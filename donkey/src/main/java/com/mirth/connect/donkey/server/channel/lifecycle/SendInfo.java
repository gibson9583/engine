/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** One actual destination send attempt. */
public final class SendInfo {
    private final MessageInfo message;
    private final ExecutionMode executionMode;
    private final int attempt;

    public SendInfo(MessageInfo message, ExecutionMode executionMode, int attempt) {
        this.message = Objects.requireNonNull(message, "message");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        this.attempt = attempt;
    }

    public MessageInfo getMessage() {
        return message;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public int getAttempt() {
        return attempt;
    }
}
