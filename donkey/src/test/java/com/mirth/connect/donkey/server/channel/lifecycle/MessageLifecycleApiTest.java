/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.mirth.connect.donkey.model.message.Status;

public class MessageLifecycleApiTest {
    private static final Class<?>[] VALUE_TYPES = {
            DispatchInfo.class, InboundTraceParent.class, MessageLineage.class, MessageInfo.class,
            ProcessInfo.class, ChainInfo.class, QueueInfo.class, SendInfo.class, FailureInfo.class,
            LifecycleResult.class, StatusChangeInfo.class, HandoffInfo.class,
            HandoffCancellation.class };

    private static final Set<Class<?>> SAFE_SIGNATURE_TYPES = new HashSet<Class<?>>(Arrays.asList(
            void.class, boolean.class, int.class, long.class, String.class, Integer.class,
            Long.class, Status.class, MessageLifecycleListener.class, LifecycleHandle.class,
            HandoffReceipt.class, DispatchInfo.class, InboundParentState.class,
            InboundTraceParent.class, MessageLineage.class, MessageInfo.class, ProcessInfo.class,
            ChainInfo.class, QueueInfo.class, SendInfo.class, ExecutionMode.class,
            LifecycleOutcome.class, FailureCategory.class, FailureInfo.class,
            LifecycleResult.class, StatusChangeInfo.class, HandoffKind.class, HandoffInfo.class,
            HandoffCancellationReason.class, HandoffCancellation.class,
            HandoffAbandonReason.class));

