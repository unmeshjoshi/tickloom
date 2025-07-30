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
    private static final int READ_BUFFER_SIZE = 64 * 1024;              // 64KB scratch
    private static final int MAX_PAYLOAD_SIZE = Frame.MAX_PAYLOAD_SIZE; // from your Frame

    /* ---------- scratch + output ---------- */
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
    private final Deque<Frame> ready = new ArrayDeque<>();

    /* ---------- per‑frame assembly state ---------- */
    private int  curStreamId   = -1;
    private byte curFrameType  = -1;
    private int  curPayloadLen = -1;
    private ByteBuffer payloadBuf;          // null until header parsed

    /* ============================================== */

    public ReadResult readFrom(SocketChannel ch) throws IOException {
        int n = ch.read(readBuffer);
        if (n == -1) throw new EOFException("peer closed");
        if (n == 0 && ready.isEmpty()) return ReadResult.incomplete();

        readBuffer.flip();
        while (tryAssemble()) { /* keep looping */ }

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

        return ready.isEmpty() ? ReadResult.incomplete()
                : ReadResult.frameComplete();
    }

    public Frame pollFrame() {
        return ready.pollFirst();
    }

    /* -------------- INTERNAL -------------- */

    /** Returns true if a complete frame was produced and queued. */
    /* -------------------------------------------------------------------------
     *  What can arrive in one selector pass?
     *
     *  ┌──────────────────────────────┬────────────────────────────────────────┐
     *  │  Case                         │  Example (§ = 64KB scratch buffer)   │
     *  ├──────────────────────────────┼────────────────────────────────────────┤
     *  │ A. **Many small frames**      │  [hdr+payload]  [hdr+payload]  ...    │
     *  │    – each frame (≤ a few KB)  │  Entire 64KB fill may contain 10‑50  │
     *  │    – zero partials            │  complete frames. We must loop until  │
     *  │                               │  tryExtractFrame() returns false.     │
     *  ├──────────────────────────────┼────────────────────────────────────────┤
     *  │ B. **Mixed**                  │  [complete frame] [partial frame]  │
     *  │    – last frame incomplete    │  The trailing partial’s header/prefix│
     *  │                               │  is here, but body spills to next     │
     *  │                               │  read(). We copy what we have now and │
     *  │                               │  leave payloadBuf with .hasRemaining().│
     *  ├──────────────────────────────┼────────────────────────────────────────┤
     *  │ C. **Single jumbo frame**     │  [hdr 300KB ...]          │
     *  │    – payload » 64KB          │  We receive the message piecemeal over│
     *  │                               │  several read() calls. Each iteration │
     *  │                               │  copies **bytesToCopy** into the growing│
     *  │                               │  payloadBuf until it fills.          │
     *  └──────────────────────────────┴────────────────────────────────────────┘
     *
     *  The copy‑loop below handles all three cases uniformly:
     *    • iterates until no more complete frames can be produced (A, B) or
     *      the scratch buffer is exhausted (C).
     *    • payloadBuf tracks progress for the in‑flight partial payload(B, C).
     * ----------------------------------------------------------------------- */

    private boolean tryAssemble() {
        // Step 1: header
        if (payloadBuf == null) {
            if (readBuffer.remaining() < HEADER_SIZE) return false;

            curStreamId   = readBuffer.getInt();
            curFrameType  = readBuffer.get();
            curPayloadLen = readBuffer.getInt();

            if (curPayloadLen < 0 || curPayloadLen > MAX_PAYLOAD_SIZE)
                throw new IllegalStateException("Bad payload len: " + curPayloadLen);

            payloadBuf = ByteBuffer.allocateDirect(curPayloadLen);
        }

        int noOfBytesCopied = copyPayload();

// Consume those bytes in the scratch buffer
        readBuffer.position(readBuffer.position() + noOfBytesCopied);


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

    /* -------------------------------------------------------------------------
     *  Copy bytes from the readBuffer into the per‑frame payloadBuf
     *
     *  Why we can’t just call payloadBuf.put(readBuffer):
     *    • payloadBuf might require more bytes than are currently available
     *      in readBuffer (e.g. when assembling a 300KB message with a 64KB
     *      scratch buffer).  We therefore move data incrementally.
     *
     *  Algorithm
     *    1. Calculate bytesToCopy = min(bytes available NOW, bytes still needed)
     *    2. Create a light‑weight *view* (slice) over exactly that many bytes.
     *       * slice() starts at readBuffer.position()
     *       * We shrink the slice’s limit() to cap it at bytesToCopy
     *    3. Bulk‑copy the slice into payloadBuf.
     *       − This advances payloadBuf.position() by bytesToCopy.
     *    4. Manually advance readBuffer.position() by bytesToCopy
     *       (so the next iteration starts at the correct spot).
     * ----------------------------------------------------------------------- */
    private int copyPayload() {

        int bytesToCopy = Math.min(readBuffer.remaining(),   // bytes currently in scratch
                payloadBuf.remaining());  // bytes still needed

// Create a view onto just the bytes we’ll move this round
        ByteBuffer chunk = readBuffer.slice();   // view: pos → limit
        chunk.limit(bytesToCopy);                // shrink view to bytesToCopy

// Bulk transfer into the accumulating payload
        payloadBuf.put(chunk);
        return bytesToCopy;
    }
}
