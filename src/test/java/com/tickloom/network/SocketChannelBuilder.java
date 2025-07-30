package com.tickloom.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito-based builder that produces a stubbed {@link SocketChannel} for unit tests.
 * <p>
 * The caller can enqueue any number of byte-array <em>chunks</em> with {@link #thatReads(byte[])}
 * (or its var-arg overload).  Each invocation of {@code read(dst)} will drain <strong>exactly
 * one</strong> chunk into {@code dst}—partial copies occur when {@code dst.remaining()} is smaller
 * than the current chunk’s unread bytes.
 * <ul>
 *   <li>When the current chunk is exhausted the builder advances to the next chunk on the next
 *       {@code read()} call.</li>
 *   <li>After the final chunk is consumed, subsequent {@code read()} calls return {@code -1}
 *       (EOF), emulating a peer that closed the stream cleanly.</li>
 * </ul>
 * <p>
 * Usage example:
 * <pre>{@code
 * SocketChannel channel = SocketChannelBuilder.aChannel()
 *         .thatReads(headerBytes)          // first read returns header
 *         .thatReads(bodyPart1, bodyPart2) // second and third reads
 *         .build();
 * }</pre>
 */
public class SocketChannelBuilder {

    /** Ordered list of chunks still to be delivered. */
    private final List<byte[]> chunks = new ArrayList<>();

    /** If {@code true}, {@code read()} will return -1 immediately. */
    private boolean closed = false;

    /* ------------------------------------------------------------------ *
     *  DSL-style factory helpers                                         *
     * ------------------------------------------------------------------ */

    public static SocketChannelBuilder aChannel() {
        return new SocketChannelBuilder();
    }

    /* ------------------------------------------------------------------ *
     *  Configuration                                                      *
     * ------------------------------------------------------------------ */

    /**
     * Enqueue a single chunk that will be returned by one {@code read()} call.
     */
    public SocketChannelBuilder thatReads(byte[] data) {
        this.chunks.add(data);
        return this;
    }

    /**
     * Enqueue multiple chunks in order.  Each array element corresponds to one future
     * {@code read()} call against the stubbed channel.
     */
    public SocketChannelBuilder thatReads(byte[]... many) {
        this.chunks.addAll(Arrays.asList(many));
        return this;
    }

    /**
     * Simulate a socket that is already closed by the peer:
     * {@code read()} will always return {@code -1}.
     */
    public SocketChannelBuilder thatIsClosed() {
        this.closed = true;
        return this;
    }

    /* ------------------------------------------------------------------ *
     *  Build                                                              *
     * ------------------------------------------------------------------ */

    /**
     * Build the Mockito-backed {@link SocketChannel} stub with the configured behaviour.
     */
    public SocketChannel build() throws IOException {
        SocketChannel ch = mock(SocketChannel.class);

        /* -------------------------------------------------------------- *
         * Closed channel path – trivial: always -1.                      *
         * -------------------------------------------------------------- */
        if (closed) {
            when(ch.read(any(ByteBuffer.class))).thenReturn(-1);
            return ch;
        }

        /* -------------------------------------------------------------- *
         * Open channel path – maintain mutable read-state across calls.  *
         * We capture the indices inside small final arrays so that the   *
         * lambda can mutate them (effectively simulating a little state  *
         * machine).                                                      *
         * -------------------------------------------------------------- */
        final int[] chunkIndex   = {0}; // which chunk we’re on
        final int[] chunkOffset  = {0}; // how many bytes of the chunk have been read

        when(ch.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            /* All chunks consumed? Signal EOF. */
            if (chunkIndex[0] >= chunks.size()) {
                return -1;
            }

            ByteBuffer dst   = invocation.getArgument(0);
            byte[] curChunk  = chunks.get(chunkIndex[0]);

            /* How many unread bytes remain in this chunk? */
            int remainingInChunk = curChunk.length - chunkOffset[0];
            /* How many bytes can we actually copy on this call? */
            int toCopy = Math.min(dst.remaining(), remainingInChunk);

            /* Copy bytes into destination buffer. */
            dst.put(curChunk, chunkOffset[0], toCopy);

            /* Advance offset inside the chunk. */
            chunkOffset[0] += toCopy;

            /* If entire chunk is consumed, advance to the next one. */
            if (chunkOffset[0] == curChunk.length) {
                chunkIndex[0]++;
                chunkOffset[0] = 0;
            }
            return toCopy; // number of bytes "read"
        });

        return ch;
    }
}
