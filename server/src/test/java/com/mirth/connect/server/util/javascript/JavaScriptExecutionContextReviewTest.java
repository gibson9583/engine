/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.server.util.javascript;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.channel.lifecycle.*;

/** Uses the real execute/Future/cancellation boundary with an owned private executor. */
public class JavaScriptExecutionContextReviewTest {
    private final ThreadLocal<String> current = new ThreadLocal<>();
    private ExecutorService original, worker;
    private Field executorField;
    private LifecycleListenerRegistration registration;
    private final AtomicInteger captures = new AtomicInteger(), attaches = new AtomicInteger(), closes = new AtomicInteger();
    private final MessageLifecycleListeners registry = Donkey.getInstance().getMessageLifecycleListeners();
    @BeforeClass public static void initialize() { JavaScriptUtilTest.setUpBeforeClass(); }
    @Before public void setUp() throws Exception {
        executorField = JavaScriptUtil.class.getDeclaredField("executor"); executorField.setAccessible(true);
        original = (ExecutorService) executorField.get(null);
        worker = Executors.newSingleThreadExecutor(); executorField.set(null, worker);
        registration = registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                captures.incrementAndGet(); String value = current.get();
                return () -> { attaches.incrementAndGet(); String previous = current.get(); current.set(value);
                    Thread owner = Thread.currentThread();
                    return () -> { assertSame(owner, Thread.currentThread()); closes.incrementAndGet();
                        if (previous == null) current.remove(); else current.set(previous); };
                };
            }
        });
        current.set("caller");
    }
    @After public void cleanUp() throws Exception {
        executorField.set(null, original); registration.unregister(); current.remove();
        worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
    private <T> JavaScriptTask<T> task(Callable<T> operation) {
        return new JavaScriptTask<T>(null, "context-review") {
            @Override public T doCall() throws Exception { return operation.call(); }
        };
    }
    @Test public void realWorkerRestoresPriorContextAndLegacyCallIsContextFree() throws Exception {
        assertEquals("caller", JavaScriptUtil.execute(task(current::get), registry.captureToken()));
        assertNull(worker.submit(current::get).get()); assertEquals("caller", current.get());
        assertNull(JavaScriptUtil.execute(task(current::get))); assertEquals(1, captures.get());
        assertEquals(1, attaches.get()); assertEquals(1, closes.get());
    }
    @Test public void rejectionDoesNotAttachOrExecuteTask() throws Exception {
        worker.shutdown(); AtomicBoolean ran = new AtomicBoolean();
        try { JavaScriptUtil.execute(task(() -> { ran.set(true); return null; }), registry.captureToken()); fail(); }
        catch (RejectedExecutionException expected) { }
        assertFalse(ran.get()); assertEquals(1, captures.get()); assertEquals(0, attaches.get());
    }
    @Test public void actualFutureCancellationBeforeWorkerStartPreventsTaskAndAttach() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.submit(() -> { occupied.countDown(); release.await(5, TimeUnit.SECONDS); return null; });
        assertTrue(occupied.await(5, TimeUnit.SECONDS)); AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { JavaScriptUtil.execute(task(() -> { ran.set(true); return null; }), registry.captureToken()); }
            catch (Throwable problem) { failure.set(problem); }
        });
        try {
            caller.start(); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (captures.get() == 0 && System.nanoTime() < end) Thread.yield();
            assertEquals(1, captures.get()); caller.interrupt(); caller.join(5000); assertFalse(caller.isAlive());
            assertTrue(failure.get() instanceof InterruptedException); release.countDown(); worker.submit(() -> null).get(5, TimeUnit.SECONDS);
            assertFalse(ran.get()); assertEquals(0, attaches.get()); assertEquals(0, closes.get());
        } finally { release.countDown(); caller.interrupt(); caller.join(5000); }
    }
    @Test public void interruptedCallerNeverClosesClaimedUncooperativeWorkerScope() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            current.set("captured");
            try { JavaScriptUtil.execute(task(() -> {
                entered.countDown();
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                assertEquals("captured", current.get()); exited.countDown(); return null;
            }), registry.captureToken()); } catch (Throwable problem) { failure.set(problem); }
            finally { current.remove(); }
        });
        try {
            caller.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive()); assertTrue(failure.get() instanceof InterruptedException);
            assertEquals(1, attaches.get()); assertEquals(0, closes.get());
            release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertNull(worker.submit(current::get).get(5, TimeUnit.SECONDS)); assertEquals(1, closes.get());
        } finally { release.countDown(); caller.interrupt(); caller.join(5000); }
    }
    @Test public void realFuturePreservesTaskFatalForScopedPathAndLegacyWrappingForUnscoped() throws Exception {
        ThreadDeath fatal = new ThreadDeath();
        try { JavaScriptUtil.execute(task(() -> { throw fatal; }), registry.captureToken()); fail(); }
        catch (ThreadDeath actual) { assertSame(fatal, actual); }
        assertEquals(1, closes.get()); assertNull(worker.submit(current::get).get());
        try { JavaScriptUtil.execute(task(() -> { throw fatal; })); fail(); }
        catch (JavaScriptExecutorException actual) { assertSame(fatal, actual.getCause()); }
    }
    @Test public void noScriptPreAndPostShortcutsDoNotCapture() throws Exception {
        String channel = "69e68c97-067d-4d3d-8ed8-014650b09518";
        JavaScriptTask<Object> body = task(() -> { fail("no script task executed"); return null; });
        assertNull(JavaScriptUtil.executeJavaScriptPreProcessorTask(body, channel, registry.captureToken()));
        assertNull(JavaScriptUtil.executeJavaScriptPostProcessorTask(body, channel, registry.captureToken()));
        assertEquals(0, captures.get()); assertEquals(0, attaches.get());
    }
    @Test public void realFuturePreservesAttachAndCleanupFatalIdentities() throws Exception {
        OutOfMemoryError attachFailure = new OutOfMemoryError("controlled_attach");
        LifecycleListenerRegistration failing = registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() { return () -> { throw attachFailure; }; }
        });
        try {
            try { JavaScriptUtil.execute(task(() -> { fail("body after fatal attach"); return null; }), registry.captureToken()); fail(); }
            catch (OutOfMemoryError actual) { assertSame(attachFailure, actual); }
            assertEquals(1, closes.get()); assertNull(worker.submit(current::get).get());
        } finally { failing.unregister(); }
        ThreadDeath cleanupFailure = new ThreadDeath();
        failing = registry.register(new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() { return () -> () -> { throw cleanupFailure; }; }
        });
        try {
            try { JavaScriptUtil.execute(task(() -> "done"), registry.captureToken()); fail(); }
            catch (ThreadDeath actual) { assertSame(cleanupFailure, actual); }
            assertEquals(2, closes.get()); assertNull(worker.submit(current::get).get());
        } finally { failing.unregister(); }
    }
    @Test public void binaryAttachmentStillCapturesOriginalTokenAfterRawCarrierReplacement() throws Exception {
        String channel = "32a48acf-2f02-4c40-9f8b-ed3bd4c314c2";
        MirthContextFactory factory = new MirthContextFactory(new java.net.URL[0], new HashSet<>(), false);
        com.mirth.connect.donkey.model.message.RawMessage binary = new com.mirth.connect.donkey.model.message.RawMessage(new byte[] {1, 2, 3});
        binary.setLifecycleDispatchToken(registry.captureToken());
        String result = JavaScriptUtil.executeAttachmentScript(factory, binary, channel, "fixture", new ArrayList<>());
        assertEquals("AQID", result);
        assertEquals(1, captures.get()); assertEquals(1, attaches.get()); assertEquals(1, closes.get());
        assertNull(worker.submit(current::get).get());
    }
    @Test public void nestedActualSubmissionsRestoreOuterWorkerAndCallerScopes() throws Exception {
        worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        worker = Executors.newFixedThreadPool(2); executorField.set(null, worker);
        LifecycleDispatchToken token = registry.captureToken();
        String result = JavaScriptUtil.execute(task(() -> {
            assertEquals("caller", current.get());
            assertEquals("caller", JavaScriptUtil.execute(task(current::get), token));
            assertEquals("caller", current.get());
            return "outer-completed";
        }), token);
        assertEquals("outer-completed", result); assertEquals("caller", current.get());
        assertEquals(2, captures.get()); assertEquals(2, attaches.get()); assertEquals(2, closes.get());
    }
}
