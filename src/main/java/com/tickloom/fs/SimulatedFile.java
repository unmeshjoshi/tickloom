package com.tickloom.fs;

import java.util.Arrays;

// TODO: faults, once tickloom runs on this and we know we need them. Not designed in detail yet:
//  - Partial copy at a crash: for each 512-byte sector where visible differs from durable, randomly keep the old
//    sector, land the new one, or land it and mark it bad. A sector is index offset / 512; no sector objects needed.
//    Calls still queued at the crash stay dropped. Truncate lands whole or not at all.
//  - Bad sectors (TigerBeetle style): a set of sector numbers kept beside the clean bytes, in two copies like the
//    bytes (visible and durable). A read that touches one returns garbled bytes; a write that covers the whole
//    sector clears the mark.
//  - A hook to fail the next call (FDB io_error), to exercise Wal poisoning on the real stack.
//  - The WalCrashTest oracle then changes: recovered is a prefix of what was appended and contains every
//    acknowledged record, instead of equalling the acknowledged records.

/**
 * The bytes of one simulated file, in two copies. Reads and writes use {@code visible}. A sync copies visible to
 * durable. A crash throws away visible and starts again from durable.
 */
final class SimulatedFile {
    private byte[] visible = new byte[0];
    private byte[] durable = new byte[0];

    long size() {
        return visible.length;
    }

    byte[] read(long offset, int length) {
        if (offset >= visible.length) {
            return new byte[0];
        }
        int from = (int) offset;
        return Arrays.copyOfRange(visible, from, Math.min(visible.length, from + length));
    }

    void write(long offset, byte[] data) {
        long end = offset + data.length;
        if (end > visible.length) {
            visible = Arrays.copyOf(visible, (int) end);
        }
        System.arraycopy(data, 0, visible, (int) offset, data.length);
    }

    void truncate(long newSize) {
        if (newSize < visible.length) {
            visible = Arrays.copyOf(visible, (int) newSize);
        }
    }

    void sync() {
        durable = visible.clone();
    }

    /** On restart, what is on disk is what becomes visible. */
    void crash() {
        visible = durable.clone();
    }
}
