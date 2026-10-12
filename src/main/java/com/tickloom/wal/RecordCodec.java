package com.tickloom.wal;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Record format: {@code [length: 4][crc32c: 4][payload]}. The CRC covers the length field and the payload.
 * A length of zero or above the maximum is invalid, which also stops a decoder from trusting a garbage length.
 * Decoding never throws on bad bytes; it reports {@link Status#INVALID} or {@link Status#INCOMPLETE}.
 */
final class RecordCodec {
    static final int HEADER_SIZE = 8;
    static final int DEFAULT_MAX_PAYLOAD = 1024 * 1024;

    enum Status {OK, INCOMPLETE, INVALID}

    /** The result of decoding one record. {@code size} is the encoded size, set only when the status is OK. */
    record Decoded(Status status, byte[] payload, int size) {
        static final Decoded INCOMPLETE = new Decoded(Status.INCOMPLETE, null, 0);
        static final Decoded INVALID = new Decoded(Status.INVALID, null, 0);
    }

    private final int maxPayload;

    RecordCodec(int maxPayload) {
        if (maxPayload <= 0) {
            throw new IllegalArgumentException("maxPayload must be positive");
        }
        this.maxPayload = maxPayload;
    }

    /** Encodes every payload, in order, into one buffer. Rejects an empty batch, an empty payload or an oversized one. */
    byte[] encode(List<byte[]> payloads) {
        if (payloads.isEmpty()) {
            throw new IllegalArgumentException("empty batch");
        }
        int total = 0;
        for (byte[] payload : payloads) {
            if (payload.length == 0 || payload.length > maxPayload) {
                throw new IllegalArgumentException("payload length " + payload.length + " not in 1.." + maxPayload);
            }
            total += HEADER_SIZE + payload.length;
        }
        ByteBuffer out = ByteBuffer.allocate(total);
        for (byte[] payload : payloads) {
            out.putInt(payload.length);
            out.putInt(crc(payload.length, payload, 0));
            out.put(payload);
        }
        return out.array();
    }

    /** Decodes the record that starts at {@code position}, looking at bytes before {@code limit} only. */
    Decoded decode(byte[] buffer, int position, int limit) {
        if (limit - position < HEADER_SIZE) {
            return Decoded.INCOMPLETE;
        }
        ByteBuffer in = ByteBuffer.wrap(buffer, position, limit - position);
        int length = in.getInt();
        int expectedCrc = in.getInt();
        if (length <= 0 || length > maxPayload) {
            return Decoded.INVALID;
        }
        if (limit - position - HEADER_SIZE < length) {
            return Decoded.INCOMPLETE;
        }
        int payloadStart = position + HEADER_SIZE;
        if (crc(length, buffer, payloadStart) != expectedCrc) {
            return Decoded.INVALID;
        }
        byte[] payload = new byte[length];
        System.arraycopy(buffer, payloadStart, payload, 0, length);
        return new Decoded(Status.OK, payload, HEADER_SIZE + length);
    }

    private static int crc(int length, byte[] bytes, int payloadStart) {
        // Length and payload in one pass. When encoding, the payload starts at 0 of its own array.
        CRC32C crc = new CRC32C();
        crc.update(length >>> 24);
        crc.update(length >>> 16);
        crc.update(length >>> 8);
        crc.update(length);
        crc.update(bytes, payloadStart, length);
        return (int) crc.getValue();
    }
}
