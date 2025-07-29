package com.tickstep.network;

public final class ReadResult {
    public enum Status {
        FRAME_COMPLETE,
        INCOMPLETE
    }

    private final Status status;
    private final Throwable error;

    private ReadResult(Status status, Throwable error) {
        this.status = status;
        this.error = error;
    }

    public static ReadResult frameComplete() {
        return new ReadResult(Status.FRAME_COMPLETE, null);
    }

    public static ReadResult incomplete() {
        return new ReadResult(Status.INCOMPLETE, null);
    }

    public boolean isFrameComplete() {
        return status == Status.FRAME_COMPLETE;
    }

    public Throwable error() {
        return error;
    }

    public Status status() {
        return status;
    }

    @Override
    public String toString() {
        return "ReadResult{" + status + (error != null ? ", error=" + error : "") + '}';
    }
}
