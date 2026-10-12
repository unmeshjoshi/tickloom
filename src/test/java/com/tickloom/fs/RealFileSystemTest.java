package com.tickloom.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class RealFileSystemTest extends FileSystemContractTest {
    @TempDir
    Path dir;
    private RealFileSystem fileSystem;

    @Override
    TickableFileSystem fileSystem() {
        if (fileSystem == null) fileSystem = new RealFileSystem(dir, 5_000);
        return fileSystem;
    }

    @AfterEach
    void stop() {
        if (fileSystem != null) fileSystem.shutdown();
    }
}
