/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed callback kinds used for listener health accounting. */
public enum LifecycleCallbackKind {
    DISPATCH_START,
    SOURCE_MESSAGE_CREATED,
    PROCESS_START,
    FILTER_TRANSFORMER_START,
    DESTINATION_CHAIN_START,
    DESTINATION_QUEUE_START,
    SEND_START,
    HANDOFF_CREATED,
    HANDOFF_CANCELLED,
    HANDOFFS_ABANDONED,
    STATUS_CHANGED,
    HANDLE_END
}
