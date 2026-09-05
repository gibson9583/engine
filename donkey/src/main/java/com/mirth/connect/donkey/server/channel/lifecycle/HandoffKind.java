/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed asynchronous transfer kinds. */
public enum HandoffKind {
    SOURCE_QUEUE,
    ASYNC_CHAIN,
    DESTINATION_QUEUE
}
