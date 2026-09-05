/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** One destination-queue acquisition or same-thread held retry. */
public final class QueueInfo {
    private final MessageInfo message;
    private final ExecutionMode executionMode;
    private final long nextSendAttempt;

    public QueueInfo(MessageInfo message, ExecutionMode executionMode, long nextSendAttempt) {
        this.message = Objects.requireNonNull(message, "message");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        if (message.getMetaDataId() == 0 || message.getChainId() == null) {
            throw new IllegalArgumentException(
                    "destination-queue message must have destination metadata and a chainId");
        }
        if (nextSendAttempt <= 0) {
            throw new IllegalArgumentException("nextSendAttempt must be positive");
        }
        if (nextSendAttempt != (long) message.getSendAttempts() + 1L) {
            throw new IllegalArgumentException(
                    "nextSendAttempt must equal completed sendAttempts plus one");
        }
        this.nextSendAttempt = nextSendAttempt;
    }

    public MessageInfo getMessage() {
        return message;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public long getNextSendAttempt() {
        return nextSendAttempt;
    }
}
