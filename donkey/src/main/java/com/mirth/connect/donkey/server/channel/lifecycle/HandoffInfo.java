/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Immutable target and discriminator for one asynchronous transfer. */
public final class HandoffInfo {
    private final HandoffKind kind;
    private final MessageInfo message;
    private final Integer chainId;
    private final Integer nextSendAttempt;

    public HandoffInfo(HandoffKind kind, MessageInfo message, Integer chainId,
            Integer nextSendAttempt) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.message = Objects.requireNonNull(message, "message");
        switch (kind) {
            case SOURCE_QUEUE:
                requireAbsent(chainId, nextSendAttempt, "source-queue");
                break;
            case ASYNC_CHAIN:
                if (chainId == null || chainId <= 0 || nextSendAttempt != null) {
                    throw new IllegalArgumentException(
                            "async-chain handoff requires only a positive chainId");
                }
                if (!chainId.equals(message.getChainId())) {
                    throw new IllegalArgumentException("chainId must match message chainId");
                }
                break;
            case DESTINATION_QUEUE:
                if (chainId != null || nextSendAttempt == null || nextSendAttempt <= 0) {
                    throw new IllegalArgumentException(
                            "destination-queue handoff requires only a positive nextSendAttempt");
                }
                break;
            default:
                throw new IllegalArgumentException("unsupported handoff kind");
        }
        this.chainId = chainId;
        this.nextSendAttempt = nextSendAttempt;
    }

    public HandoffKind getKind() {
        return kind;
    }

    public MessageInfo getMessage() {
        return message;
    }

    public Integer getChainId() {
        return chainId;
    }

    public Integer getNextSendAttempt() {
        return nextSendAttempt;
    }

    private static void requireAbsent(Integer chainId, Integer nextSendAttempt, String kind) {
        if (chainId != null || nextSendAttempt != null) {
            throw new IllegalArgumentException(kind + " handoff has no discriminator");
        }
    }
}
