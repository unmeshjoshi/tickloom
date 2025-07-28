package com.tickstep.network;

import com.tickstep.ProcessId;
import com.tickstep.messaging.Message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;

public class NioConnection {
    private static final int MAX_FRAMES_PER_READ = 64;
    private static final int MAX_PENDING_WRITES = 1000;


    private final PeerType peerType;
    private ProcessId sourceId            ;
    private ProcessId destinationId;

    private final NioNetwork nioNetwork;
    private final SocketChannel channel;
    private final SelectionKey channelKey;
    private final ReadFrame readFrame;
    private final Queue<WriteFrame> pendingWrites;
    private MessageCodec codec;


    public NioConnection(NioNetwork nioNetwork, SocketChannel acceptedChannel, SelectionKey channelKey, MessageCodec codec) {
        this.nioNetwork = nioNetwork;
        this.channel = acceptedChannel;
        this.channelKey = channelKey;
        this.codec = codec;
        this.peerType = PeerType.UNKNOWN;
        this.readFrame = new ReadFrame();
        this.pendingWrites = new ArrayDeque<>(MAX_PENDING_WRITES);
        //TODO: Beyond MAX_PENDING_WRITES, start dropping writes
    }

    public InetAddressAndPort getRemoteAddress()  {
        try {
            return InetAddressAndPort.from((InetSocketAddress) channel.getRemoteAddress());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public SocketChannel getChannel() { return channel; }
    public boolean isConnected() { return channel != null && channel.isOpen() && channel.isConnected(); }

    private void addPendingWrite(WriteFrame writeFrame) {
        pendingWrites.add(writeFrame);
        toggleWriteInterest();
    }


    public void read() {
        System.out.println("NIO: read() called on channel: " + channel);
        int framesProcessed = 0;
        int messagesDecoded = 0;

        try {
            while (framesProcessed < MAX_FRAMES_PER_READ) {
                ReadResult result = readFrame.readFrom(channel);
                System.out.println("NIO: ReadResult: " + result.status());

                if (result.isConnectionClosed()) {
                    handleConnectionClosed();
                    return;
                }

                if (result.hasFramingError()) {
                    handleFramingError(channel, result.error(), readFrame);
                    return; //  Do not continue on framing errors
                }

                if (!result.isFrameComplete()) {
                    System.out.println("NIO: Frame not complete, waiting for more data");
                    break; // wait for more data
                }

                ReadFrame.Frame frame = readFrame.complete();
                System.out.println("NIO: Frame complete: " + frame);
                routeMessage(frame);

                framesProcessed++;
                messagesDecoded++;
            }
        } catch (Exception e) {
            handleUnexpectedReadError(e);
        }

        // Update metrics
        if (messagesDecoded > 0) {
            System.out.println("[NIO][handleRead] Decoded " + messagesDecoded + " messages, processed " + framesProcessed + " frames");
        }
    }

    private void routeMessage(ReadFrame.Frame frame) {
        Message message = codec.decode(frame.getPayload().array(), Message.class);
        if (peerType != PeerType.UNKNOWN) {
            this.sourceId = message.source();
            this.destinationId = message.destination();

        }
        nioNetwork.handleMessage(message, this);
        //TODO: route to handlers in the messagebus.

    }


    private void handleConnectionClosed() {
        System.out.println("[NIO][handleRead] Channel closed by peer: " + channel);
        cleanupConnection();
    }

    /**
     * Comprehensive connection cleanup to prevent resource leaks.
     * This follows production patterns for proper connection lifecycle management.
     */
    private void cleanupConnection() {
        try {
            // Cancel any selection key (this will also detach the ChannelState)
            if (channelKey != null) {
                NioConnection channelState = (NioConnection) channelKey.attachment();
                channelKey.attach(null); // Clear the attachment
                channelKey.cancel();

            }
            channel.close();
            System.out.println("NIO: Connection cleanup completed for channel");
            nioNetwork.removeConnection(this);
        } catch (IOException e) {
            System.err.println("NIO: Error during connection cleanup: " + e.getMessage());
        }
    }


    private void handleFramingError(SocketChannel channel, Throwable error, ReadFrame frame) {
        System.out.println("[NIO][framing] Invalid frame from " + getRemoteAddress() + ": " + error.getMessage() + " (resetting ReadFrame)");
        frame.reset();
    }

    private void handleUnexpectedReadError(Exception e) {
        System.err.println("[NIO][handleRead] Unexpected error on " + getRemoteAddress() + ": " + e.getMessage());
        e.printStackTrace();
    }

    public void write() {
        System.out.println("NIO: write() called, pending writes: " + pendingWrites.size());
        writePendingMessages();
    }

    private void writePendingMessages() {
        if (pendingWrites.isEmpty()) {
            return;
        }
        
        try {
            WriteFrame currentFrame = pendingWrites.peek();
            if (currentFrame == null) {
                return;
            }
            
            int bytesWritten = currentFrame.write(channel);
            System.out.println("NIO: Wrote " + bytesWritten + " bytes, remaining=" + currentFrame.remaining());
            
            if (!currentFrame.hasRemaining()) {
                // Frame completely written, remove from queue
                pendingWrites.poll();
                System.out.println("NIO: Completed writing frame, remaining in queue: " + pendingWrites.size());
            }

            toggleWriteInterest();

        } catch (IOException e) {
            System.err.println("NIO: Error writing to channel: " + e.getMessage());
            cleanupConnection();
        }
    }

    private void toggleWriteInterest() {
        // If we still have pending writes, make sure we're registered for write events
        if (!pendingWrites.isEmpty() && channelKey != null) {
            channelKey.interestOps(channelKey.interestOps() | SelectionKey.OP_WRITE);
            System.out.println("NIO: Set write interest, pending writes: " + pendingWrites.size() + ", interest ops: " + channelKey.interestOps());
        } else if (pendingWrites.isEmpty() && channelKey != null) {
            // No more writes, remove write interest
            channelKey.interestOps(channelKey.interestOps() & ~SelectionKey.OP_WRITE);
            System.out.println("NIO: Removed write interest, interest ops: " + channelKey.interestOps());
        }
    }

    public ProcessId getSourceId() {
        return sourceId;
    }

    public ProcessId getDestinationId() {
        return destinationId;
    }

    public void send(Message message) {
        System.out.println("NIO: Adding message to pending writes: " + message);
        addPendingWrite(new WriteFrame(codec.encode(message)));
        toggleWriteInterest();
    }

    public boolean hasPendingWrites() {
        return !pendingWrites.isEmpty();
    }

    public void ensureWriteInterest() {
        if (channelKey != null && !pendingWrites.isEmpty()) {
            channelKey.interestOps(channelKey.interestOps() | SelectionKey.OP_WRITE);
            System.out.println("NIO: Restored write interest for connection with pending writes");
        }
    }

    public void handleConnect() {
        System.out.println("NIO: handleConnect called for channel: " + channel);
        try {
            if (!channel.finishConnect()) {
                System.out.println("NIO: Connection still pending, will retry later");
                return;
            }

            System.out.println("NIO: Connection established successfully");
            // Connection established, switch to read/write mode
            channelKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);

        } catch (IOException e) {
            System.err.println("NIO: Connection failed: " + e.getMessage());
            cleanupConnection();
        }
    }
}
