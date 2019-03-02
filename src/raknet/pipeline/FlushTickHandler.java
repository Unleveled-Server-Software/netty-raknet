package raknet.pipeline;

import io.netty.channel.*;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

public class FlushTickHandler extends ChannelOutboundHandlerAdapter {

    public static final String NAME_IN = "rn-tick-in";
    public static final String NAME_OUT = "rn-tick-out";
    public static final long TICK_RESOLUTION = TimeUnit.NANOSECONDS.convert(5, TimeUnit.MILLISECONDS);

    protected static final long COARSE_TIMER_RESOLUTION = 50; //in ms, limited by netty timer resolution

    protected long tickAccum = 0;
    protected long lastTickAccum = System.nanoTime();
    protected ChannelHandlerContext ctx;
    protected ScheduledFuture timer;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.ctx = ctx;
        timer = ctx.channel().eventLoop().scheduleAtFixedRate(
                this::maybeFlush, COARSE_TIMER_RESOLUTION, COARSE_TIMER_RESOLUTION, TimeUnit.MILLISECONDS);
        ctx.channel().pipeline().addFirst(NAME_IN, new InboundHandler());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        timer.cancel(true);
        timer = null;
        this.ctx = null;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        maybeFlush();
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        //force flush, lets adjust tickAccum
        if (tickAccum >= TICK_RESOLUTION) {
            tickAccum -= TICK_RESOLUTION;
        } else {
            tickAccum = 0;
        }
        super.flush(ctx);
    }

    protected void maybeFlush() {
        if (ctx == null) {
            return;
        }
        final long curTime = System.nanoTime();
        tickAccum += curTime - lastTickAccum;
        lastTickAccum = curTime;
        while (tickAccum >= TICK_RESOLUTION) {
            tickAccum -= TICK_RESOLUTION;
            ctx.flush();
        }
    }

    protected final class InboundHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, msg);
            maybeFlush();
        }
    }

}