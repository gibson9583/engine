/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.donkey.server.channel.lifecycle;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import com.mirth.connect.donkey.model.message.Status;

public class LifecycleEndHealthTest {
    private static final LifecycleResult RESULT = new LifecycleResult(LifecycleOutcome.SUCCESS, null, null, null);
    private static final LifecycleCallbackKind[] ENDS = {
        LifecycleCallbackKind.DISPATCH_END, LifecycleCallbackKind.PROCESS_END,
        LifecycleCallbackKind.FILTER_TRANSFORMER_END, LifecycleCallbackKind.DESTINATION_CHAIN_END,
        LifecycleCallbackKind.DESTINATION_QUEUE_END, LifecycleCallbackKind.SEND_END
    };

    @Test
    public void retainedEndsKeepOperationHealthAfterRemovalAndRepeatedEnd() {
        AtomicLong clock = new AtomicLong();
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(clock::get, 10L, Long.MAX_VALUE, 100);
        LifecycleListenerRegistration registration = listeners.register(new AllOperations(clock, true));
        LifecycleHandle[] handles = starts(listeners, listeners.captureToken());
        registration.unregister();
        for (int i = handles.length - 1; i >= 0; i--) {
            handles[i].end(RESULT);
            handles[i].end(RESULT);
        }
        for (int i = 0; i < ENDS.length; i++) {
            LifecycleCallbackHealth health = registration.getCallbackHealth(ENDS[i]);
            assertSame(ENDS[i], health.getCallbackKind());
            assertEquals(1L, health.getInvocationCount());
            assertEquals(i % 2, health.getFailureCount());
            assertEquals(i == 0 ? 0L : 1L, health.getSlowInvocationCount());
            assertEquals((i + 1L) * 10L, health.getMaximumDurationNanos());
        }
        LifecycleCallbackHealth total = registration.getCallbackHealth(LifecycleCallbackKind.HANDLE_END);
        assertEquals(6L, total.getInvocationCount());
        assertEquals(3L, total.getFailureCount());
        assertEquals(5L, total.getSlowInvocationCount());
        assertEquals(60L, total.getMaximumDurationNanos());
        assertEquals(0, registration.getActiveInvocationCount());
    }

    @Test
    public void operationEndFailureAdvancesQuarantineOnlyOnceAndRetainedEndsStillRun() {
        AtomicLong clock = new AtomicLong();
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(clock::get, Long.MAX_VALUE, Long.MAX_VALUE, 2);
        LifecycleListenerRegistration registration = listeners.register(new AllOperations(clock, true));
        LifecycleHandle[] handles = starts(listeners, listeners.captureToken());
        handles[1].end(RESULT);
        assertFalse(registration.isQuarantined());
        assertEquals(1, registration.getConsecutiveFailureCount());
        handles[3].end(RESULT);
        assertTrue(registration.isQuarantined());
        assertEquals(2, registration.getConsecutiveFailureCount());
        handles[5].end(RESULT);
        assertEquals(3, registration.getConsecutiveFailureCount());
        for (int i = 0; i < handles.length; i += 2) handles[i].end(RESULT);
        assertEquals(6L, registration.getCallbackHealth(LifecycleCallbackKind.HANDLE_END).getInvocationCount());
        assertEquals(1L, registration.getCallbackHealth(LifecycleCallbackKind.SEND_END).getFailureCount());
    }

    @Test
    public void fatalStartUnwindReportsOriginalOperationEnd() {
        AtomicLong clock = new AtomicLong();
        MessageLifecycleListeners listeners = new MessageLifecycleListeners(clock::get, Long.MAX_VALUE, Long.MAX_VALUE, 100);
        LifecycleListenerRegistration registration = listeners.register(new AllOperations(clock, false));
        ThreadDeath fatal = new ThreadDeath();
        listeners.register(new MessageLifecycleListener() {
            @Override public LifecycleHandle onFilterTransformerStart(MessageInfo message) { throw fatal; }
        });
        try {
            listeners.onFilterTransformerStart(listeners.captureToken(), source());
            fail("fatal start must escape");
        } catch (ThreadDeath actual) { assertSame(fatal, actual); }
        assertEquals(1L, registration.getCallbackHealth(LifecycleCallbackKind.FILTER_TRANSFORMER_END).getInvocationCount());
        assertEquals(1L, registration.getCallbackHealth(LifecycleCallbackKind.HANDLE_END).getInvocationCount());
        assertEquals(0L, registration.getCallbackHealth(LifecycleCallbackKind.PROCESS_END).getInvocationCount());
    }

