package com.tickloom.fs;

public enum OpenMode {
    /** Creates the file. Fails with FileAlreadyExistsException if the name is taken. */
    CREATE_NEW,
    /** Opens an existing file. Fails with NoSuchFileException if there is none. */
    OPEN_EXISTING,
    /** Opens the file, creating it first if needed. */
    OPEN_OR_CREATE
}
