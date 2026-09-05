/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Immutable process-operation input. */
public final class ProcessInfo {
    private final MessageInfo message;
    private final ExecutionMode executionMode;

    public ProcessInfo(MessageInfo message, ExecutionMode executionMode) {
        this.message = Objects.requireNonNull(message, "message");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
    }

    public MessageInfo getMessage() {
        return message;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }
}
