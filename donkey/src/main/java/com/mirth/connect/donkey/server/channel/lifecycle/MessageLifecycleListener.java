/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/**
 * Observes the message lifecycle without exposing message content or mutable engine objects.
 * Implementations are privileged in-process code and must be thread-safe and non-blocking.
 */
public interface MessageLifecycleListener {
    default LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
        return LifecycleHandle.NOOP;
    }

    default void onSourceMessageCreated(MessageInfo source) {}

    default LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
        return LifecycleHandle.NOOP;
    }

    default LifecycleHandle onFilterTransformerStart(MessageInfo message) {
        return LifecycleHandle.NOOP;
    }

    default LifecycleHandle onDestinationChainStart(ChainInfo chain, HandoffReceipt receipt) {
        return LifecycleHandle.NOOP;
    }

    default LifecycleHandle onDestinationQueueStart(QueueInfo queue, HandoffReceipt receipt) {
        return LifecycleHandle.NOOP;
    }

    default LifecycleHandle onSendStart(SendInfo send) {
        return LifecycleHandle.NOOP;
    }

    default HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
        return HandoffReceipt.NOOP;
    }

    default void onHandoffCancelled(HandoffCancellation cancellation, HandoffReceipt receipt) {}

    default void onHandoffsAbandoned(HandoffAbandonReason reason) {}

    default void onStatusChanged(StatusChangeInfo change) {}
}