    @Test
    public void publicApiUsesOnlyClosedContentFreeTypes() {
        for (Class<?> type : lifecycleTypes()) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    assertSafe(constructor.getParameterTypes());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    // Every Java enum necessarily exposes compiler-generated values() and
                    // valueOf(String). They are language machinery, not lifecycle callbacks.
                    if (type.isEnum()
                            && (method.getName().equals("values")
                                    || method.getName().equals("valueOf"))) {
                        continue;
                    }
                    assertSafe(method.getReturnType());
                    assertSafe(method.getParameterTypes());
                }
            }
        }
    }

    @Test
    public void valueObjectsAreFinalScalarSnapshots() {
        for (Class<?> type : VALUE_TYPES) {
            assertTrue(type.getName(), Modifier.isFinal(type.getModifiers()));
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertTrue(type.getName() + "." + field.getName(),
                            Modifier.isPrivate(field.getModifiers())
                                    && Modifier.isFinal(field.getModifiers()));
                    assertSafe(field.getType());
                }
            }
        }
    }

    @Test
    public void listenerDefaultsAreNoOps() {
        MessageLifecycleListener listener = new MessageLifecycleListener() {};
        MessageInfo message = message(Status.RECEIVED, 1);
        DispatchInfo dispatch = new DispatchInfo("server", "channel", "Channel", 0, "source",
                "HTTP Listener", InboundParentState.ABSENT, null, null);
        HandoffInfo handoff = new HandoffInfo(HandoffKind.SOURCE_QUEUE, message, null, null);

        assertSame(LifecycleHandle.NOOP, listener.onDispatchStart(dispatch));
        listener.onSourceMessageCreated(message);
        assertSame(LifecycleHandle.NOOP,
                listener.onProcessStart(new ProcessInfo(message, ExecutionMode.SYNCHRONOUS),
                        HandoffReceipt.NOOP));
        assertSame(LifecycleHandle.NOOP, listener.onFilterTransformerStart(message));
        assertSame(LifecycleHandle.NOOP,
                listener.onDestinationChainStart(
                        new ChainInfo(message(Status.RECEIVED, 1), ExecutionMode.SYNCHRONOUS),
                        HandoffReceipt.NOOP));
        assertSame(LifecycleHandle.NOOP,
                listener.onDestinationQueueStart(
                        new QueueInfo(message, ExecutionMode.DESTINATION_QUEUE, 1),
                        HandoffReceipt.NOOP));
        assertSame(LifecycleHandle.NOOP,
                listener.onSendStart(new SendInfo(message, ExecutionMode.SYNCHRONOUS, 1)));
        assertSame(HandoffReceipt.NOOP, listener.onHandoffCreated(handoff));
        listener.onHandoffCancelled(
                new HandoffCancellation(handoff, HandoffCancellationReason.CANCELLED),
                HandoffReceipt.NOOP);
        listener.onHandoffsAbandoned(HandoffAbandonReason.UNREGISTERED);
        listener.onStatusChanged(new StatusChangeInfo(message, Status.QUEUED, Status.RECEIVED,
                null));
        LifecycleHandle.NOOP.end(
                new LifecycleResult(LifecycleOutcome.SUCCESS, message, null, null));
    }

    @Test
    public void inboundParentStateCannotBeAmbiguous() {
        InboundTraceParent parent = new InboundTraceParent(
                "00000000000000000000000000000001", "0000000000000001", 255);
        DispatchInfo dispatch = new DispatchInfo("server", "channel", "Channel", 0, "source",
                "HTTP Listener", InboundParentState.VALID, parent,
                new MessageLineage("upstream", 42));

        assertSame(parent, dispatch.getInboundTraceParent());
        assertEquals(255, parent.getTraceFlagsUnsignedByte());
        assertEquals(42, dispatch.getMessageLineage().getSourceMessageId());
        expectIllegalArgument(() -> new DispatchInfo("server", "channel", "Channel", 0,
                "source", "HTTP Listener", InboundParentState.INVALID, parent, null));
        expectIllegalArgument(() -> new DispatchInfo("server", "channel", "Channel", 0,
                "source", "HTTP Listener", InboundParentState.VALID, null, null));
        expectIllegalArgument(() -> new InboundTraceParent(
                "00000000000000000000000000000000", "0000000000000001", 0));
        expectIllegalArgument(() -> new InboundTraceParent(
                "00000000000000000000000000000001", "0000000000000000", 0));
        expectIllegalArgument(() -> new InboundTraceParent(
                "00000000000000000000000000000001", "0000000000000001", 256));
    }

    @Test
    public void statusAndHandoffShapesAreValidated() {
        MessageInfo received = message(Status.RECEIVED, 1);
        MessageInfo error = message(Status.ERROR, 1);
        FailureInfo failure = new FailureInfo(FailureCategory.TRANSFORMER,
                IllegalStateException.class.getName());

        assertNull(new StatusChangeInfo(received, Status.QUEUED, Status.RECEIVED, null)
                .getFailure());
        assertSame(failure,
                new StatusChangeInfo(error, Status.RECEIVED, Status.ERROR, failure).getFailure());
        expectIllegalArgument(
                () -> new StatusChangeInfo(error, Status.RECEIVED, Status.ERROR, null));
        expectIllegalArgument(() -> new StatusChangeInfo(received, Status.QUEUED,
                Status.RECEIVED, failure));
        expectIllegalArgument(() -> new LifecycleResult(LifecycleOutcome.SUCCESS, error,
                Status.ERROR, null));

        HandoffInfo chain = new HandoffInfo(HandoffKind.ASYNC_CHAIN, received, 1, null);
        assertEquals(Integer.valueOf(1), chain.getChainId());
        HandoffInfo queue = new HandoffInfo(HandoffKind.DESTINATION_QUEUE, received, null, 2);
        assertEquals(Integer.valueOf(2), queue.getNextSendAttempt());
        expectIllegalArgument(
                () -> new HandoffInfo(HandoffKind.SOURCE_QUEUE, received, 1, null));
        expectIllegalArgument(
                () -> new HandoffInfo(HandoffKind.ASYNC_CHAIN, received, 2, null));
        expectIllegalArgument(
                () -> new HandoffInfo(HandoffKind.DESTINATION_QUEUE, received, null, 0));
    }

    private static MessageInfo message(Status status, Integer chainId) {
        return new MessageInfo("server", "channel", "Channel", 7, 1, "destination",
                "HTTP Sender", 11, chainId, status, 0, 100L, null, null);
    }

    private static Class<?>[] lifecycleTypes() {
        return new Class<?>[] { MessageLifecycleListener.class, LifecycleHandle.class,
                HandoffReceipt.class, InboundParentState.class, InboundTraceParent.class,
                MessageLineage.class, DispatchInfo.class, MessageInfo.class, ExecutionMode.class,
                ProcessInfo.class, ChainInfo.class, QueueInfo.class, SendInfo.class,
                LifecycleOutcome.class, FailureCategory.class, FailureInfo.class,
                LifecycleResult.class, StatusChangeInfo.class, HandoffKind.class,
                HandoffInfo.class, HandoffCancellationReason.class, HandoffCancellation.class,
                HandoffAbandonReason.class };
    }

    private static void assertSafe(Class<?>... types) {
        for (Class<?> type : types) {
            assertTrue("forbidden lifecycle API type: " + type.getName(),
                    SAFE_SIGNATURE_TYPES.contains(type));
        }
    }

    private static void expectIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
