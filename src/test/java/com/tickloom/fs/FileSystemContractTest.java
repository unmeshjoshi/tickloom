package com.tickloom.fs;

import org.junit.jupiter.api.Test;
import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The FileSystem contract. Subclasses supply the implementation; the tests are the same for every one. */
abstract class FileSystemContractTest {
    abstract TickableFileSystem fileSystem();

    void tickUntilDone(TickCompletableFuture<?>... futures) throws InterruptedException {
        for (int i = 0; i < 5_000; i++) {
            boolean pending = false;
            for (TickCompletableFuture<?> f : futures) pending |= f.isPending();
            if (!pending) return;
            fileSystem().tick();
            Thread.sleep(0, 200_000);
        }
        fail("still pending after 5000 ticks");
    }

    <T> T await(TickCompletableFuture<T> future) throws Exception {
        tickUntilDone(future);
        if (future.isFailed()) throw (Exception) future.getException();
        return future.getResult();
    }

    @Test
    void createThenOpenFindsTheBytes() throws Exception {
        FileIO created = await(fileSystem().open("a", OpenMode.CREATE_NEW));
        await(created.write(ByteBuffer.wrap("hello".getBytes()), 0));
        await(created.close());

        FileIO reopened = await(fileSystem().open("a", OpenMode.OPEN_EXISTING));
        ByteBuffer read = await(reopened.read(0, 10));
        assertEquals("hello", new String(read.array(), 0, read.remaining()));
    }

    @Test
    void createNewOnAnExistingNameFails() throws Exception {
        await(fileSystem().open("a", OpenMode.CREATE_NEW));
        TickCompletableFuture<FileIO> again = fileSystem().open("a", OpenMode.CREATE_NEW);
        tickUntilDone(again);
        assertInstanceOf(FileAlreadyExistsException.class, again.getException());
    }

    @Test
    void openExistingOnAMissingNameFails() throws Exception {
        TickCompletableFuture<FileIO> open = fileSystem().open("missing", OpenMode.OPEN_EXISTING);
        tickUntilDone(open);
        assertInstanceOf(NoSuchFileException.class, open.getException());
    }

    @Test
    void openOrCreateCreatesOnceAndThenOpens() throws Exception {
        FileIO first = await(fileSystem().open("a", OpenMode.OPEN_OR_CREATE));
        await(first.write(ByteBuffer.wrap("x".getBytes()), 0));
        FileIO second = await(fileSystem().open("a", OpenMode.OPEN_OR_CREATE));
        assertEquals(1L, await(second.size()));
    }

    @Test
    void listShowsCreatedNamesInOrderAndNotDeletedOnes() throws Exception {
        await(fileSystem().open("b", OpenMode.CREATE_NEW));
        await(fileSystem().open("a", OpenMode.CREATE_NEW));
        await(fileSystem().open("c", OpenMode.CREATE_NEW));
        await(fileSystem().delete("b"));

        assertEquals(List.of("a", "c"), await(fileSystem().list()));
        assertTrue(await(fileSystem().exists("a")));
        assertFalse(await(fileSystem().exists("b")));
    }

    @Test
    void renameMovesTheNameAndKeepsTheBytes() throws Exception {
        FileIO file = await(fileSystem().open("old", OpenMode.CREATE_NEW));
        await(file.write(ByteBuffer.wrap("data".getBytes()), 0));
        await(fileSystem().rename("old", "new"));

        assertEquals(List.of("new"), await(fileSystem().list()));
        FileIO renamed = await(fileSystem().open("new", OpenMode.OPEN_EXISTING));
        assertEquals(4L, await(renamed.size()));
    }

    @Test
    void renameReplacesAnExistingTarget() throws Exception {
        FileIO source = await(fileSystem().open("source", OpenMode.CREATE_NEW));
        await(source.write(ByteBuffer.wrap("source".getBytes()), 0));
        await(fileSystem().open("target", OpenMode.CREATE_NEW));
        await(fileSystem().rename("source", "target"));

        assertEquals(List.of("target"), await(fileSystem().list()));
        assertEquals(6L, await(await(fileSystem().open("target", OpenMode.OPEN_EXISTING)).size()));
    }

    @Test
    void deleteAndRenameOfAMissingNameFail() throws Exception {
        TickCompletableFuture<Void> delete = fileSystem().delete("missing");
        TickCompletableFuture<Void> rename = fileSystem().rename("missing", "other");
        tickUntilDone(delete, rename);
        assertInstanceOf(NoSuchFileException.class, delete.getException());
        assertInstanceOf(NoSuchFileException.class, rename.getException());
    }

    @Test
    void syncDirectoryCompletes() throws Exception {
        await(fileSystem().open("a", OpenMode.CREATE_NEW));
        await(fileSystem().syncDirectory());
    }
}
