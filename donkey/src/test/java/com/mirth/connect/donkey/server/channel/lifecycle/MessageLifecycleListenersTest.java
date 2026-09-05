/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Test;

import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;

public class MessageLifecycleListenersTest {
    @After
    public void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    public void donkeyOwnsOneRegistryAndEmptyPathUsesStaticNoOps() {
        assertSame(Donkey.getInstance().getMessageLifecycleListeners(),
                Donkey.getInstance().getMessageLifecycleListeners());
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        LifecycleDispatchToken first = listeners.captureToken();
        LifecycleDispatchToken second = listeners.captureToken();

        assertSame(first, second);
        assertTrue(first.isEmpty());
        assertSame(LifecycleHandle.NOOP, listeners.onDispatchStart(first, dispatch()));
        assertSame(HandoffBundle.EMPTY,
                listeners.createHandoffs(first, sourceHandoff(sourceMessage(0))));
        assertEquals(0, listeners.getRegisteredListenerCount());
    }

    @Test
    public void capturedTokenPreservesOrderAndNeverBackfills() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        RecordingListener first = new RecordingListener("first", calls);
        RecordingListener second = new RecordingListener("second", calls);
        LifecycleListenerRegistration firstRegistration = listeners.register(first);
        LifecycleDispatchToken firstOnly = listeners.captureToken();
        listeners.register(second);

        listeners.onSourceMessageCreated(firstOnly, sourceMessage(0));
        assertEquals(Arrays.asList("first:source"), calls);
        calls.clear();
        listeners.onSourceMessageCreated(listeners.captureToken(), sourceMessage(0));
        assertEquals(Arrays.asList("first:source", "second:source"), calls);

        try {
            listeners.register(first);
            fail("expected duplicate identity rejection");
        } catch (IllegalStateException expected) {
            assertEquals(2, listeners.getRegisteredListenerCount());
        }

        calls.clear();
        firstRegistration.unregister();
        firstRegistration.unregister();
        listeners.onSourceMessageCreated(firstOnly, sourceMessage(0));
        assertEquals(Arrays.asList("first:abandoned:UNREGISTERED"), calls);
        assertEquals(1, listeners.getRegisteredListenerCount());

