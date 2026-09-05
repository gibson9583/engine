/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Immutable destination-chain operation input. */
public final class ChainInfo {
    private final MessageInfo message;
    private final ExecutionMode executionMode;

    public ChainInfo(MessageInfo message, ExecutionMode executionMode) {
        this.message = Objects.requireNonNull(message, "message");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        if (message.getChainId() == null) {
            throw new IllegalArgumentException("destination-chain message must have a chainId");
        }
    }

    public MessageInfo getMessage() {
        return message;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }
}
