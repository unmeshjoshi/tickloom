package com.tickstep.network;

import java.nio.channels.SelectionKey;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SelectionKeyBuilder {

    private boolean isReadable = false;
    private boolean isWritable = false;
    private boolean isAcceptable = false;
    private NioConnection attachment;

    public SelectionKeyBuilder() {
        // Private constructor to force use of the factory method
    }

    public static SelectionKeyBuilder aKey() {
        return new SelectionKeyBuilder();
    }

    public SelectionKeyBuilder thatIsReadable() {
        this.isReadable = true;
        return this;
    }

    public SelectionKeyBuilder thatIsWritable() {
        this.isWritable = true;
        return this;
    }

    public SelectionKeyBuilder withAttachment(NioConnection connection) {
        this.attachment = connection;
        return this;
    }

    public SelectionKey build() {
        SelectionKey mockKey = mock(SelectionKey.class);

        // Apply all configured properties
        when(mockKey.isReadable()).thenReturn(this.isReadable);
        when(mockKey.isWritable()).thenReturn(this.isWritable);
        when(mockKey.isAcceptable()).thenReturn(this.isAcceptable);
        
        // Use the provided attachment, or create a default mock if none was given
        when(mockKey.attachment()).thenReturn(
            this.attachment != null ? this.attachment : mock(NioConnection.class)
        );

        return mockKey;
    }
}