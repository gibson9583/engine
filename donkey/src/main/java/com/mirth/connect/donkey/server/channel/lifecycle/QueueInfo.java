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
    private final int nextSendAttempt;

    public QueueInfo(MessageInfo message, ExecutionMode executionMode, int nextSendAttempt) {
        this.message = Objects.requireNonNull(message, "message");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        if (nextSendAttempt <= 0) {
            throw new IllegalArgumentException("nextSendAttempt must be positive");
        }
        this.nextSendAttempt = nextSendAttempt;
    }

    public MessageInfo getMessage() {
        return message;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public int getNextSendAttempt() {
        return nextSendAttempt;
    }
}
