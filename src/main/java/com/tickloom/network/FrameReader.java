package com.tickloom.network;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Bulk‑read FrameReader that handles payloads larger than the scratch buffer.
 * <p>
 * Frame wire format:
 * 4‑byte streamId │ 1‑byte type │ 4‑byte payloadLen │ payload…
 /* -------------------------------------------------------------------------
 *  What can arrive in one selector pass?
 *
 *  ┌──────────────────────────────┬────────────────────────────────────────┐
 *  │  Case                         │  Example (§ = 64KB scratch buffer)   │
 *  ├──────────────────────────────┼────────────────────────────────────────┤
 *  │ A. **Many small frames**      │  [hdr+payload]  [hdr+payload]  ...    │
 *  │    – each frame (≤ a few KB)  │  Entire 64KB fill may contain 10‑50  │
 *  │    – zero partials            │  complete frames. We must loop until  │
 *  │                               │  buffer is fully read()               │
 *  ├──────────────────────────────┼────────────────────────────────────────┤
 *  │ B. **Mixed**                  │  [complete frame] [partial frame]  │
 *  │    – last frame incomplete    │  The trailing partial’s header/prefix│
 *  │                               │  is here, but body spills to next     │
 *  │                               │  read(). We copy what we have now and │
 *  │                               │  leave payloadBuf with .hasRemaining().│
 *  ├──────────────────────────────┼────────────────────────────────────────┤
 *  │ C. **Single jumbo frame**     │  [hdr 300KB ...]          │
 *  │    – payload » 64KB           │  We receive the message piecemeal over│
 *  │                               │  several read() calls. Each iteration │
 *  │                               │  copies **bytesToCopy** into the growing│
 *  │                               │  payloadBuf until it fills.          │
 *  └──────────────────────────────┴────────────────────────────────────────┘
 */
public final class FrameReader {

    /* ---------- constants ---------- */
    private static final int HEADER_SIZE = Header.SIZE;      // 9
    private static final int READ_BUFFER_SIZE = 64 * 1024;              // 64KB scratch
    private static final int MAX_PAYLOAD_SIZE = Frame.MAX_PAYLOAD_SIZE; // from your Frame

    /* ---------- scratch + output ---------- */
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
    private final Deque<Frame> ready = new ArrayDeque<>();


    private State state = new ReadingHeader(ByteBuffer.allocate(Header.SIZE));

    public void setState(State state) {
        this.state = state;
    }
    /* ============================================== */

    public ReadResult readFrom(SocketChannel ch) throws IOException {
        int n = ch.read(readBuffer);
        if (n == -1) throw new EOFException("peer closed");
        if (n == 0 && ready.isEmpty()) return ReadResult.incomplete();

        readBuffer.flip();

        readAllFramesFrom(readBuffer);

// After we’ve extracted as many complete frames as possible, there may be
// a *partial* header or body left in the scratch buffer.  We need to keep
// those bytes for the next selector pass, but also make room for more.
//
// compact():
//   • copies the unread bytes (position → limit) down to index
//   • sets position = remaining, limit = capacity
// This preserves the incomplete frame *and* switches the buffer back to
// WRITE mode so a subsequent channel.read(...) can append new data.


        readBuffer.compact();
//
        return ready.isEmpty() ? ReadResult.incomplete()
                : ReadResult.frameComplete();
    }

    public void readAllFramesFrom(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            Optional<Frame> frame = state.tryReading(this, buffer);
            if (frame.isPresent()) {
                ready.addLast(frame.get());
            }
        }
    }

    public Frame pollFrame() {
        return ready.pollFirst();
    }


    static abstract class State {
        ByteBuffer to;
        public State(ByteBuffer buffer) {
            this.to = buffer;
        }

        public Optional<Frame> tryReading(FrameReader context, ByteBuffer from) {
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
        public abstract Optional<Frame> onComplete(FrameReader context, ByteBuffer buffer);
    }

    public static class ReadingHeader extends State {
        public ReadingHeader(ByteBuffer buf) {
            super(buf);
        }
        public Optional<Frame> onComplete(FrameReader context, ByteBuffer buffer) {
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
        public Optional<Frame> onComplete(FrameReader context, ByteBuffer payloadBuf) {
            Frame frame = new Frame(header, payloadBuf);
            context.setState(new ReadingHeader(ByteBuffer.allocate(Header.SIZE)));
            return Optional.of(frame);
        }
    }
}
