/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.donkey.server.channel.lifecycle;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.*;
import org.junit.Test;

/** Independent failure controls for ownership after a listener has returned its scope. */
public class ExecutionContextReviewTest {
    @Test public void concurrentConsumersExecuteBothApplicationsButAttachAuthorityOnce() throws Exception {
        ThreadLocal<String> current = new ThreadLocal<>();
        AtomicInteger attached = new AtomicInteger(), closed = new AtomicInteger();
        MessageLifecycleListeners registry = new MessageLifecycleListeners();
        registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> { attached.incrementAndGet(); current.set("captured");
                    return () -> { closed.incrementAndGet(); current.remove(); }; };
            }
        });
        ExecutionContextBundle transfer = registry.captureExecutionContexts(registry.captureToken());
        CyclicBarrier inside = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Callable<String> run = () -> {
            String result = transfer.call(() -> { inside.await(5, TimeUnit.SECONDS); return current.get(); });
            assertNull(current.get()); return result;
        };
        try {
            Future<String> a = workers.submit(run), b = workers.submit(run);
            List<String> values = Arrays.asList(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertTrue(values.contains("captured")); assertTrue(values.contains(null));
            assertEquals(1, attached.get()); assertEquals(1, closed.get());
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void foreignScopeCloseDoesNotConsumeOwnersLegitimateClose() throws Exception {
        MessageLifecycleListeners registry = new MessageLifecycleListeners();
        AtomicInteger closed = new AtomicInteger();
        LifecycleListenerRegistration registration = registry.register(new MessageLifecycleListener() {});
        LifecycleExecutionScope scope = registry.attachExecutionContexts(new MessageLifecycleListeners.ExecutionEntry[] {
            new MessageLifecycleListeners.ExecutionEntry(registration.getRegistration(), () -> () -> closed.incrementAndGet())
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> { try { scope.close(); } catch (Throwable problem) { failure.set(problem); } });
        foreign.start(); foreign.join(5000); assertFalse(foreign.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException); assertEquals(0, closed.get());
        scope.close(); scope.close(); assertEquals(1, closed.get());
    }

    @Test public void captureKeepsQuarantineFatalBeforeLaterCauseInspectionFatal() throws Exception {
        quarantineFatalBeforeInspection("capture");
    }
    @Test public void attachKeepsQuarantineFatalBeforeLaterCauseInspectionFatal() throws Exception {
        quarantineFatalBeforeInspection("attach");
    }
    @Test public void closeKeepsQuarantineFatalBeforeLaterCauseInspectionFatal() throws Exception {
        quarantineFatalBeforeInspection("close");
    }
    private void quarantineFatalBeforeInspection(String phase) throws Exception {
        OutOfMemoryError first = new OutOfMemoryError("controlled_abandonment");
        ThreadDeath later = new ThreadDeath();
        IllegalStateException ordinary = new IllegalStateException("controlled_callback") {
            @Override public synchronized Throwable getCause() { throw later; }
        };
        AtomicInteger siblingCloses = new AtomicInteger();
        AtomicInteger abandonments = new AtomicInteger();
        MessageLifecycleListeners registry = new MessageLifecycleListeners(() -> 1L,
                Long.MAX_VALUE, Long.MAX_VALUE, 1);
        LifecycleListenerRegistration sibling = registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> () -> siblingCloses.incrementAndGet();
            }
        });
        LifecycleListenerRegistration failing = registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                if (phase.equals("capture")) throw ordinary;
                return () -> {
                    if (phase.equals("attach")) throw ordinary;
                    return () -> { throw ordinary; };
                };
            }
            @Override public void onHandoffsAbandoned(HandoffAbandonReason reason) {
                abandonments.incrementAndGet(); throw first;
            }
        });
        Throwable observed = null;
        try { registry.captureExecutionContexts(registry.captureToken()).call(() -> null); }
        catch (Throwable problem) { observed = problem; }
        assertEquals(1, abandonments.get());
        assertEquals(phase.equals("capture") ? 0 : 1, siblingCloses.get());
        assertEquals(0, sibling.getActiveInvocationCount());
        assertEquals(0, failing.getActiveInvocationCount());
        assertSame("quarantine raised the first fatal before cause inspection", first, observed);
    }

    @Test public void timingFailureAfterCaptureLeaseAcquisitionReleasesInvocation() {
        AtomicBoolean failClock = new AtomicBoolean();
        OutOfMemoryError fatal = new OutOfMemoryError("controlled_clock_failure");
        MessageLifecycleListeners registry = new MessageLifecycleListeners(() -> {
            if (failClock.getAndSet(false)) throw fatal;
            return 1L;
        }, Long.MAX_VALUE, Long.MAX_VALUE, 100);
        LifecycleListenerRegistration registration = registry.register(new MessageLifecycleListener() {});
        failClock.set(true);
        try { registry.captureExecutionContexts(registry.captureToken()); fail(); }
        catch (OutOfMemoryError actual) { assertSame(fatal, actual); }
        assertEquals("capture must release its invocation lease on timing failure", 0,
                registration.getActiveInvocationCount());
    }

    @Test public void timingFailureAfterAttachLeaseAcquisitionReleasesInvocation() throws Exception {
        AtomicBoolean failClock = new AtomicBoolean();
        OutOfMemoryError fatal = new OutOfMemoryError("controlled_clock_failure");
        MessageLifecycleListeners registry = new MessageLifecycleListeners(() -> {
            if (failClock.getAndSet(false)) throw fatal;
            return 1L;
        }, Long.MAX_VALUE, Long.MAX_VALUE, 100);
        LifecycleListenerRegistration registration = registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> LifecycleExecutionScope.NOOP;
            }
        });
        ExecutionContextBundle transfer = registry.captureExecutionContexts(registry.captureToken());
        failClock.set(true);
        try { transfer.call(() -> null); fail(); }
        catch (OutOfMemoryError actual) { assertSame(fatal, actual); }
        assertEquals("attach must release its invocation lease on timing failure", 0,
                registration.getActiveInvocationCount());
    }

    @Test public void bookkeepingFatalCannotSkipEarlierOwnedScope() throws Exception {
        AtomicBoolean failClock = new AtomicBoolean();
        OutOfMemoryError fatal = new OutOfMemoryError("controlled_clock_failure");
        AtomicInteger closed = new AtomicInteger();
        MessageLifecycleListeners registry = new MessageLifecycleListeners(() -> {
            if (failClock.getAndSet(false)) throw fatal;
            return 1L;
        }, Long.MAX_VALUE, Long.MAX_VALUE, 100);
        registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> () -> closed.incrementAndGet();
            }
        });
        registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> () -> { closed.incrementAndGet(); failClock.set(true); };
            }
        });
        try { registry.captureExecutionContexts(registry.captureToken()).call(() -> null); fail(); }
        catch (OutOfMemoryError actual) { assertSame(fatal, actual); }
        assertEquals("all returned scopes remain owned through bookkeeping failure", 2, closed.get());
    }

    @Test public void cleanupFatalOutranksOrdinaryAttachBookkeepingFailure() throws Exception {
        AtomicBoolean failClock = new AtomicBoolean();
        IllegalStateException ordinary = new IllegalStateException("controlled_clock_failure");
        OutOfMemoryError fatal = new OutOfMemoryError("controlled_close_failure");
        AtomicInteger closed = new AtomicInteger();
        MessageLifecycleListeners registry = new MessageLifecycleListeners(() -> {
            if (failClock.getAndSet(false)) throw ordinary;
            return 1L;
        }, Long.MAX_VALUE, Long.MAX_VALUE, 100);
        registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> () -> { closed.incrementAndGet(); throw fatal; };
            }
        });
        registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                return () -> { failClock.set(true); return () -> closed.incrementAndGet(); };
            }
        });
        try { registry.captureExecutionContexts(registry.captureToken()).call(() -> { fail("body"); return null; }); fail(); }
        catch (Throwable actual) { assertSame("first fatal must propagate", fatal, actual); }
        assertEquals(2, closed.get());
    }
}
