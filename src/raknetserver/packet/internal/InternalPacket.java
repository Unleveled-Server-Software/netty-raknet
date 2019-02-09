package raknetserver.packet.internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public interface InternalPacket {

	int getPacketId();

    Reliability getReliability();

    int getOrderId();

    void decode(ByteBuf buf);

    void encode(ByteBuf buf);

    void encodeFull(ByteBuf buf);

    InternalPacketData toInternalPacketData(ByteBufAllocator alloc);

    enum Reliability {
        //						  REL    ORD    SEQ    ACK
        UNRELIABLE				(false, false, false, false),
        UNRELIABLE_SEQUENCED	(false, true,  true,  false),
        RELIABLE				(true,  false, false, false),
        RELIABLE_ORDERED		(true,  true,  false, false),
        RELIABLE_SEQUENCED		(true,  true,  true,  false),
        UNRELIABLE_ACK			(false, false, false, true ),
        RELIABLE_ACK			(true,  false, false, true ),
        RELIABLE_ORDERED_ACK	(true,  true,  false, true );

        public static Reliability get(int code) {
            assert code >= 0 && code < values().length;
            return values()[code];
        }

        public final boolean isReliable;
        public final boolean isOrdered;
        public final boolean isSequenced;
        public final boolean isAckd;

        Reliability(boolean isReliable, boolean isOrdered, boolean isSequenced, boolean isAckd) {
            this.isReliable = isReliable;
            this.isOrdered = isOrdered;
            this.isSequenced = isSequenced;
            this.isAckd = isAckd;
        }

        public int code() {
            return ordinal();
        }
    }

}
