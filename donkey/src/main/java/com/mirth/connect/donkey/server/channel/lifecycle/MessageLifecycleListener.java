/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/**
 * Observes the message lifecycle without exposing message content or mutable engine objects.
 *
 * <p>Callbacks execute synchronously on their calling thread. Message-work callbacks use source
 * connector, source queue, channel executor/recovery, and destination queue threads; cancellation
 * and unregistration cleanup can instead use queue-maintenance, plugin lifecycle, or server
 * control threads. The same listener can be called concurrently by any of them. Implementations
 * are privileged in-process code and must be thread-safe, return quickly, avoid blocking I/O, and
 * never call back into engine controllers. Buffering and export must be asynchronous.</p>
 *
 * <p>Some callbacks run while a channel process-lock permit or database transaction is open. A
 * listener failure can therefore delay or roll back real message traffic. The registry isolates
 * nonfatal listener failures, but it cannot safely time out or preempt a hung callback.</p>
 *
 * <p>Every successful start handle is ended on the same engine thread, in reverse listener order,
 * from the engine operation's lexical {@code finally}. A handle may end after its listener has
 * been unregistered or quarantined. Implementations must not assume that separate callbacks for
 * one message use the same thread; asynchronous correlation is represented by
 * {@link HandoffReceipt}, not thread-local state.</p>
 */
public interface MessageLifecycleListener {
    /**
     * Begins accepted raw-message dispatch on the source connector's current thread.
     *
     * <p>This callback runs after the stopped-state guard but before dispatch-thread registration
     * and target-channel process-lock acquisition. Its handle includes target-lock wait and all
     * accepted dispatch work. The handle ends on this thread and may end while the acquired target
     * permit is still held. A synchronous Channel Writer dispatch is already nested in its upstream
     * send and can therefore begin while the upstream channel's process-lock permit and destination
     * DAO transaction are open.</p>
     */
    default LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
        return LifecycleHandle.NOOP;
    }

    /**
     * Reports source-message identity on the current dispatch thread immediately after message-id
     * and lifecycle-carrier initialization. The target channel process lock is held. Notification
     * precedes new-message insertion, overwrite deletion/reset, source-map copying, attachment
     * work, content insertion, connector-message persistence, and message-scoped error events; it
     * is an identity notification rather than a persistence claim.
     */
    default void onSourceMessageCreated(MessageInfo source) {}

    /**
     * Begins source-message processing on the thread that entered the process operation.
     *
     * <p>That can be the synchronous source-dispatch thread, a source-queue thread, or a channel
     * executor/recovery thread. A process-lock permit may be held. The handle covers preprocessing,
     * source transformation, destination chains, postprocessing, early filtered/error returns,
     * interruption, and transaction completion.</p>
     */
    default LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
        return LifecycleHandle.NOOP;
    }

    /**
     * Begins filtering/transformation on its enclosing operation's thread. Depending on the path,
     * this can be a dispatch, source-queue, channel-executor/recovery, or destination-queue thread.
     * Both source and destination transforms can run while the enclosing DAO transaction is open;
     * an inline final-chain destination transform can use the dispatch, source-queue, or recovery
     * thread rather than a chain-executor thread.
     */
    default LifecycleHandle onFilterTransformerStart(MessageInfo message) {
        return LifecycleHandle.NOOP;
    }

    /**
     * Begins a destination chain on either the inline source-processing thread or a channel
     * executor/recovery thread. Nested destination work may execute with an open DAO transaction;
     * the handle ends on the same thread after the chain completes or fails.
     */
    default LifecycleHandle onDestinationChainStart(ChainInfo chain, HandoffReceipt receipt) {
        return LifecycleHandle.NOOP;
    }

    /**
     * Begins one acquired or held destination-queue attempt on its destination queue thread. The
     * handle spans retry delay, transformation, send, commit or rollback, and final queue
     * disposition. DAO work occurs inside the scope, and listener code is never intentionally run
     * while the queue monitor is held.
     */
    default LifecycleHandle onDestinationQueueStart(QueueInfo queue, HandoffReceipt receipt) {
        return LifecycleHandle.NOOP;
    }

    /**
     * Begins one actual connector send on the enclosing process, chain, or destination-queue
     * thread. Destination sends normally run while that operation's DAO transaction is open. The
     * handle includes response validation and ends on the same thread.
     */
    default LifecycleHandle onSendStart(SendInfo send) {
        return LifecycleHandle.NOOP;
    }

    /**
     * Creates listener-owned correlation on the current producer thread before asynchronous
     * submission or attempted queue insertion. Queue handoff callbacks run outside the queue
     * monitor. The eventual consume or cancellation may occur on another thread.
     */
    default HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
        return HandoffReceipt.NOOP;
    }

    /**
     * Cancels an unconsumed handoff on the thread that observes failed or discarded transfer. This
     * need not be the creation thread. Queue cancellation callbacks run only after the queue
     * monitor has been released.
     */
    default void onHandoffCancelled(HandoffCancellation cancellation, HandoffReceipt receipt) {}

    /**
     * Clears all outstanding handoffs for this registration. It runs on the unregistering thread,
     * or on the engine thread whose failed callback triggers quarantine, and may overlap a callback
     * that had already acquired its invocation lease.
     */
    default void onHandoffsAbandoned(HandoffAbandonReason reason) {}

    /**
     * Reports a runtime status transition on the current message-processing thread, immediately
     * after {@code dao.updateStatus} returns and before the surrounding transaction commits. A
     * later rollback does not retract this notification and is reported by the enclosing lifecycle
     * result.
     */
    default void onStatusChanged(StatusChangeInfo change) {}
}
