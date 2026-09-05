/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.mirth.connect.donkey.model.message.Status;

/** Structural fast-path checks. Performance ratios belong to the JMH suite, not this test. */
public class MessageLifecycleFastPathTest {
    @Test
    public void emptyRegistryReusesStaticCarriersAndNeverInvokesClock() {
        AtomicInteger clockCalls = new AtomicInteger();
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                () -> {
                    clockCalls.incrementAndGet();
                    return 1L;
                }, 1L, 1L, 1);
        LifecycleDispatchToken token = listeners.captureToken();
        MessageInfo source = sourceMessage();
        MessageInfo destination = destinationMessage();

        assertSame(LifecycleDispatchToken.EMPTY, token);
        assertSame(token, listeners.captureToken());
        assertEquals(0L, listeners.allocateMessageIncarnationId(token));
        assertSame(LifecycleHandle.NOOP, listeners.onDispatchStart(token,
                new DispatchInfo("server", "channel", "Channel", 0, "source", "HTTP",
                        InboundParentState.ABSENT, null, null)));
        listeners.onSourceMessageCreated(token, source);
        assertSame(LifecycleHandle.NOOP, listeners.onProcessStart(token,
                new ProcessInfo(source, ExecutionMode.SYNCHRONOUS)));
        assertSame(LifecycleHandle.NOOP, listeners.onFilterTransformerStart(token, source));
        assertSame(LifecycleHandle.NOOP, listeners.onDestinationChainStart(token,
                new ChainInfo(destination, ExecutionMode.SYNCHRONOUS)));
        assertSame(LifecycleHandle.NOOP, listeners.onDestinationQueueStart(token,
                new QueueInfo(destination, ExecutionMode.DESTINATION_QUEUE, 1L)));
        assertSame(LifecycleHandle.NOOP, listeners.onSendStart(token,
                new SendInfo(destination, ExecutionMode.SYNCHRONOUS, 1)));
        listeners.onStatusChanged(token,
                new StatusChangeInfo(destination, Status.QUEUED, Status.RECEIVED, null));
        assertSame(HandoffBundle.EMPTY, listeners.createHandoffs(token,
                new HandoffInfo(HandoffKind.SOURCE_QUEUE, source, null, null)));
        assertEquals(0, clockCalls.get());
    }

    @Test
    public void emptyHandoffConsumptionAndCancellationRemainStaticNoOps() {
        AtomicInteger clockCalls = new AtomicInteger();
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                () -> {
                    clockCalls.incrementAndGet();
                    return 1L;
                }, 1L, 1L, 1);

        assertSame(LifecycleHandle.NOOP, listeners.onProcessStart(
                new ProcessInfo(sourceMessage(), ExecutionMode.SOURCE_QUEUE),
                HandoffBundle.EMPTY));
        HandoffCancellationBatch batch = listeners.claimHandoffsForCancellation(
                HandoffBundle.EMPTY, HandoffCancellationReason.CANCELLED);
        assertSame(HandoffCancellationBatch.EMPTY, batch);
        listeners.deliverHandoffCancellations(batch);
        listeners.cancelHandoffs(HandoffBundle.EMPTY,
                HandoffCancellationReason.CANCELLED);

        assertTrue(batch.isEmpty());
        assertEquals(0, clockCalls.get());
    }

    private static MessageInfo sourceMessage() {
        return new MessageInfo("server", "channel", "Channel", 7L, 0, "source", "HTTP",
                1L, null, Status.RECEIVED, 0, 100L, null, null);
    }

    private static MessageInfo destinationMessage() {
        return new MessageInfo("server", "channel", "Channel", 7L, 1, "destination",
                "HTTP Sender", 1L, 1, Status.RECEIVED, 0, 100L, null, null);
    }
}
