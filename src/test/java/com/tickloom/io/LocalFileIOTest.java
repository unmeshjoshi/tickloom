package com.tickloom.io;

import com.tickloom.future.ListenableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileIOTest {

    @TempDir
    Path tempDir;

    private LocalFileIO fileIO;

    @BeforeEach
    void setUp() throws Exception {
        Path filePath = tempDir.resolve("test.dat");
        fileIO = LocalFileIO.open(filePath.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        fileIO.close();
    }

    @Test
    void writeAndReadBack() {
        byte[] data = "hello".getBytes();

        ListenableFuture<Integer> writeFuture = fileIO.write(data, 0);
        assertTrue(writeFuture.isCompleted());
        assertEquals(5, writeFuture.getResult());

        ListenableFuture<byte[]> readFuture = fileIO.read(0, 5);
        assertTrue(readFuture.isCompleted());
        assertArrayEquals(data, readFuture.getResult());
    }

    @Test
    void writeAtOffset() {
        fileIO.write("aaaa".getBytes(), 0);
        fileIO.write("bb".getBytes(), 2);

        ListenableFuture<byte[]> readFuture = fileIO.read(0, 4);
        assertArrayEquals("aabb".getBytes(), readFuture.getResult());
    }

    @Test
    void writeExtendsFile() {
        fileIO.write("abc".getBytes(), 0);
        assertEquals(3, fileIO.size());

        fileIO.write("de".getBytes(), 10);
        assertEquals(12, fileIO.size());
    }

    @Test
    void readBeyondSize() {
        fileIO.write("abc".getBytes(), 0);

        ListenableFuture<byte[]> readFuture = fileIO.read(0, 100);
        assertEquals(3, readFuture.getResult().length);
        assertArrayEquals("abc".getBytes(), readFuture.getResult());
    }

    @Test
    void truncateShrinks() {
        fileIO.write("abcdef".getBytes(), 0);
        assertEquals(6, fileIO.size());

        ListenableFuture<Void> truncateFuture = fileIO.truncate(3);
        assertTrue(truncateFuture.isCompleted());
        assertEquals(3, fileIO.size());

        ListenableFuture<byte[]> readFuture = fileIO.read(0, 3);
        assertArrayEquals("abc".getBytes(), readFuture.getResult());
    }

    @Test
    void syncCompletes() {
        fileIO.write("data".getBytes(), 0);

        ListenableFuture<Void> syncFuture = fileIO.sync();
        assertTrue(syncFuture.isCompleted());
    }

    @Test
    void overwriteSameOffset() {
        fileIO.write("first_".getBytes(), 0);
        fileIO.write("second".getBytes(), 0);

        ListenableFuture<byte[]> readFuture = fileIO.read(0, 6);
        assertArrayEquals("second".getBytes(), readFuture.getResult());
    }

    @Test
    void getFilenameReturnsPath() {
        assertTrue(fileIO.getFilename().endsWith("test.dat"));
    }

    @Test
    void dataSurvivesCloseAndReopen() throws Exception {
        fileIO.write("durable".getBytes(), 0);
        fileIO.sync();
        String path = fileIO.getFilename();
        fileIO.close();

        LocalFileIO reopened = LocalFileIO.open(path);
        assertEquals(7, reopened.size());
        ListenableFuture<byte[]> readFuture = reopened.read(0, 7);
        assertArrayEquals("durable".getBytes(), readFuture.getResult());
        reopened.close();
    }
}
