/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.sql.Statement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class CheckedReadCancellationOwnershipTest {
    @Test(timeout = 10000)
    public void runningDeadlineCancellationOutlivesRegistrationCloseUntilActualExit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Statement statement = mock(Statement.class);
        doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(statement).cancel();
        CallbackCancellation cancellation = new CallbackCancellation();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 100_000_000L, cancellation);
        try {
            var registration = control.arm(statement);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancellation.callback.get().run(); // Explicit cancellation loses to the real running deadline callback.
            registration.close(); registration.close();
            assertFalse(control.isCancellationComplete());
            release.countDown(); awaitTerminal(control);
            verify(statement, times(1)).cancel();
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000)
    public void simultaneousExplicitAndDeadlineCallbacksInvokeJdbcCancelOnce() throws Exception {
        Statement statement = mock(Statement.class);
        CallbackCancellation cancellation = new CallbackCancellation();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 150_000_000L, cancellation);
        try (var registration = control.arm(statement)) {
            Runnable callback = cancellation.callback.get();
            Thread first = new Thread(callback), second = new Thread(callback);
            first.start(); second.start(); first.join(2000); second.join(2000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive()); awaitTerminal(control);
            callback.run(); verify(statement, times(1)).cancel();
        }
        assertTrue(control.isCancellationComplete());
    }

    @Test(timeout = 10000)
    public void failedRegistrationCannotLeaveScheduledOrRetainedCallbackCapableOfCancel() throws Exception {
        Statement statement = mock(Statement.class); RuntimeException original = new IllegalStateException("registration_failure");
        AtomicReference<Runnable> retained = new AtomicReference<>();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 1_000_000_000L,
                new CheckedReadControl.Cancellation() {
                    public boolean isCancelled() { return false; }
                    public CheckedReadControl.CancellationRegistration register(Runnable callback) { retained.set(callback); throw original; }
                });
        assertSame(original, assertThrows(RuntimeException.class, () -> control.arm(statement)));
        assertTrue(control.isCancellationComplete()); retained.get().run(); retained.get().run();
        verify(statement, never()).cancel();
    }

    @Test(timeout = 10000)
    public void failedRegistrationStillTracksAlreadyClaimedCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Statement statement = mock(Statement.class);
        doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(statement).cancel();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 3_000_000_000L,
                new CheckedReadControl.Cancellation() {
                    public boolean isCancelled() { return false; }
                    public CheckedReadControl.CancellationRegistration register(Runnable callback) {
                        Thread task = new Thread(callback); callbackThread.set(task); task.start();
                        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                        throw new IllegalStateException("accepted_then_failed");
                    }
                });
        try {
            assertThrows(IllegalStateException.class, () -> control.arm(statement));
            assertFalse(control.isCancellationComplete()); release.countDown(); callbackThread.get().join(2000); awaitTerminal(control);
            verify(statement, times(1)).cancel();
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000)
    public void cancellationExceptionsExitAndOrdinaryCloseCannotEnableLateCallback() throws Exception {
        for (Throwable failure : new Throwable[] {new java.sql.SQLException("driver"), new AssertionError("driver"), new OutOfMemoryError("driver"), new ThreadDeath()}) {
            Statement statement = mock(Statement.class); doThrow(failure).when(statement).cancel();
            CallbackCancellation cancellation = new CallbackCancellation();
            CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 3_000_000_000L, cancellation);
            try (var registration = control.arm(statement)) {
                if (failure instanceof java.sql.SQLException) cancellation.callback.get().run();
                else assertSame(failure, assertThrows(failure.getClass(), cancellation.callback.get()::run));
                assertTrue(control.isCancellationComplete()); cancellation.callback.get().run();
                verify(statement, times(1)).cancel();
            }
        }
        Statement statement = mock(Statement.class); CallbackCancellation cancellation = new CallbackCancellation();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 3_000_000_000L, cancellation);
        var registration = control.arm(statement); Runnable stale = cancellation.callback.get();
        assertFalse(control.isCancellationComplete()); registration.close(); assertTrue(control.isCancellationComplete()); stale.run();
        verify(statement, never()).cancel();
    }

    @Test(timeout = 10000)
    public void queryTimeoutFailureCleansThePreallocatedDutyWithoutRegistering() throws Exception {
        Statement statement = mock(Statement.class); doThrow(new java.sql.SQLException("timeout_setup")).when(statement).setQueryTimeout(anyInt());
        CallbackCancellation cancellation = new CallbackCancellation();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + 3_000_000_000L, cancellation);
        assertThrows(java.sql.SQLException.class, () -> control.arm(statement));
        assertTrue(control.isCancellationComplete()); assertNull(cancellation.callback.get()); verify(statement, never()).cancel();
    }

    @Test(timeout = 10000)
    public void multipleArmsAndSharedNoneRemainCountedUntilEveryDutyIsTerminal() throws Exception {
        var callbacks = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();
        CheckedReadControl control = new CheckedReadControl(System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
                new CheckedReadControl.Cancellation() {
                    public boolean isCancelled() { return false; }
                    public CheckedReadControl.CancellationRegistration register(Runnable callback) {
                        callbacks.add(callback); return () -> callbacks.remove(callback);
                    }
                });
        CountDownLatch[] entered = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        Thread[] workers = new Thread[2];
        CheckedReadControl.CancellationRegistration[] registrations = new CheckedReadControl.CancellationRegistration[2];
        try {
            for (int i = 0; i < 2; i++) {
                int index = i; Statement statement = mock(Statement.class);
                doAnswer(call -> { entered[index].countDown(); release[index].await(); return null; }).when(statement).cancel();
                registrations[i] = control.arm(statement);
                workers[i] = new Thread(callbacks.get(i)); workers[i].start();
                assertTrue(entered[i].await(2, TimeUnit.SECONDS));
            }
            for (var registration : registrations) registration.close();
            assertFalse(control.isCancellationComplete());
            release[0].countDown(); workers[0].join(2000); assertFalse(workers[0].isAlive());
            assertFalse(control.isCancellationComplete());
            release[1].countDown(); workers[1].join(2000); assertFalse(workers[1].isAlive());
            assertTrue(control.isCancellationComplete());
        } finally {
            for (var latch : release) latch.countDown();
            for (var registration : registrations) if (registration != null) registration.close();
            for (var worker : workers) if (worker != null) worker.join(2000);
        }
        Statement first = mock(Statement.class), second = mock(Statement.class);
        try (var one = CheckedReadControl.NONE.arm(first); var two = CheckedReadControl.NONE.arm(second)) {
            assertFalse(CheckedReadControl.NONE.isCancellationComplete()); one.close();
            assertFalse(CheckedReadControl.NONE.isCancellationComplete()); two.close();
            assertTrue(CheckedReadControl.NONE.isCancellationComplete());
            verifyNoInteractions(first, second);
        }
    }

    @Test(timeout = 10000)
    public void nullRegistrationCannotOrphanEitherReadyOrClaimedCallback() throws Exception {
        for (boolean claimed : new boolean[] {false, true}) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Statement statement = mock(Statement.class);
            doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(statement).cancel();
            AtomicReference<Runnable> retained = new AtomicReference<>(); AtomicReference<Thread> worker = new AtomicReference<>();
            CheckedReadControl control = new CheckedReadControl(System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
                    new CheckedReadControl.Cancellation() {
                        public boolean isCancelled() { return false; }
                        public CheckedReadControl.CancellationRegistration register(Runnable callback) {
                            retained.set(callback);
                            if (claimed) {
                                worker.set(new Thread(callback)); worker.get().start();
                                try { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
                                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                            }
                            return null;
                        }
                    });
            try {
                assertEquals("cancellationRegistration", assertThrows(NullPointerException.class, () -> control.arm(statement)).getMessage());
                assertEquals(!claimed, control.isCancellationComplete());
                release.countDown(); if (worker.get() != null) worker.get().join(2000);
                awaitTerminal(control); retained.get().run(); retained.get().run();
                verify(statement, times(claimed ? 1 : 0)).cancel();
            } finally { release.countDown(); if (worker.get() != null) worker.get().join(2000); }
        }
    }

    @Test(timeout = 10000)
    public void bothCloseOperationsRunAndFirstFatalPrioritySurvivesEveryPair() throws Exception {
        for (int firstKind = 0; firstKind < 4; firstKind++) for (int nextKind = 0; nextKind < 4; nextKind++) {
            Statement statement = mock(Statement.class); CallbackCancellation cancellation = new CallbackCancellation();
            CheckedReadControl control = new CheckedReadControl(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), cancellation);
            var arm = control.arm(statement); Runnable retained = cancellation.callback.get();
            var futureField = arm.getClass().getDeclaredField("deadlineTask"); futureField.setAccessible(true);
            var registrationField = arm.getClass().getDeclaredField("registration"); registrationField.setAccessible(true);
            ((ScheduledFuture<?>)futureField.get(arm)).cancel(false);
            ((CheckedReadControl.CancellationRegistration)registrationField.get(arm)).close();
            ScheduledFuture<?> failedFuture = mock(ScheduledFuture.class);
            var failedRegistration = mock(CheckedReadControl.CancellationRegistration.class);
            Throwable first = failure(firstKind), next = failure(nextKind);
            doThrow(first).when(failedFuture).cancel(false); doThrow(next).when(failedRegistration).close();
            futureField.set(arm, failedFuture); registrationField.set(arm, failedRegistration);
            Throwable expected = firstKind >= 2 || nextKind < 2 ? first : next;
            assertSame(expected, assertThrows(Throwable.class, arm::close));
            verify(failedFuture, times(1)).cancel(false); verify(failedRegistration, times(1)).close();
            arm.close(); retained.run(); assertTrue(control.isCancellationComplete());
            verify(statement, never()).cancel();
        }
    }

    @Test(timeout = 10000)
    public void isolatedSchedulerRejectionAndAcceptThenStartFailureLeaveNoCapableCallback() throws Exception {
        for (boolean accepted : new boolean[] {false, true}) {
            String name = CheckedReadControl.class.getName();
            ClassLoader loader = new ClassLoader(CheckedReadControl.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String selected, boolean resolve) throws ClassNotFoundException {
                    if (!selected.equals(name) && !selected.startsWith(name + "$")) return super.loadClass(selected, resolve);
                    synchronized (getClassLoadingLock(selected)) {
                        Class<?> loaded = findLoadedClass(selected);
                        if (loaded == null) {
                            try (var bytes = getParent().getResourceAsStream(selected.replace('.', '/') + ".class")) {
                                if (bytes == null) throw new ClassNotFoundException(selected);
                                byte[] code = bytes.readAllBytes(); loaded = defineClass(selected, code, 0, code.length);
                            } catch (java.io.IOException failure) { throw new ClassNotFoundException(selected, failure); }
                        }
                        if (resolve) resolveClass(loaded); return loaded;
                    }
                }
            };
            Class<?> controlType = Class.forName(name, true, loader);
            Class<?> cancellationType = Class.forName(name + "$Cancellation", true, loader);
            Object cancellation = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {cancellationType},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("isCancelled")) return false;
                        throw new AssertionError("registration after scheduling failure");
                    });
            Object control = controlType.getConstructor(long.class, cancellationType)
                    .newInstance(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), cancellation);
            var field = controlType.getDeclaredField("DEADLINE_CANCELLER"); field.setAccessible(true);
            var scheduler = (ScheduledThreadPoolExecutor)field.get(null);
            var startFailure = new OutOfMemoryError("accepted_then_thread_start_failed");
            if (accepted) scheduler.setThreadFactory(task -> { throw startFailure; });
            else scheduler.shutdown();
            Statement statement = mock(Statement.class);
            try {
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> controlType.getMethod("arm", Statement.class).invoke(control, statement));
                if (accepted) {
                    assertSame(startFailure, failure.getCause());
                    assertEquals(1, scheduler.getQueue().size());
                    for (Runnable late : scheduler.getQueue().toArray(new Runnable[0])) late.run();
                } else assertTrue(failure.getCause() instanceof RejectedExecutionException);
                assertEquals(true, controlType.getMethod("isCancellationComplete").invoke(control));
                verify(statement).setQueryTimeout(anyInt()); verify(statement, never()).cancel();
            } finally { scheduler.shutdownNow(); assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS)); }
        }
    }

    @Test(timeout = 10000)
    public void postRegistrationCancellationPreservesTypedFailureUnlessCleanupIsFatal() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            Throwable cleanup = failure(kind); AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<Runnable> retained = new AtomicReference<>();
            var registration = mock(CheckedReadControl.CancellationRegistration.class);
            doThrow(cleanup).when(registration).close();
            CheckedReadControl control = new CheckedReadControl(System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
                    new CheckedReadControl.Cancellation() {
                        public boolean isCancelled() { return cancelled.get(); }
                        public CheckedReadControl.CancellationRegistration register(Runnable callback) {
                            retained.set(callback); cancelled.set(true); return registration;
                        }
                    });
            Statement statement = mock(Statement.class);
            Throwable actual = assertThrows(Throwable.class, () -> control.arm(statement));
            if (kind >= 2) {
                assertSame(cleanup, actual);
                assertEquals(CheckedReadException.Reason.CANCELLED,
                        ((CheckedReadException)actual.getSuppressed()[0]).getReason());
            } else {
                assertTrue(actual instanceof CheckedReadException);
                assertEquals(CheckedReadException.Reason.CANCELLED, ((CheckedReadException)actual).getReason());
                assertSame(cleanup, actual.getSuppressed()[0]);
            }
            assertTrue(control.isCancellationComplete()); retained.get().run();
            verify(registration, times(1)).close(); verify(statement, never()).cancel();
        }
    }

    private static Throwable failure(int kind) {
        switch (kind) {
            case 0: return new IllegalStateException("ordinary");
            case 1: return new AssertionError("nonfatal_error");
            case 2: return new OutOfMemoryError("fatal");
            default: return new ThreadDeath();
        }
    }

    private static void awaitTerminal(CheckedReadControl control) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!control.isCancellationComplete() && deadline - System.nanoTime() > 0) Thread.sleep(1);
        assertTrue(control.isCancellationComplete());
    }
    private static final class CallbackCancellation implements CheckedReadControl.Cancellation {
        final AtomicReference<Runnable> callback = new AtomicReference<>();
        public boolean isCancelled() { return false; }
        public CheckedReadControl.CancellationRegistration register(Runnable selected) {
            callback.set(selected); return () -> callback.compareAndSet(selected, null);
        }
    }
}
