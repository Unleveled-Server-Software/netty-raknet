package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import raknetserver.RakNetServer;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetPacket;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.pipeline.internal.InternalTickManager;
import raknetserver.utils.Constants;
import raknetserver.utils.PacketHandlerRegistry;
import raknetserver.utils.UINT;

import java.util.concurrent.TimeUnit;

public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

    protected static final int RTT_WEIGHT = 8;
    protected static final int DEFAULT_RTT_MS = 400;
    protected static final PacketHandlerRegistry<RakNetPacketReliabilityHandler, RakNetPacket> registry = new PacketHandlerRegistry<>();

    static {
        registry.register(RakNetEncapsulatedData.class, (ctx, handler, packet) -> handler.handleEncapsulatedData(ctx, packet));
        registry.register(RakNetACK.class, (ctx, handler, packet) -> handler.handleAck(packet));
        registry.register(RakNetNACK.class, (ctx, handler, packet) -> handler.handleNack(packet));
    }

    protected final RakNetServer.Metrics metrics;
    protected final IntSortedSet nackSet = new IntRBTreeSet(UINT.B3.COMPARATOR);
    protected final IntSortedSet ackSet = new IntRBTreeSet(UINT.B3.COMPARATOR);
    protected final Int2ObjectRBTreeMap<RakNetEncapsulatedData> sentPackets = new Int2ObjectRBTreeMap<>(UINT.B3.COMPARATOR);

    protected int lastReceivedSeqId = 0;
    protected int nextSendSeqId = 0;
    protected long avgRTT = TimeUnit.NANOSECONDS.convert(DEFAULT_RTT_MS, TimeUnit.MILLISECONDS);
    protected boolean backPressureActive = false;
    protected RakNetEncapsulatedData queuedPacket = new RakNetEncapsulatedData();

    public RakNetPacketReliabilityHandler(RakNetServer.Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        queuedPacket.release();
        queuedPacket = null;
        sentPackets.values().forEach(packet -> packet.release());
        sentPackets.clear();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RakNetPacket) {
            registry.handle(ctx, this, (RakNetPacket) msg);
            InternalTickManager.checkTick(ctx);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof EncapsulatedPacket) {
            if (sentPackets.size() > Constants.MAX_PACKET_LOSS) {
                throw new DecoderException("Too big packet loss (unconfirmed sent packets)");
            }
            final EncapsulatedPacket packet = (EncapsulatedPacket) msg;
            try {
                queuePacket(ctx, packet);
                promise.trySuccess(); //TODO: more accurate way to trigger these?
                metrics.incrOutPacket(1);
            } finally {
                packet.release();
            }
        } else {
            if (msg instanceof RakNetServer.Tick) {
                tick(ctx, ((RakNetServer.Tick) msg).getTicks());
                return; //TODO: UDP channel library crashes if it gets anything besides a ByteBuf....
            }
            ctx.writeAndFlush(msg, promise);
        }
    }

    protected void handleEncapsulatedData(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
        final int packetSeqId = packet.getSeqId();
        ackSet.add(packetSeqId);
        nackSet.remove(packetSeqId);
        if (UINT.B3.minusWrap(packetSeqId, lastReceivedSeqId) > 0) {
            lastReceivedSeqId = UINT.B3.plus(lastReceivedSeqId, 1);
            while (lastReceivedSeqId != packetSeqId) { //nack any missed packets before this one
                nackSet.add(lastReceivedSeqId); //add missing packets to nack set
                lastReceivedSeqId = UINT.B3.plus(lastReceivedSeqId, 1);
            }
        }
        metrics.incrRecv(1);
        metrics.incrInPacket(packet.getNumPackets());
        packet.readTo(ctx);
    }

    protected void handleAck(RakNetACK ack) {
        int nAck = 0;
        int nIterations = 0;
        for (REntry entry : ack.getEntries()) {
            final int max = UINT.B3.plus(entry.idFinish, 1);
            for (int id = entry.idStart ; id != max ; id = UINT.B3.plus(id, 1)) {
                final RakNetEncapsulatedData packet = sentPackets.remove(id);
                if (packet != null) {
                    final long rtt = Math.max(packet.timeSinceSend(), InternalTickManager.TICK_RESOLUTION);
                    if (rtt <= Constants.MAX_RTT) {
                        avgRTT = (avgRTT * (RTT_WEIGHT - 1) + rtt) / RTT_WEIGHT;
                    }
                    packet.release();
                    metrics.measureRTTns(rtt);
                    metrics.measureSendAttempts(packet.getSendAttempts());
                    nAck++;
                }
                if (nIterations++ > Constants.MAX_PACKET_LOSS) {
                    throw new DecoderException("Too big ack confirm range");
                }
            }
        }
        metrics.incrAckRecv(nAck);
    }

    protected void handleNack(RakNetNACK nack) {
        int nNack = 0;
        int nIterations = 0;
        for (REntry entry : nack.getEntries()) {
            final int max = UINT.B3.plus(entry.idFinish, 1);
            for (int id = entry.idStart ; id != max ; id = UINT.B3.plus(id, 1)) {
                final RakNetEncapsulatedData packet = sentPackets.get(id);
                if (packet != null) {
                    packet.scheduleResend();
                    nNack++;
                }
                if (nIterations++ > Constants.MAX_PACKET_LOSS) {
                    throw new DecoderException("Too big nack confirm range");
                }
            }
        }
        metrics.incrNackRecv(nNack);
    }

    protected void tick(ChannelHandlerContext ctx, int nTicks) {
        //all data flushed in order of priority
        if (!ackSet.isEmpty()) {
            ctx.writeAndFlush(new RakNetACK(ackSet)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            metrics.incrAckSend(ackSet.size());
            ackSet.clear();
        }
        if (!nackSet.isEmpty()) {
            ctx.writeAndFlush(new RakNetNACK(nackSet)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            metrics.incrNackSend(nackSet.size());
            nackSet.clear();
        }
        flushPacket();
        final int maxResend = Constants.RESEND_PER_TICK * nTicks;
        final ObjectIterator<RakNetEncapsulatedData> packetItr = sentPackets.values().iterator();
        int nSent = 0;
        while (packetItr.hasNext()) {
            final RakNetEncapsulatedData packet = packetItr.next();
            //always evaluate resendTick
            if (packet.resendTick(nTicks) && (nSent < maxResend || packet.getSendAttempts() == 0)) {
                sendPacketRaw(ctx, packet);
                nSent++;
                if (packet.getSendAttempts() > 1) {
                    metrics.incrResend(1);
                }
            }
        }
        if (sentPackets.size() > Constants.MAX_PACKET_LOSS) {
            throw new DecoderException("Too big packet loss (resend queue)");
        } else if (sentPackets.size() > Constants.BACK_PRESSURE_HIGH_WATERMARK) {
            updateBackPressure(ctx, true);
        } else if (sentPackets.size() < Constants.BACK_PRESSURE_LOW_WATERMARK) {
            updateBackPressure(ctx, false);
        }
    }

    protected void queuePacket(ChannelHandlerContext ctx, EncapsulatedPacket packet) {
        final int maxPacketSize = ctx.channel().attr(RakNetServer.MTU).get() - 100;
        if (!queuedPacket.isEmpty() && (queuedPacket.getRoughPacketSize() + packet.getRoughPacketSize()) > maxPacketSize) {
            flushPacket();
        }
        if (!queuedPacket.isEmpty()) {
            metrics.incrJoin(1);
        }
        queuedPacket.addPacket(packet);
    }

    protected void registerPacket(RakNetEncapsulatedData packet) {
        packet.setSeqId(nextSendSeqId);
        nextSendSeqId = UINT.B3.plus(nextSendSeqId, 1);
        sentPackets.put(packet.getSeqId(), packet);
    }

    protected void sendPacketRaw(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
        packet.refreshResend((int) (avgRTT / InternalTickManager.TICK_RESOLUTION)); // number of ticks per RTT
        ctx.writeAndFlush(packet.retain()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        metrics.incrSend(1);
    }

    protected void flushPacket() {
        if (!queuedPacket.isEmpty()) {
            registerPacket(queuedPacket);
            queuedPacket = new RakNetEncapsulatedData();
        }
    }

    protected void updateBackPressure(ChannelHandlerContext ctx, boolean enabled) {
        if (backPressureActive == enabled) {
            return;
        }
        backPressureActive = enabled;
        ctx.fireChannelRead(backPressureActive ? RakNetServer.BackPressure.ON : RakNetServer.BackPressure.OFF);
    }

}
