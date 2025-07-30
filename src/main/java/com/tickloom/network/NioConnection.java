package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.messaging.Message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;

public class NioConnection {
    private static final int MAX_FRAMES_PER_READ = 64;
    private static final int MAX_PENDING_WRITES = 1000;


    private final PeerType peerType;
    private ProcessId sourceId;
    private ProcessId destinationId;

    private final NioNetwork nioNetwork;
    private final SocketChannel channel;
    private final SelectionKey channelKey;
    private final FrameReader frameReader;
    private final Queue<ByteBuffer> pendingWrites;
    private final MessageCodec codec;


    public NioConnection(NioNetwork nioNetwork, SocketChannel acceptedChannel, SelectionKey channelKey, MessageCodec codec) {
        this.nioNetwork = nioNetwork;
        this.channel = acceptedChannel;
        this.channelKey = channelKey;
        this.codec = codec;
        this.peerType = PeerType.UNKNOWN;
        this.frameReader = new FrameReader();
        this.pendingWrites = new ArrayDeque<>(MAX_PENDING_WRITES);
        //TODO: Beyond MAX_PENDING_WRITES, start dropping writes
    }

    public InetAddressAndPort getRemoteAddress() {
        try {
            return InetAddressAndPort.from((InetSocketAddress) channel.getRemoteAddress());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public boolean isConnected() {
        return channel != null && channel.isOpen() && channel.isConnected();
    }

    private void addPendingWrite(ByteBuffer buffer) {
        pendingWrites.add(buffer);
        toggleWriteInterest();
    }


    public void read() throws IOException {
        System.out.println("NIO: read() called on channel: " + channel);
        int framesProcessed = 0;
        int messagesDecoded = 0;

        while (framesProcessed < MAX_FRAMES_PER_READ) {
            ReadResult result = frameReader.readFrom(channel);
            System.out.println("NIO: ReadResult: " + result.status());

            if (!result.isFrameComplete()) {
                System.out.println("NIO: Frame not complete, waiting for more data");
                break; // wait for more data
            }

            Frame frame = frameReader.pollFrame();
            while(frame != null) {
                System.out.println("NIO: Frame complete: " + frame);
                routeMessage(frame);
                framesProcessed++;
                messagesDecoded++;
                frame = frameReader.pollFrame();
            }
        }
        // Update metrics
        if (messagesDecoded > 0) {
            System.out.println("[NIO][handleRead] Decoded " + messagesDecoded + " messages, processed " + framesProcessed + " frames");
        }
    }

    private void routeMessage(Frame frame) {
        Message message = codec.decode(frame.getPayload(), Message.class);
        if (peerType == PeerType.UNKNOWN) {
            this.sourceId = message.source();
            this.destinationId = message.destination();

        }
        nioNetwork.handleMessage(message, this);
        //TODO: route to handlers in the messagebus.

    }

    /**
     * Comprehensive connection cleanup to prevent resource leaks.
     * This follows production patterns for proper connection lifecycle management.
     */
    public void cleanupConnection() {
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




    public void write() throws IOException {
        System.out.println("NIO: write() called, pending writes: " + pendingWrites.size());
        writePendingMessages();
    }

    private void writePendingMessages() throws IOException {
        if (pendingWrites.isEmpty()) {
            return;
        }

        // Convert queue to array for bulk write
        ByteBuffer[] buffers = pendingWrites.toArray(new ByteBuffer[0]);
        // Write all buffers in a single call
        long bytesWritten = channel.write(buffers);
        System.out.println("NIO: Wrote " + bytesWritten + " bytes in bulk write");

        // Remove completely written buffers
        pendingWrites.removeIf(buffer -> !buffer.hasRemaining());

        System.out.println("NIO: Remaining buffers in queue: " + pendingWrites.size());

        toggleWriteInterest();


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

    public void setDestinationId(ProcessId destinationId) {
        this.destinationId = destinationId;
    }

    public void send(Message message) {
        System.out.println("NIO: Adding message to pending writes: " + message);
        ByteBuffer buffer = createFrameBuffer(message);
        addPendingWrite(buffer);
        toggleWriteInterest();
    }

    /**
     * Creates a ByteBuffer containing the framed message data.
     *
     * @param message The message to frame
     * @return ByteBuffer ready for writing to channel
     */
    private ByteBuffer createFrameBuffer(Message message) {
        byte[] messageData = codec.encode(message);
        Frame frame = new Frame(0, (byte) 0, ByteBuffer.wrap(messageData));
        return Frame.toByteBuffer(frame);
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

    public void handleConnect() throws IOException {
        System.out.println("NIO: handleConnect called for channel: " + channel);

        if (!channel.finishConnect()) {
            System.out.println("NIO: Connection still pending, will retry later");
            return;
        }

        System.out.println("NIO: Connection established successfully");
        // Connection established, switch to read/write mode
        channelKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }
}
