/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed execution modes that determine synchronous, queued, and recovery trace boundaries. */
public enum ExecutionMode {
    SYNCHRONOUS,
    SOURCE_QUEUE,
    ASYNC_CHAIN,
    DESTINATION_QUEUE,
    PERSISTED_QUEUE_REFILL,
    RECOVERY
}
