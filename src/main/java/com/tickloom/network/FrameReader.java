package com.tickloom.network;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bulk‑read FrameReader that handles payloads larger than the scratch buffer.
 *
 * Frame wire format:
 *   4‑byte streamId │ 1‑byte type │ 4‑byte payloadLen │ payload…
 */
public final class FrameReader {

    /* ---------- constants ---------- */
    private static final int HEADER_SIZE      = Frame.HEADER_SIZE;      // 9
    private static final int SCRATCH_SIZE     = 64 * 1024;              // 64 KB scratch
    private static final int MAX_PAYLOAD_SIZE = Frame.MAX_PAYLOAD_SIZE; // from your Frame

    /* ---------- scratch + output ---------- */
    private final ByteBuffer scratch = ByteBuffer.allocateDirect(SCRATCH_SIZE);
    private final Deque<Frame> ready = new ArrayDeque<>();

    /* ---------- per‑frame assembly state ---------- */
    private int  curStreamId   = -1;
    private byte curFrameType  = -1;
    private int  curPayloadLen = -1;
    private ByteBuffer payloadBuf;          // null until header parsed

    /* ============================================== */

    public ReadResult readFrom(SocketChannel ch) throws IOException {
        int n = ch.read(scratch);
        if (n == -1) throw new EOFException("peer closed");
        if (n == 0 && ready.isEmpty()) return ReadResult.incomplete();

        scratch.flip();
        while (tryAssemble()) { /* keep looping */ }
        scratch.compact();

        return ready.isEmpty() ? ReadResult.incomplete()
                : ReadResult.frameComplete();
    }

    public Frame pollFrame() {
        return ready.pollFirst();
    }

    /* -------------- INTERNAL -------------- */

    /** Returns true if a complete frame was produced and queued. */
    private boolean tryAssemble() {
        // Step 1: header
        if (payloadBuf == null) {
            if (scratch.remaining() < HEADER_SIZE) return false;

            curStreamId   = scratch.getInt();
            curFrameType  = scratch.get();
            curPayloadLen = scratch.getInt();

            if (curPayloadLen < 0 || curPayloadLen > MAX_PAYLOAD_SIZE)
                throw new IllegalStateException("Bad payload len: " + curPayloadLen);

            payloadBuf = ByteBuffer.allocateDirect(curPayloadLen);
        }

        // Step 2: copy as much as we have into the payload buffer
        int toCopy = Math.min(scratch.remaining(), payloadBuf.remaining());
        int oldLim = scratch.limit();
        scratch.limit(scratch.position() + toCopy);
        payloadBuf.put(scratch);            // bulk copy
        scratch.limit(oldLim);

        // Step 3: finished?
        if (!payloadBuf.hasRemaining()) {
            payloadBuf.flip();
            ready.addLast(new Frame(curStreamId, curFrameType, payloadBuf));

            // reset state for next frame
            payloadBuf     = null;
            curStreamId    = -1;
            curFrameType   = -1;
            curPayloadLen  = -1;
            return true;                    // produced one frame
        }
        return false;                       // need more bytes
    }
}
