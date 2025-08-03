package com.tickloom.network;

import java.nio.ByteBuffer;
import java.util.Optional;

import static com.tickloom.network.Frame.HEADER_SIZE;
import static com.tickloom.network.Frame.MAX_PAYLOAD_SIZE;

public class StateBasedFrameReader {
        State state;
        public StateBasedFrameReader() {
            this.state = new ReadingHeader(ByteBuffer.allocate(HEADER_SIZE));
        }
        public void setState(State state) {
            this.state = state;
        }
        public Optional<Frame> tryReading(ByteBuffer buf) {
            return state.tryReading(this, buf);
        }


    static abstract class State {
        ByteBuffer to;
        public State(ByteBuffer buffer) {
            this.to = buffer;
        }

        public Optional<Frame> tryReading(StateBasedFrameReader context, ByteBuffer from) {
            int bytesToCopy = Math.min(from.remaining(), to.remaining());
            ByteBuffer sliceToCopy = from.slice(from.position(), bytesToCopy);
            to.put(sliceToCopy);
            from.position(from.position() + bytesToCopy);
            if (to.remaining() == 0) {
                to.flip();
                return onComplete(context,to);
            }
            return Optional.empty();
        }
        public abstract Optional<Frame> onComplete(StateBasedFrameReader context, ByteBuffer buffer);
    }

    public static class ReadingHeader extends State {
        public ReadingHeader(ByteBuffer buf) {
            super(buf);
        }
        public Optional<Frame> onComplete(StateBasedFrameReader context, ByteBuffer buffer) {
            int streamId = buffer.getInt();
            byte type = buffer.get();
            int payloadLength = buffer.getInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE)
                throw new IllegalStateException("Bad payload len: " + payloadLength);

            ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLength);
            context.setState(new ReadingPayload(streamId,type,payloadBuf));
            return Optional.empty();
        }
    }

    public static class ReadingPayload extends State {

        private final int streamId;
        private final byte type;

        public ReadingPayload(int streamId, byte type, ByteBuffer buffer) {
            super(buffer);
            this.streamId = streamId;
            this.type = type;
        }

        @Override
        public Optional<Frame> onComplete(StateBasedFrameReader context, ByteBuffer payloadBuf) {
            Frame frame = new Frame(streamId, type, payloadBuf);
            context.setState(new ReadingHeader(ByteBuffer.allocate(HEADER_SIZE)));
            return Optional.of(frame);
        }
    }
}
