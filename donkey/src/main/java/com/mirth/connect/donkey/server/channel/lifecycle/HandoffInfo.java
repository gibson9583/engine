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
    private final HandoffCreateReason createReason;
    private final MessageInfo message;
    private final Integer chainId;
    private final Long nextSendAttempt;

    public HandoffInfo(HandoffKind kind, MessageInfo message, Integer chainId,
            Long nextSendAttempt) {
        this(kind, message, chainId, nextSendAttempt, initialReason(kind));
    }

    public HandoffInfo(HandoffKind kind, MessageInfo message, Integer chainId,
            Long nextSendAttempt, HandoffCreateReason createReason) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.createReason = Objects.requireNonNull(createReason, "createReason");
        if (createReason.getKind() != kind) {
            throw new IllegalArgumentException("createReason must match handoff kind");
        }
        this.message = Objects.requireNonNull(message, "message");
        switch (kind) {
            case SOURCE_QUEUE:
                requireAbsent(chainId, nextSendAttempt, "source-queue");
                if (message.getMetaDataId() != 0 || message.getChainId() != null) {
                    throw new IllegalArgumentException(
                            "source-queue handoff must target source metadata with no chainId");
                }
                break;
            case ASYNC_CHAIN:
                if (chainId == null || chainId <= 0 || nextSendAttempt != null) {
                    throw new IllegalArgumentException(
                            "async-chain handoff requires only a positive chainId");
                }
                if (!chainId.equals(message.getChainId())) {
                    throw new IllegalArgumentException("chainId must match message chainId");
                }
                if (message.getMetaDataId() == 0) {
                    throw new IllegalArgumentException(
                            "async-chain handoff must target destination metadata");
                }
                break;
            case DESTINATION_QUEUE:
                if (chainId != null || nextSendAttempt == null || nextSendAttempt <= 0) {
                    throw new IllegalArgumentException(
                            "destination-queue handoff requires only a positive nextSendAttempt");
                }
                if (message.getMetaDataId() == 0 || message.getChainId() == null) {
                    throw new IllegalArgumentException(
                            "destination-queue handoff must target destination metadata and chain");
                }
                requireNextAttempt(message, nextSendAttempt);
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

    public HandoffCreateReason getCreateReason() {
        return createReason;
    }

    /** The compatibility constructor describes initial enqueue/submission only. */
    private static HandoffCreateReason initialReason(HandoffKind kind) {
        switch (Objects.requireNonNull(kind, "kind")) {
            case SOURCE_QUEUE: return HandoffCreateReason.SOURCE_ENQUEUE;
            case ASYNC_CHAIN: return HandoffCreateReason.ASYNC_CHAIN_SUBMIT;
            case DESTINATION_QUEUE: return HandoffCreateReason.DESTINATION_ENQUEUE;
            default: throw new IllegalArgumentException("unsupported handoff kind");
        }
    }

    public MessageInfo getMessage() {
        return message;
    }

    public Integer getChainId() {
        return chainId;
    }

    public Long getNextSendAttempt() {
        return nextSendAttempt;
    }

    private static void requireAbsent(Integer chainId, Long nextSendAttempt, String kind) {
        if (chainId != null || nextSendAttempt != null) {
            throw new IllegalArgumentException(kind + " handoff has no discriminator");
        }
    }

    private static void requireNextAttempt(MessageInfo message, long nextSendAttempt) {
        if (nextSendAttempt != (long) message.getSendAttempts() + 1L) {
            throw new IllegalArgumentException(
                    "nextSendAttempt must equal completed sendAttempts plus one");
        }
    }
}
