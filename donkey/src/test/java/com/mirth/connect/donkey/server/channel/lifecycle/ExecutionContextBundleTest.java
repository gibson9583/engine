/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.donkey.server.channel.lifecycle;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class ExecutionContextBundleTest {
    private final ThreadLocal<String> current = new ThreadLocal<>();
    private MessageLifecycleListener listener(String value, List<String> calls) {
        return new MessageLifecycleListener() {
            @Override public LifecycleExecutionContext captureExecutionContext() {
                calls.add("capture:"+value);
                String captured=current.get();
                return () -> {
                    calls.add("attach:"+value);
                    String previous=current.get();
                    current.set(captured+":"+value);
                    Thread worker=Thread.currentThread();
                    return () -> {
                        assertSame(worker,Thread.currentThread());
                        calls.add("close:"+value);
                        if(previous==null)current.remove();else current.set(previous);
                    };
                };
            }
        };
    }
    @Test public void actualWorkerRestoresAmbientAndClosesInReverseOrder()throws Exception {
        var registry=new MessageLifecycleListeners();var calls=new CopyOnWriteArrayList<String>();
        registry.register(listener("a",calls));registry.register(listener("b",calls));
        current.set("producer");var transfer=registry.captureExecutionContexts(registry.captureToken());
        var executor=Executors.newSingleThreadExecutor();try {
            executor.submit(()->current.set("ambient")).get();
            assertEquals("producer:b",executor.submit(()->transfer.call(current::get)).get());
            assertEquals("producer",current.get());
            assertEquals("ambient",executor.submit(current::get).get());
            assertEquals(Arrays.asList("capture:a","capture:b","attach:a","attach:b","close:b","close:a"),calls);
            assertEquals("ambient",executor.submit(()->transfer.call(current::get)).get());
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));current.remove();}
    }
    @Test public void emptyAndCancelledTransfersRunWithoutAuthority()throws Exception {
        var registry=new MessageLifecycleListeners();assertSame(ExecutionContextBundle.EMPTY,registry.captureExecutionContexts(registry.captureToken()));
        var calls=new ArrayList<String>();registry.register(listener("a",calls));
        var transfer=registry.captureExecutionContexts(registry.captureToken());transfer.cancel();transfer.cancel();
        assertNull(transfer.call(current::get));assertEquals(List.of("capture:a"),calls);
    }
    @Test public void unregisteredIdentityNeverBackfillsEvenWhenSameListenerIsRegisteredAgain()throws Exception {
        var registry=new MessageLifecycleListeners();var calls=new ArrayList<String>();var listener=listener("a",calls);
        var old=registry.register(listener);var token=registry.captureToken();var transfer=registry.captureExecutionContexts(token);
        old.unregister();registry.register(listener);assertNull(transfer.call(current::get));
        assertSame(ExecutionContextBundle.EMPTY,registry.captureExecutionContexts(token));assertEquals(List.of("capture:a"),calls);
    }
    @Test public void cancellationAfterClaimNeverClosesHeldWorkerScope()throws Exception {
        var registry=new MessageLifecycleListeners();var calls=new CopyOnWriteArrayList<String>();registry.register(listener("a",calls));
        current.set("producer");var transfer=registry.captureExecutionContexts(registry.captureToken());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try {
            Future<String> worker=executor.submit(()->transfer.call(()->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return current.get();}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));transfer.cancel();assertEquals(List.of("capture:a","attach:a"),calls);
            assertEquals("producer",current.get());release.countDown();assertEquals("producer:a",worker.get());
            assertNull(executor.submit(current::get).get());assertEquals(List.of("capture:a","attach:a","close:a"),calls);
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));current.remove();}
    }
    @Test public void nonfatalCaptureAndAttachFailuresDoNotSuppressSiblings()throws Exception {
        var registry=new MessageLifecycleListeners();var calls=new ArrayList<String>();
        registry.register(new MessageLifecycleListener(){@Override public LifecycleExecutionContext captureExecutionContext(){throw new IllegalStateException("private");}});
        registry.register(new MessageLifecycleListener(){@Override public LifecycleExecutionContext captureExecutionContext(){return ()->{throw new IllegalStateException("private");};}});
        registry.register(listener("ok",calls));var transfer=registry.captureExecutionContexts(registry.captureToken());
        assertEquals("null:ok",transfer.call(current::get));assertNull(current.get());assertEquals(List.of("capture:ok","attach:ok","close:ok"),calls);
    }
    @Test public void fatalAttachUnwindsEveryPreviouslyAttachedSibling()throws Exception {
        var registry=new MessageLifecycleListeners();var calls=new ArrayList<String>();registry.register(listener("ok",calls));var fatal=new OutOfMemoryError("controlled");
        registry.register(new MessageLifecycleListener(){@Override public LifecycleExecutionContext captureExecutionContext(){return ()->{throw fatal;};}});
        var transfer=registry.captureExecutionContexts(registry.captureToken());
        try {transfer.call(()->failOperation());fail();}catch(OutOfMemoryError actual){assertSame(fatal,actual);}
        assertNull(current.get());assertEquals(List.of("capture:ok","attach:ok","close:ok"),calls);
    }
    @Test public void fatalCloseKeepsTaskFatalAndCleansSiblings()throws Exception {
        var registry=new MessageLifecycleListeners();var calls=new ArrayList<String>();registry.register(listener("ok",calls));var cleanup=new OutOfMemoryError("cleanup");var task=new ThreadDeath();
        registry.register(new MessageLifecycleListener(){@Override public LifecycleExecutionContext captureExecutionContext(){return ()->()->{throw cleanup;};}});
        try {registry.captureExecutionContexts(registry.captureToken()).call(()->{throw task;});fail();}catch(ThreadDeath actual){assertSame(task,actual);assertSame(cleanup,actual.getSuppressed()[0]);}
        assertNull(current.get());assertEquals(List.of("capture:ok","attach:ok","close:ok"),calls);
    }
    @Test public void fatalCloseTakesPrecedenceOverOrdinaryTaskFailure()throws Exception {
        var registry=new MessageLifecycleListeners();var cleanup=new OutOfMemoryError("cleanup");var task=new IllegalStateException("task");
        registry.register(new MessageLifecycleListener(){@Override public LifecycleExecutionContext captureExecutionContext(){return ()->()->{throw cleanup;};}});
        try {registry.captureExecutionContexts(registry.captureToken()).call(()->{throw task;});fail();}catch(OutOfMemoryError actual){assertSame(cleanup,actual);assertSame(task,actual.getSuppressed()[0]);}
    }
    private static String failOperation(){fail("operation must not run after fatal attach");return "";}
}
