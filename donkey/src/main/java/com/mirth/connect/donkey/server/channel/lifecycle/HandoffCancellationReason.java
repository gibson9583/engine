/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed reasons why an exact-carrier asynchronous transfer did not start. */
public enum HandoffCancellationReason {
    SUBMISSION_REJECTED,
    TRANSFER_FAILED,
    PERSISTED_ONLY,
    DISPLACED,
    REMOVED,
    INVALIDATED,
    CAPACITY_REDUCED,
    REFILL_DISCARDED,
    INTERRUPTED,
    ROLLED_BACK,
    SHUTDOWN,
    CANCELLED
}
