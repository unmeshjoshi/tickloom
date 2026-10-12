package com.tickloom.fs;

import com.tickloom.Tickable;

/*
 * A FileIO driven by tick().
 *
 * Threading
 *   - Call every method, including tick(), from one thread: the loop thread.
 *   - Futures complete only inside tick(), on that thread.
 */
public interface TickableFileIO extends FileIO, Tickable {

    /** Completes the futures of finished calls. Call about once per millisecond from the loop thread. */
    @Override
    void tick();
}
