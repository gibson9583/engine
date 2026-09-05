/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Dedicated Channel Writer lineage; it is never reconstructed from a message map. */
public final class MessageLineage {
    private final String sourceChannelId;
    private final long sourceMessageId;

    public MessageLineage(String sourceChannelId, long sourceMessageId) {
        this.sourceChannelId = Objects.requireNonNull(sourceChannelId, "sourceChannelId");
        this.sourceMessageId = sourceMessageId;
    }

    public String getSourceChannelId() {
        return sourceChannelId;
    }

    public long getSourceMessageId() {
        return sourceMessageId;
    }
}
