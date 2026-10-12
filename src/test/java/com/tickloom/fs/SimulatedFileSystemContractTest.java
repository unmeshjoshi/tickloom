package com.tickloom.fs;

class SimulatedFileSystemContractTest extends FileSystemContractTest {
    private final SimulatedFileSystem fileSystem = new SimulatedFileSystem();

    @Override
    TickableFileSystem fileSystem() {
        return fileSystem;
    }
}