    @Test
    public void fatalEndsKeepExactKindsAndStillCloseSiblingHandles() {
        for (int operation = 0; operation < ENDS.length; operation++) {
            AtomicLong clock = new AtomicLong();
            MessageLifecycleListeners listeners = new MessageLifecycleListeners(clock::get, 10L, Long.MAX_VALUE, 2);
            LifecycleListenerRegistration successful = listeners.register(new AllOperations(clock, false));
            ThreadDeath fatal = new ThreadDeath();
            LifecycleListenerRegistration failing = listeners.register(proxy(false, fatal));
            LifecycleHandle handle = operation(listeners, listeners.captureToken(), operation);
            try { handle.end(RESULT); fail("fatal must escape"); }
            catch (ThreadDeath actual) { assertSame(fatal, actual); }
            handle.end(RESULT);
            assertEquals(1L, successful.getCallbackHealth(ENDS[operation]).getInvocationCount());
            assertEquals(1L, failing.getCallbackHealth(ENDS[operation]).getFailureCount());
            assertEquals(1L, failing.getCallbackHealth(LifecycleCallbackKind.HANDLE_END).getFailureCount());
            assertEquals(1, failing.getConsecutiveFailureCount());
            assertFalse(failing.isQuarantined());
            assertEquals(0, failing.getActiveInvocationCount());
            for (int other = 0; other < ENDS.length; other++) {
                if (other != operation) assertEquals(0L, failing.getCallbackHealth(ENDS[other]).getInvocationCount());
            }
        }
    }

    @Test
    public void everyFatalStartUnwindsUnderItsOwnEndKind() {
        for (int operation = 0; operation < ENDS.length; operation++) {
            AtomicLong clock = new AtomicLong();
            MessageLifecycleListeners listeners = new MessageLifecycleListeners(clock::get, 10L, Long.MAX_VALUE, 2);
            LifecycleListenerRegistration retained = listeners.register(new AllOperations(clock, false));
            ThreadDeath fatal = new ThreadDeath();
            LifecycleListenerRegistration failing = listeners.register(proxy(true, fatal));
            try { operation(listeners, listeners.captureToken(), operation); fail("fatal must escape"); }
            catch (ThreadDeath actual) { assertSame(fatal, actual); }
            assertEquals(1L, retained.getCallbackHealth(ENDS[operation]).getInvocationCount());
            assertEquals(1L, retained.getCallbackHealth(LifecycleCallbackKind.HANDLE_END).getInvocationCount());
            assertEquals(0L, failing.getCallbackHealth(LifecycleCallbackKind.HANDLE_END).getInvocationCount());
            assertEquals(0, failing.getActiveInvocationCount());
        }
    }

    @Test
    public void concurrentEndsAndRetriesCountEachRetainedOperationOnce() throws Exception {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        LifecycleListenerRegistration registration = listeners.register(new AllOperations(new AtomicLong(), false));
        LifecycleHandle[] first = starts(listeners, listeners.captureToken());
        LifecycleHandle[] retry = starts(listeners, listeners.captureToken());
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch begin = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                futures.add(executor.submit(() -> {
                    try { begin.await(); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                    for (int repetition = 0; repetition < 100; repetition++) {
                        for (int operation = 0; operation < ENDS.length; operation++) {
                            first[operation].end(RESULT);
                            retry[operation].end(RESULT);
                        }
                        LifecycleCallbackHealth snapshot = registration.getCallbackHealth(LifecycleCallbackKind.HANDLE_END);
                        assertTrue(snapshot.getInvocationCount() <= 12L);
                        assertEquals(0L, snapshot.getFailureCount());
                        assertTrue(snapshot.getSlowInvocationCount() <= snapshot.getInvocationCount());
                    }
                }));
            }
            begin.countDown();
            registration.unregister();
            for (java.util.concurrent.Future<?> future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        for (LifecycleCallbackKind kind : ENDS) assertEquals(2L, registration.getCallbackHealth(kind).getInvocationCount());
        assertEquals(12L, registration.getCallbackHealth(LifecycleCallbackKind.HANDLE_END).getInvocationCount());
        assertEquals(0, registration.getActiveInvocationCount());
    }

    @Test
    public void emptyCapturedTokenDoesNotAcquireOrRecordEndHealth() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        LifecycleDispatchToken empty = listeners.captureToken();
        LifecycleListenerRegistration registration = listeners.register(new AllOperations(new AtomicLong(), false));
        for (LifecycleHandle handle : starts(listeners, empty)) {
            assertSame(LifecycleHandle.NOOP, handle);
            handle.end(RESULT);
        }
        for (LifecycleCallbackKind kind : LifecycleCallbackKind.values()) {
            assertEquals(0L, registration.getCallbackHealth(kind).getInvocationCount());
        }
    }

