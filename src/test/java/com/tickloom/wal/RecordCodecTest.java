package com.tickloom.wal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordCodecTest {
    private final RecordCodec codec = new RecordCodec(100);

    @Test
    void roundTripsABatch() {
        byte[] encoded = codec.encode(List.of("one".getBytes(), "three".getBytes()));
        assertEquals(8 + 3 + 8 + 5, encoded.length);

        RecordCodec.Decoded first = codec.decode(encoded, 0, encoded.length);
        assertEquals(RecordCodec.Status.OK, first.status());
        assertArrayEquals("one".getBytes(), first.payload());
        assertEquals(11, first.size());

        RecordCodec.Decoded second = codec.decode(encoded, first.size(), encoded.length);
        assertEquals(RecordCodec.Status.OK, second.status());
        assertArrayEquals("three".getBytes(), second.payload());
    }

    @Test
    void aFlippedPayloadBitIsInvalid() {
        byte[] encoded = codec.encode(List.of("payload".getBytes()));
        encoded[encoded.length - 1] ^= 1;
        assertEquals(RecordCodec.Status.INVALID, codec.decode(encoded, 0, encoded.length).status());
    }

    @Test
    void aFlippedLengthBitIsInvalid() {
        byte[] encoded = codec.encode(List.of("payload".getBytes()));
        encoded[3] ^= 1;   // length 7 becomes 6
        assertEquals(RecordCodec.Status.INVALID, codec.decode(encoded, 0, encoded.length).status());
    }

    @Test
    void aTruncatedRecordIsIncomplete() {
        byte[] encoded = codec.encode(List.of("payload".getBytes()));
        assertEquals(RecordCodec.Status.INCOMPLETE, codec.decode(encoded, 0, encoded.length - 1).status());
        assertEquals(RecordCodec.Status.INCOMPLETE, codec.decode(encoded, 0, 3).status());
        assertEquals(RecordCodec.Status.INCOMPLETE, codec.decode(encoded, 0, 0).status());
    }

    @Test
    void zeroBytesAreInvalid() {
        byte[] zeros = new byte[32];
        assertEquals(RecordCodec.Status.INVALID, codec.decode(zeros, 0, zeros.length).status());
    }

    @Test
    void aLengthOverTheMaximumIsInvalid() {
        byte[] encoded = new RecordCodec(1000).encode(List.of(new byte[101]));
        assertEquals(RecordCodec.Status.INVALID, codec.decode(encoded, 0, encoded.length).status());
    }

    @Test
    void encodeRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of()));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(new byte[101])));
    }
}