        calls.clear();
        listeners.register(first);
        listeners.onSourceMessageCreated(firstOnly, sourceMessage(0));
        assertTrue(calls.isEmpty());
        listeners.onSourceMessageCreated(listeners.captureToken(), sourceMessage(0));
        assertEquals(Arrays.asList("second:source", "first:source"), calls);
    }

    @Test
    public void startsInOrderAndEndsExactlyOnceInReverseAfterUnregister() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        LifecycleListenerRegistration first = listeners.register(
                new RecordingListener("first", calls));
        listeners.register(new RecordingListener("second", calls));
        LifecycleDispatchToken token = listeners.captureToken();

        LifecycleHandle handle = listeners.onDispatchStart(token, dispatch());
        first.unregister();
        calls.clear();
        LifecycleResult result = new LifecycleResult(LifecycleOutcome.SUCCESS, sourceMessage(0),
                null, null);
        handle.end(result);
        handle.end(result);

        assertEquals(Arrays.asList("second:end", "first:end"), calls);
    }

    @Test
    public void nonfatalFailuresAreIsolatedAndInterruptedCauseRestoresFlag() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        listeners.register(new MessageLifecycleListener() {
            @Override
            public LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
                throw new IllegalStateException(new InterruptedException("stop"));
            }
        });
        listeners.register(new RecordingListener("second", calls));
        LifecycleDispatchToken token = listeners.captureToken();

        LifecycleHandle handle = listeners.onDispatchStart(token, dispatch());

        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Arrays.asList("second:start"), calls);
        assertNotSame(LifecycleHandle.NOOP, handle);
    }

    @Test
    public void fatalErrorFromCauseInspectionUnwindsPriorHandlesAndReleasesLease() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        listeners.register(new RecordingListener("first", calls));
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal cause inspection");
        LifecycleListenerRegistration hostile = listeners.register(new MessageLifecycleListener() {
            @Override
            public LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
                throw new HostileCauseException(fatal);
            }
        });
        listeners.register(new RecordingListener("later", calls));

        try {
            listeners.onDispatchStart(listeners.captureToken(), dispatch());
            fail("expected fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(fatal, actual);
        }

        assertEquals(Arrays.asList("first:start", "first:end"), calls);
        assertEquals(0, hostile.getActiveInvocationCount());
    }

    @Test
    public void fatalErrorWrappedAsOrdinaryCauseIsRethrownAndUnwindsPriorHandles() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        listeners.register(new RecordingListener("first", calls));
        TestVirtualMachineError fatal = new TestVirtualMachineError("wrapped fatal");
        LifecycleListenerRegistration wrapped = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
                        throw new IllegalStateException("wrapper", fatal);
                    }
                });
        listeners.register(new RecordingListener("later", calls));

        try {
            listeners.onDispatchStart(listeners.captureToken(), dispatch());
            fail("expected fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(fatal, actual);
        }

        assertEquals(Arrays.asList("first:start", "first:end"), calls);
        assertEquals(0, wrapped.getActiveInvocationCount());
    }

    @Test
    public void interruptIsRestoredBeforeNestedFatalCauseIsRethrown() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        TestVirtualMachineError fatal = new TestVirtualMachineError("nested below interrupt");
        InterruptedException interrupted = new InterruptedException("stop");
        interrupted.initCause(fatal);
        listeners.register(new MessageLifecycleListener() {
            @Override
            public void onSourceMessageCreated(MessageInfo source) {
                throw new IllegalStateException(interrupted);
            }
        });

        try {
            listeners.onSourceMessageCreated(listeners.captureToken(), sourceMessage(0));
            fail("expected fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(fatal, actual);
        }

        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    public void deepAndIndirectlyHostileFatalCausesAreRethrown() {
        MessageLifecycleListeners deepListeners = new MessageLifecycleListeners();
        TestVirtualMachineError deepFatal = new TestVirtualMachineError("deep fatal");
        Throwable deepFailure = deepFatal;
        for (int i = 0; i < 40; i++) {
            deepFailure = new IllegalStateException(deepFailure);
        }
        final Throwable callbackFailure = deepFailure;
        deepListeners.register(new MessageLifecycleListener() {
            @Override
            public void onSourceMessageCreated(MessageInfo source) {
                throw (RuntimeException) callbackFailure;
            }
        });

        try {
            deepListeners.onSourceMessageCreated(deepListeners.captureToken(), sourceMessage(0));
            fail("expected deep fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(deepFatal, actual);
        }

        MessageLifecycleListeners hostileListeners = new MessageLifecycleListeners();
        TestVirtualMachineError hostileFatal = new TestVirtualMachineError("indirect hostile fatal");
        hostileListeners.register(new MessageLifecycleListener() {
            @Override
            public void onSourceMessageCreated(MessageInfo source) {
                throw new IndirectHostileCauseException(hostileFatal);
            }
        });

        try {
            hostileListeners.onSourceMessageCreated(hostileListeners.captureToken(),
                    sourceMessage(0));
            fail("expected indirectly hostile fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(hostileFatal, actual);
        }
    }

    @Test
    public void fatalStartUnwindsPriorHandlesAndDoesNotCallLaterListeners() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        listeners.register(new RecordingListener("first", calls));
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal");
        listeners.register(new MessageLifecycleListener() {
            @Override
            public LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
                calls.add("fatal:start");
                throw fatal;
            }
        });
        listeners.register(new RecordingListener("later", calls));

        try {
            listeners.onDispatchStart(listeners.captureToken(), dispatch());
            fail("expected fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(fatal, actual);
        }
        assertEquals(Arrays.asList("first:start", "fatal:start", "first:end"), calls);
    }

    @Test
    public void fatalEndStillClosesEveryHandleAndRethrowsFirstFatal() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal");
        listeners.register(new RecordingListener("first", calls));
        listeners.register(new RecordingListener("fatal", calls) {
            @Override
            protected LifecycleHandle newHandle() {
                return result -> {
                    calls.add("fatal:end");
                    throw fatal;
                };
            }
        });
        listeners.register(new RecordingListener("last", calls));
        LifecycleHandle handle = listeners.onDispatchStart(listeners.captureToken(), dispatch());
        calls.clear();

        try {
            handle.end(new LifecycleResult(LifecycleOutcome.ERROR, null, null,
                    new FailureInfo(FailureCategory.UNKNOWN, fatal.getClass().getName())));
            fail("expected fatal error");
        } catch (TestVirtualMachineError actual) {
            assertSame(fatal, actual);
        }
        assertEquals(Arrays.asList("last:end", "fatal:end", "first:end"), calls);
    }

    @Test
    public void consecutiveFailuresQuarantineFutureCallsAndExposeHealth() {
        AtomicLong clock = new AtomicLong();
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                () -> clock.getAndAdd(20), 10, 60, 3);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger abandoned = new AtomicInteger();
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public void onSourceMessageCreated(MessageInfo source) {
                        calls.incrementAndGet();
                        throw new IllegalStateException("failure");
                    }

                    @Override
                    public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                        assertSame(HandoffAbandonReason.QUARANTINED, reason);
                        abandoned.incrementAndGet();
                    }
                });
        LifecycleDispatchToken token = listeners.captureToken();

        listeners.onSourceMessageCreated(token, sourceMessage(0));
        listeners.onSourceMessageCreated(token, sourceMessage(0));
        listeners.onSourceMessageCreated(token, sourceMessage(0));
        listeners.onSourceMessageCreated(token, sourceMessage(0));

        assertEquals(3, calls.get());
        assertEquals(1, abandoned.get());
        assertTrue(registration.isQuarantined());
        assertFalse(registration.isRegistered());
        assertEquals(0, listeners.getRegisteredListenerCount());
        assertEquals(3, registration.getConsecutiveFailureCount());
        LifecycleCallbackHealth health = registration.getCallbackHealth(
                LifecycleCallbackKind.SOURCE_MESSAGE_CREATED);
        assertEquals(3, health.getInvocationCount());
        assertEquals(3, health.getFailureCount());
        assertEquals(3, health.getSlowInvocationCount());
        assertEquals(20, health.getMaximumDurationNanos());
    }

    @Test
    public void successfulCallResetsConsecutiveFailureThreshold() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                System::nanoTime, Long.MAX_VALUE, Long.MAX_VALUE, 3);
        AtomicInteger calls = new AtomicInteger();
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public void onSourceMessageCreated(MessageInfo source) {
                        int call = calls.incrementAndGet();
                        if (call != 3) {
                            throw new IllegalStateException("failure");
                        }
                    }
                });
        LifecycleDispatchToken token = listeners.captureToken();

        for (int i = 0; i < 5; i++) {
            listeners.onSourceMessageCreated(token, sourceMessage(0));
        }

        assertTrue(registration.isRegistered());
        assertEquals(2, registration.getConsecutiveFailureCount());
    }

    @Test(timeout = 5000)
    public void unregisterDoesNotWaitForAlreadyLeasedCallback() throws Exception {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger abandoned = new AtomicInteger();
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public void onSourceMessageCreated(MessageInfo source) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        completed.incrementAndGet();
                    }

                    @Override
                    public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                        assertEquals(0, completed.get());
                        abandoned.incrementAndGet();
                    }
                });
        LifecycleDispatchToken token = listeners.captureToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> callback = executor.submit(
                    () -> listeners.onSourceMessageCreated(token, sourceMessage(0)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, registration.getActiveInvocationCount());

            registration.unregister();

            assertEquals(1, abandoned.get());
            assertEquals(1, registration.getActiveInvocationCount());
            release.countDown();
            callback.get(2, TimeUnit.SECONDS);
            assertEquals(0, registration.getActiveInvocationCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void sameListenerCannotReregisterUntilOldAbandonmentCompletes() throws Exception {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        CountDownLatch abandoning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MessageLifecycleListener listener = new MessageLifecycleListener() {
            @Override
            public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                abandoning.countDown();
                await(release);
            }
        };
        LifecycleListenerRegistration registration = listeners.register(listener);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> unregister = executor.submit(registration::unregister);
            assertTrue(abandoning.await(2, TimeUnit.SECONDS));

            try {
                listeners.register(listener);
                fail("expected registration rejection during abandonment");
            } catch (IllegalStateException expected) {
                assertEquals(0, listeners.getRegisteredListenerCount());
            }

            release.countDown();
            unregister.get(2, TimeUnit.SECONDS);
            assertTrue(listeners.register(listener).isRegistered());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void lateHandoffPublicationAfterUnregisterIsCancelledExactlyOnce() throws Exception {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean parentPublished = new AtomicBoolean();
        AtomicInteger abandoned = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        Receipt receipt = new Receipt("late");
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                        entered.countDown();
                        await(release);
                        parentPublished.set(true);
                        return receipt;
                    }

                    @Override
                    public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                        assertSame(HandoffAbandonReason.UNREGISTERED, reason);
                        parentPublished.set(false);
                        abandoned.incrementAndGet();
                    }

                    @Override
                    public void onHandoffCancelled(HandoffCancellation cancellation,
                            HandoffReceipt actual) {
                        assertSame(HandoffCancellationReason.TRANSFER_FAILED,
                                cancellation.getReason());
                        assertSame(receipt, actual);
                        parentPublished.set(false);
                        cancelled.incrementAndGet();
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HandoffBundle> creation = executor.submit(() -> listeners.createHandoffs(
                    listeners.captureToken(), sourceHandoff(sourceMessage(0))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            registration.unregister();
            release.countDown();
            HandoffBundle bundle = creation.get(2, TimeUnit.SECONDS);

            assertTrue(bundle.isEmpty());
            assertFalse(parentPublished.get());
            assertEquals(1, abandoned.get());
            assertEquals(1, cancelled.get());
            assertEquals(0, registration.getActiveInvocationCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void lateHandoffPublicationAfterQuarantineIsCancelledExactlyOnce() throws Exception {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                System::nanoTime, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean parentPublished = new AtomicBoolean();
        AtomicInteger abandoned = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        Receipt receipt = new Receipt("late-quarantine");
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                        entered.countDown();
                        await(release);
                        parentPublished.set(true);
                        return receipt;
                    }

                    @Override
                    public void onSourceMessageCreated(MessageInfo source) {
                        throw new IllegalStateException("quarantine");
                    }

                    @Override
                    public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                        assertSame(HandoffAbandonReason.QUARANTINED, reason);
                        parentPublished.set(false);
                        abandoned.incrementAndGet();
                    }

                    @Override
                    public void onHandoffCancelled(HandoffCancellation cancellation,
                            HandoffReceipt actual) {
                        assertSame(receipt, actual);
                        parentPublished.set(false);
                        cancelled.incrementAndGet();
                    }
                });
        LifecycleDispatchToken token = listeners.captureToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HandoffBundle> creation = executor.submit(() -> listeners.createHandoffs(token,
                    sourceHandoff(sourceMessage(0))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            listeners.onSourceMessageCreated(token, sourceMessage(0));
            release.countDown();
            HandoffBundle bundle = creation.get(2, TimeUnit.SECONDS);

            assertTrue(bundle.isEmpty());
            assertFalse(parentPublished.get());
            assertEquals(1, abandoned.get());
            assertEquals(1, cancelled.get());
            assertTrue(registration.isQuarantined());
            assertEquals(0, registration.getActiveInvocationCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void fatalAbandonmentReleasesLeaseAndRemainsUnsuppressed() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                System::nanoTime, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        TestVirtualMachineError fatal = new TestVirtualMachineError("abandonment");
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public void onSourceMessageCreated(MessageInfo source) {
                        throw new IllegalStateException("ordinary failure");
                    }

                    @Override
                    public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                        throw fatal;
                    }
                });

        try {
            listeners.onSourceMessageCreated(listeners.captureToken(), sourceMessage(0));
            fail("expected fatal abandonment error");
        } catch (TestVirtualMachineError actual) {
            assertSame(fatal, actual);
        }
        assertEquals(0, registration.getActiveInvocationCount());
        assertTrue(registration.isQuarantined());
        assertEquals(1, fatal.getSuppressed().length);
        assertTrue(fatal.getSuppressed()[0] instanceof IllegalStateException);
    }

    @Test
    public void quarantineAbandonmentFatalPrecedesCauseInspectionFatal() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(
                System::nanoTime, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        TestVirtualMachineError inspectionFatal =
                new TestVirtualMachineError("cause inspection");
        TestVirtualMachineError abandonmentFatal =
                new TestVirtualMachineError("abandonment");
        HostileCauseException callbackFailure = new HostileCauseException(inspectionFatal);
        LifecycleListenerRegistration registration = listeners.register(
                new MessageLifecycleListener() {
                    @Override
                    public void onSourceMessageCreated(MessageInfo source) {
                        throw callbackFailure;
                    }

                    @Override
                    public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                        throw abandonmentFatal;
                    }
                });

        try {
            listeners.onSourceMessageCreated(listeners.captureToken(), sourceMessage(0));
            fail("expected fatal abandonment error");
        } catch (TestVirtualMachineError actual) {
            assertSame(abandonmentFatal, actual);
        }

        assertTrue(registration.isQuarantined());
        assertEquals(0, registration.getActiveInvocationCount());
        assertEquals(2, abandonmentFatal.getSuppressed().length);
        assertSame(callbackFailure, abandonmentFatal.getSuppressed()[0]);
        assertSame(inspectionFatal, abandonmentFatal.getSuppressed()[1]);
    }

    @Test
    public void handoffRoutesExactReceiptAndClaimsConsumeOnlyOnce() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        Receipt firstReceipt = new Receipt("first");
        Receipt secondReceipt = new Receipt("second");
        listeners.register(new HandoffListener("first", firstReceipt, calls));
        listeners.register(new HandoffListener("second", secondReceipt, calls));
        LifecycleDispatchToken token = listeners.captureToken();
        HandoffBundle bundle = listeners.createHandoffs(token, sourceHandoff(sourceMessage(0)));

        LifecycleHandle first = listeners.onProcessStart(
                new ProcessInfo(sourceMessage(0), ExecutionMode.SOURCE_QUEUE), bundle);
        LifecycleHandle duplicate = listeners.onProcessStart(
                new ProcessInfo(sourceMessage(0), ExecutionMode.SOURCE_QUEUE), bundle);
        first.end(new LifecycleResult(LifecycleOutcome.SUCCESS, sourceMessage(0), null, null));
        duplicate.end(new LifecycleResult(LifecycleOutcome.SUCCESS, sourceMessage(0), null, null));

        assertEquals(Arrays.asList("first:create", "second:create",
                "first:process:first", "second:process:second",
                "first:process:NOOP", "second:process:NOOP",
                "second:end", "first:end", "second:end", "first:end"), calls);
    }

    @Test
    public void handoffCancellationIsExactOnceAndSkipsRemovedRegistration() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<String> calls = new ArrayList<String>();
        LifecycleListenerRegistration removed = listeners.register(
                new HandoffListener("removed", new Receipt("removed"), calls));
        listeners.register(new HandoffListener("active", new Receipt("active"), calls));
        HandoffBundle bundle = listeners.createHandoffs(listeners.captureToken(),
                sourceHandoff(sourceMessage(0)));
        calls.clear();
        removed.unregister();
        listeners.cancelHandoffs(bundle, HandoffCancellationReason.SHUTDOWN);
        listeners.cancelHandoffs(bundle, HandoffCancellationReason.SHUTDOWN);

        assertEquals(Arrays.asList("removed:abandoned:UNREGISTERED",
                "active:cancel:active:SHUTDOWN"), calls);
    }

    @Test
    public void cancellationClaimInvokesNoListenerUntilIdempotentDelivery() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger cancellations = new AtomicInteger();
        Receipt receipt = new Receipt("deferred");
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return receipt;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt actual) {
                assertSame(receipt, actual);
                cancellations.incrementAndGet();
            }
        });
        HandoffBundle bundle = listeners.createHandoffs(listeners.captureToken(),
                sourceHandoff(sourceMessage(0)));

        HandoffCancellationBatch batch = listeners.claimHandoffsForCancellation(bundle,
                HandoffCancellationReason.REMOVED);

        assertFalse(batch.isEmpty());
        assertEquals(0, cancellations.get());
        listeners.cancelHandoffs(bundle, HandoffCancellationReason.CANCELLED);
        assertEquals(0, cancellations.get());
        listeners.deliverHandoffCancellations(batch);
        listeners.deliverHandoffCancellations(batch);
        assertEquals(1, cancellations.get());
    }

    @Test
    public void nullAndThrowingHandoffCreationBecomeNoopWithoutBlockingSibling() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        List<HandoffReceipt> received = new ArrayList<HandoffReceipt>();
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return null;
            }

            @Override
            public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
                received.add(receipt);
                return LifecycleHandle.NOOP;
            }
        });
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                throw new IllegalStateException("failure");
            }

            @Override
            public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
                received.add(receipt);
                return LifecycleHandle.NOOP;
            }
        });
        Receipt finalReceipt = new Receipt("final");
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return finalReceipt;
            }

            @Override
            public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
                received.add(receipt);
                return LifecycleHandle.NOOP;
            }
        });
        HandoffBundle bundle = listeners.createHandoffs(listeners.captureToken(),
                sourceHandoff(sourceMessage(0)));

        listeners.onProcessStart(new ProcessInfo(sourceMessage(0), ExecutionMode.SOURCE_QUEUE),
                bundle);

        assertEquals(3, received.size());
        assertSame(HandoffReceipt.NOOP, received.get(0));
        assertSame(HandoffReceipt.NOOP, received.get(1));
        assertSame(finalReceipt, received.get(2));
    }

    @Test(timeout = 10000)
    public void handoffConsumeCancellationRaceHasOneExactReceiptWinner() throws Exception {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger exactStarts = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        Receipt receipt = new Receipt("race");
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return receipt;
            }

            @Override
            public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt actual) {
                if (actual == receipt) {
                    exactStarts.incrementAndGet();
                }
                return LifecycleHandle.NOOP;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt actual) {
                assertSame(receipt, actual);
                cancellations.incrementAndGet();
            }
        });
        LifecycleDispatchToken token = listeners.captureToken();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 1; i <= 100; i++) {
                HandoffBundle bundle = listeners.createHandoffs(token,
                        sourceHandoff(sourceMessage(0)));
                CountDownLatch start = new CountDownLatch(1);
                Future<?> consume = executor.submit(() -> {
                    await(start);
                    listeners.onProcessStart(
                            new ProcessInfo(sourceMessage(0), ExecutionMode.SOURCE_QUEUE), bundle);
                });
                Future<?> cancel = executor.submit(() -> {
                    await(start);
                    listeners.cancelHandoffs(bundle, HandoffCancellationReason.CANCELLED);
                });
                start.countDown();
                consume.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);
                assertEquals(i, exactStarts.get() + cancellations.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static DispatchInfo dispatch() {
        return new DispatchInfo("server", "channel", "Channel", 0, "source", "HTTP Listener",
                InboundParentState.ABSENT, null, null);
    }

    private static MessageInfo sourceMessage(int sendAttempts) {
        return new MessageInfo("server", "channel", "Channel", 7, 0, "source",
                "HTTP Listener", 11, null, Status.RECEIVED, sendAttempts, 100L, null, null);
    }

    private static HandoffInfo sourceHandoff(MessageInfo message) {
        return new HandoffInfo(HandoffKind.SOURCE_QUEUE, message, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class RecordingListener implements MessageLifecycleListener {
        protected final String name;
        protected final List<String> calls;

        private RecordingListener(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public LifecycleHandle onDispatchStart(DispatchInfo dispatch) {
            calls.add(name + ":start");
            return newHandle();
        }

        @Override
        public void onSourceMessageCreated(MessageInfo source) {
            calls.add(name + ":source");
        }

        @Override
        public void onHandoffsAbandoned(HandoffAbandonReason reason) {
            calls.add(name + ":abandoned:" + reason);
        }

        protected LifecycleHandle newHandle() {
            return result -> calls.add(name + ":end");
        }
    }

    private static final class HandoffListener extends RecordingListener {
        private final HandoffReceipt receipt;

        private HandoffListener(String name, HandoffReceipt receipt, List<String> calls) {
            super(name, calls);
            this.receipt = receipt;
        }

        @Override
        public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
            calls.add(name + ":create");
            return receipt;
        }

        @Override
        public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt actualReceipt) {
            String receiptName = actualReceipt == HandoffReceipt.NOOP ? "NOOP"
                    : ((Receipt) actualReceipt).name;
            calls.add(name + ":process:" + receiptName);
            return newHandle();
        }

        @Override
        public void onHandoffCancelled(HandoffCancellation cancellation,
                HandoffReceipt actualReceipt) {
            calls.add(name + ":cancel:" + ((Receipt) actualReceipt).name + ":"
                    + cancellation.getReason());
        }
    }

    private static final class Receipt implements HandoffReceipt {
        private final String name;

        private Receipt(String name) {
            this.name = name;
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private static final class HostileCauseException extends RuntimeException {
        private final VirtualMachineError fatal;

        private HostileCauseException(VirtualMachineError fatal) {
            this.fatal = fatal;
        }

        @Override
        public synchronized Throwable getCause() {
            throw fatal;
        }
    }

    private static final class IndirectHostileCauseException extends RuntimeException {
        private final VirtualMachineError fatal;

        private IndirectHostileCauseException(VirtualMachineError fatal) {
            this.fatal = fatal;
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("inspection wrapper", fatal);
        }
    }
}
