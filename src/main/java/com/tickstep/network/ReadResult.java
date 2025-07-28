package com.tickstep.network;

public final class ReadResult {
    public enum Status {
        FRAME_COMPLETE,
        INCOMPLETE,
        CONNECTION_CLOSED,
        FRAMING_ERROR
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

    public static ReadResult connectionClosed() {
        return new ReadResult(Status.CONNECTION_CLOSED, null);
    }

    public static ReadResult framingError(Throwable error) {
        return new ReadResult(Status.FRAMING_ERROR, error);
    }

    public boolean isFrameComplete() {
        return status == Status.FRAME_COMPLETE;
    }

    public boolean isConnectionClosed() {
        return status == Status.CONNECTION_CLOSED;
    }

    public boolean hasFramingError() {
        return status == Status.FRAMING_ERROR;
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
