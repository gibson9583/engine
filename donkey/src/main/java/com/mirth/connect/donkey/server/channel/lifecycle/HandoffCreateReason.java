/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed reason copied from the exact transfer boundary that creates a receipt. */
public enum HandoffCreateReason {
    SOURCE_ENQUEUE(HandoffKind.SOURCE_QUEUE),
    ASYNC_CHAIN_SUBMIT(HandoffKind.ASYNC_CHAIN),
    DESTINATION_ENQUEUE(HandoffKind.DESTINATION_QUEUE),
    DESTINATION_RETRY(HandoffKind.DESTINATION_QUEUE),
    DESTINATION_REQUEUE(HandoffKind.DESTINATION_QUEUE),
    DESTINATION_ROTATION(HandoffKind.DESTINATION_QUEUE);

    private final HandoffKind kind;

    HandoffCreateReason(HandoffKind kind) {
        this.kind = kind;
    }

    public HandoffKind getKind() {
        return kind;
    }
}
