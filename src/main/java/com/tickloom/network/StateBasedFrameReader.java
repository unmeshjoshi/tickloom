package com.tickloom.network;

import java.nio.ByteBuffer;
import java.util.Optional;

public class StateBasedFrameReader {
        State state;
        public StateBasedFrameReader() {
            this.state = new ReadingHeader(ByteBuffer.allocate(Header.SIZE));
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
            Header header = Header.readFrom(buffer);

            ByteBuffer payloadBuf = ByteBuffer.allocate(header.payloadLength());
            context.setState(new ReadingPayload(header, payloadBuf));
            return Optional.empty();
        }
    }

    public static class ReadingPayload extends State {
        private final Header header;

        public ReadingPayload(Header header, ByteBuffer payloadBuf) {
            super(payloadBuf);
            this.header = header;
        }

        @Override
        public Optional<Frame> onComplete(StateBasedFrameReader context, ByteBuffer payloadBuf) {
            Frame frame = new Frame(header, payloadBuf);
            context.setState(new ReadingHeader(ByteBuffer.allocate(Header.SIZE)));
            return Optional.of(frame);
        }
    }
}
