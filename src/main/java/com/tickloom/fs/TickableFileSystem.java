package com.tickloom.fs;

import com.tickloom.Tickable;

/** A FileSystem driven by tick(), which also ticks the files it opened. Call it from the loop thread. */
public interface TickableFileSystem extends FileSystem, Tickable {
    @Override
    void tick();
}