    private static MessageLifecycleListener proxy(boolean fatalStart, ThreadDeath fatal) {
        return (MessageLifecycleListener) java.lang.reflect.Proxy.newProxyInstance(
                MessageLifecycleListener.class.getClassLoader(), new Class<?>[] { MessageLifecycleListener.class },
                (object, method, arguments) -> {
                    if (method.getReturnType() == LifecycleHandle.class) {
                        if (fatalStart) throw fatal;
                        return (LifecycleHandle) result -> { throw fatal; };
                    }
                    return null;
                });
    }

    private static LifecycleHandle operation(MessageLifecycleListeners listeners, LifecycleDispatchToken token, int index) {
        MessageInfo destination = new MessageInfo("server", "channel", "Channel", 7, 1, "destination",
                "HTTP Sender", 11, 1, Status.QUEUED, 0, 100L, null, null);
        switch (index) {
            case 0: return listeners.onDispatchStart(token, new DispatchInfo("server", "channel", "Channel", 0,
                    "source", "HTTP Listener", InboundParentState.ABSENT, null, null));
            case 1: return listeners.onProcessStart(token, new ProcessInfo(source(), ExecutionMode.SYNCHRONOUS));
            case 2: return listeners.onFilterTransformerStart(token, source());
            case 3: return listeners.onDestinationChainStart(token, new ChainInfo(destination, ExecutionMode.SYNCHRONOUS));
            case 4: return listeners.onDestinationQueueStart(token, new QueueInfo(destination, ExecutionMode.DESTINATION_QUEUE, 1L));
            case 5: return listeners.onSendStart(token, new SendInfo(destination, ExecutionMode.SYNCHRONOUS, 1));
            default: throw new AssertionError(index);
        }
    }

    private static LifecycleHandle[] starts(MessageLifecycleListeners listeners, LifecycleDispatchToken token) {
        MessageInfo destination = new MessageInfo("server", "channel", "Channel", 7, 1, "destination",
                "HTTP Sender", 11, 1, Status.QUEUED, 0, 100L, null, null);
        return new LifecycleHandle[] {
            listeners.onDispatchStart(token, new DispatchInfo("server", "channel", "Channel", 0,
                    "source", "HTTP Listener", InboundParentState.ABSENT, null, null)),
            listeners.onProcessStart(token, new ProcessInfo(source(), ExecutionMode.SYNCHRONOUS)),
            listeners.onFilterTransformerStart(token, source()),
            listeners.onDestinationChainStart(token, new ChainInfo(destination, ExecutionMode.SYNCHRONOUS)),
            listeners.onDestinationQueueStart(token, new QueueInfo(destination, ExecutionMode.DESTINATION_QUEUE, 1L)),
            listeners.onSendStart(token, new SendInfo(destination, ExecutionMode.SYNCHRONOUS, 1))
        };
    }
    private static MessageInfo source() {
        return new MessageInfo("server", "channel", "Channel", 7, 0, "source",
                "HTTP Listener", 11, null, Status.RECEIVED, 0, 100L, null, null);
    }
    private static class AllOperations implements MessageLifecycleListener {
        private final AtomicLong clock;
        private final boolean failures;
        AllOperations(AtomicLong clock, boolean failures) { this.clock = clock; this.failures = failures; }
        private LifecycleHandle handle(int index) {
            return result -> {
                clock.addAndGet((index + 1L) * 10L);
                if (failures && index % 2 == 1) throw new IllegalStateException("end failure");
            };
        }
        @Override public LifecycleHandle onDispatchStart(DispatchInfo info) { return handle(0); }
        @Override public LifecycleHandle onProcessStart(ProcessInfo info, HandoffReceipt receipt) { return handle(1); }
        @Override public LifecycleHandle onFilterTransformerStart(MessageInfo info) { return handle(2); }
        @Override public LifecycleHandle onDestinationChainStart(ChainInfo info, HandoffReceipt receipt) { return handle(3); }
        @Override public LifecycleHandle onDestinationQueueStart(QueueInfo info, HandoffReceipt receipt) { return handle(4); }
        @Override public LifecycleHandle onSendStart(SendInfo info) { return handle(5); }
    }
}
