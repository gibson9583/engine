/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import org.junit.After;
import org.junit.Test;

public class PluginPropertyPreparersTest {
    @After
    public void cleanup() {
        PluginPropertyPreparers.clearForTest();
    }

    @Test
    public void registrationOwnsOnePluginPointAndStaleCloseCannotRemoveReplacement()
            throws Exception {
        PreparePluginPropertiesInterface first = noChangePreparer();
        PluginPropertyPreparers.PreparerRegistration firstRegistration =
                PluginPropertyPreparers.register("test", new Object(), first);
        assertThrows(IllegalStateException.class, () -> PluginPropertyPreparers.register(
                "test", new Object(), noChangePreparer()));

        firstRegistration.close();
        PluginPropertyPreparers.PreparerRegistration replacement =
                PluginPropertyPreparers.register("test", new Object(), noChangePreparer());
        firstRegistration.close();
        replacement.activate();
        PreparedPluginProperties prepared = PluginPropertyPreparers.prepare("test",
                new Properties(), false,
                PropertyWriteContext.normal(PropertyWriteOrigin.GENERIC_API, 1));
        assertTrue(prepared.getDirective() instanceof NoChange);
        replacement.close();
    }

    @Test(timeout = 5000)
    public void unregisterDoesNotWaitForAdmittedPrepareOrInvokeItsCloseHook() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        var registration = PluginPropertyPreparers.register("detach", this,
                new PreparePluginPropertiesInterface() {
            @Override
            public PreparedPluginProperties prepare(Properties values, boolean merge,
                    PropertyWriteContext context) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                        (outcome, failure) -> { });
            }
            @Override
            public void close() { closed.set(true); }
        });
        registration.activate();
        FutureTask<PreparedPluginProperties> preparing = new FutureTask<>(() ->
                PluginPropertyPreparers.prepare("detach", new Properties(), false,
                        PropertyWriteContext.pluginApi()));
        Thread worker = new Thread(preparing);
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            registration.unregister();
            assertFalse(preparing.isDone());
            assertFalse(closed.get());
            assertEquals(PluginPropertyPreparers.State.CLOSED, registration.getState());
            assertFalse(PluginPropertyPreparers.isOperational("detach", this));
            assertUnavailable(() -> PluginPropertyPreparers.prepare("detach", new Properties(),
                    false, PropertyWriteContext.pluginApi()));
            assertThrows(IllegalStateException.class, registration::activate);
            var replacement = PluginPropertyPreparers.register("detach", this, noChangePreparer());
            replacement.activate();
            registration.unregister();
            assertTrue(PluginPropertyPreparers.isOperational("detach", this));
        } finally {
            release.countDown();
        }
        preparing.get(1, TimeUnit.SECONDS);
        registration.close();
        assertFalse("unregister transfers cleanup ownership to caller", closed.get());
        assertTrue(PluginPropertyPreparers.isOperational("detach", this));
    }

    @Test(timeout = 5000)
    public void unregisterIsTerminalFromEveryLiveStateAndCanRunInsidePrepare() throws Exception {
        for (PluginPropertyPreparers.State state : new PluginPropertyPreparers.State[] {
                PluginPropertyPreparers.State.INITIALIZING,
                PluginPropertyPreparers.State.ACTIVE,
                PluginPropertyPreparers.State.RECOVERY_ONLY }) {
            AtomicReference<PluginPropertyPreparers.PreparerRegistration> owner = new AtomicReference<>();
            AtomicBoolean hookCalled = new AtomicBoolean();
            owner.set(PluginPropertyPreparers.register("self-detach", this,
                    new PreparePluginPropertiesInterface() {
                @Override
                public PreparedPluginProperties prepare(Properties incoming, boolean merge,
                        PropertyWriteContext context) {
                    owner.get().unregister();
                    var replacement = PluginPropertyPreparers.register("self-detach", new Object(),
                            noChangePreparer());
                    replacement.activate();
                    owner.get().unregister();
                    assertEquals(PluginPropertyPreparers.State.ACTIVE, replacement.getState());
                    return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                            (outcome, failure) -> { });
                }
                @Override
                public void close() { hookCalled.set(true); }
            }));
            PropertyWriteContext context = PropertyWriteContext.initialization();
            if (state == PluginPropertyPreparers.State.ACTIVE) {
                owner.get().activate();
                context = PropertyWriteContext.pluginApi();
            } else if (state == PluginPropertyPreparers.State.RECOVERY_ONLY) {
                owner.get().activateRecoveryOnly();
                context = PropertyWriteContext.recovery(1);
            }
            PluginPropertyPreparers.prepare("self-detach", new Properties(), false, context);
            assertEquals(PluginPropertyPreparers.State.CLOSED, owner.get().getState());
            assertThrows(IllegalStateException.class, owner.get()::activate);
            assertThrows(IllegalStateException.class, owner.get()::activateRecoveryOnly);
            owner.get().close();
            assertFalse(hookCalled.get());
            PluginPropertyPreparers.clearForTest();
        }
    }

    @Test(timeout = 15000)
    public void concurrentUnregisterCloseAndActivationCannotReopenOrRemoveReplacement()
            throws Exception {
        var workers = Executors.newFixedThreadPool(3, task -> {
            Thread worker = new Thread(task);
            worker.setDaemon(true);
            return worker;
        });
        try {
            for (int attempt = 0; attempt < 300; attempt++) {
                var hookCalls = new java.util.concurrent.atomic.AtomicInteger();
                var registration = PluginPropertyPreparers.register("detach-race", this,
                        new PreparePluginPropertiesInterface() {
                    @Override
                    public PreparedPluginProperties prepare(Properties incoming, boolean merge,
                            PropertyWriteContext context) {
                        return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                                (outcome, failure) -> { });
                    }
                    @Override
                    public void close() { hookCalls.incrementAndGet(); }
                });
                if (attempt % 3 == 1) registration.activate();
                if (attempt % 3 == 2) registration.activateRecoveryOnly();
                CyclicBarrier start = new CyclicBarrier(3);
                var close = workers.submit(() -> {
                    start.await(2, TimeUnit.SECONDS);
                    registration.close();
                    return null;
                });
                var detach = workers.submit(() -> {
                    start.await(2, TimeUnit.SECONDS);
                    registration.unregister();
                    return null;
                });
                var activate = workers.submit(() -> {
                    start.await(2, TimeUnit.SECONDS);
                    try { registration.activate(); }
                    catch (IllegalStateException terminalOrAlreadyActive) { }
                    try {
                        PluginPropertyPreparers.prepare("detach-race", new Properties(), false,
                                PropertyWriteContext.pluginApi());
                    } catch (PluginPropertyWriteException terminal) {
                        assertEquals(PluginPropertyWriteOutcome.PREPARER_UNAVAILABLE,
                                terminal.getOutcome());
                    }
                    return null;
                });
                close.get(2, TimeUnit.SECONDS);
                detach.get(2, TimeUnit.SECONDS);
                activate.get(2, TimeUnit.SECONDS);
                assertEquals(PluginPropertyPreparers.State.CLOSED, registration.getState());
                assertTrue(hookCalls.get() <= 1);
                Object nextOwner = new Object();
                var replacement = PluginPropertyPreparers.register("detach-race", nextOwner,
                        noChangePreparer());
                replacement.activate();
                registration.unregister();
                registration.close();
                assertThrows(IllegalStateException.class, registration::activate);
                assertThrows(IllegalStateException.class, registration::activateRecoveryOnly);
                assertTrue(PluginPropertyPreparers.isOperational("detach-race", nextOwner));
                replacement.close();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void legacyCloseHookDoesNotHoldTheEntryMonitorAgainstUnregister() throws Exception {
        AtomicReference<PluginPropertyPreparers.PreparerRegistration> owner = new AtomicReference<>();
        owner.set(PluginPropertyPreparers.register("close-detach", this,
                new PreparePluginPropertiesInterface() {
            @Override
            public PreparedPluginProperties prepare(Properties incoming, boolean merge,
                    PropertyWriteContext context) {
                return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                        (outcome, failure) -> { });
            }
            @Override
            public void close() throws Exception {
                FutureTask<Void> detach = new FutureTask<>(() -> {
                    owner.get().unregister();
                    return null;
                });
                Thread worker = new Thread(detach);
                worker.setDaemon(true);
                worker.start();
                detach.get(2, TimeUnit.SECONDS);
            }
        }));
        owner.get().close();
        assertEquals(PluginPropertyPreparers.State.CLOSED, owner.get().getState());
    }

    @Test
    public void statesAdmitOnlyTheirTrustedOriginAndPurpose() throws Exception {
        PluginPropertyPreparers.PreparerRegistration registration =
                PluginPropertyPreparers.register("test", this, noChangePreparer());
        assertFalse(PluginPropertyPreparers.isOperational("test", this));
        PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.initialization());
        assertUnavailable(() -> PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.normal(PropertyWriteOrigin.GENERIC_API, 1)));

        registration.activateRecoveryOnly();
        assertTrue(PluginPropertyPreparers.isOperational("test", this));
        assertFalse(PluginPropertyPreparers.isOperational("test", new Object()));
        PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.recovery(1));
        assertUnavailable(() -> PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.pluginApi()));

        registration.activate();
        PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.normal(PropertyWriteOrigin.RESTORE, 1));
        PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.recovery(1));
        assertUnavailable(() -> PluginPropertyPreparers.prepare("test", new Properties(), false,
                PropertyWriteContext.initialization()));
    }

    @Test
    public void suppliedActorsAreAlwaysPositiveAndRecoveryIsPluginApiOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> PropertyWriteContext.normal(PropertyWriteOrigin.PLUGIN_API, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PropertyWriteContext(PropertyWriteOrigin.RESTORE,
                        PropertyWritePurpose.NORMAL, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new PropertyWriteContext(PropertyWriteOrigin.GENERIC_API,
                        PropertyWritePurpose.NORMAL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PropertyWriteContext(PropertyWriteOrigin.RESTORE,
                        PropertyWritePurpose.RECOVERY, 1));
    }

    @Test
    public void preparerReceivesAndReturnsDefensiveStringOnlyCopies() throws Exception {
        AtomicBoolean sawOriginal = new AtomicBoolean();
        PreparePluginPropertiesInterface preparer = (incoming, merge, context) -> {
            sawOriginal.set("before".equals(incoming.getProperty("key")));
            incoming.setProperty("key", "canonical");
            return new PreparedPluginProperties(incoming, NoChange.INSTANCE, (outcome, failure) -> { });
        };
        PluginPropertyPreparers.register("test", this, preparer);
        Properties submitted = new Properties();
        submitted.setProperty("key", "before");
        PreparedPluginProperties prepared = PluginPropertyPreparers.prepare("test", submitted,
                false, PropertyWriteContext.initialization());
        submitted.setProperty("key", "after");
        Properties first = prepared.getCanonicalProperties();
        Properties second = prepared.getCanonicalProperties();
        first.setProperty("key", "mutated");

        assertTrue(sawOriginal.get());
        assertEquals("canonical", second.getProperty("key"));
        assertNotSame(first, second);

        Properties invalid = new Properties();
        invalid.put(1, "value");
        assertThrows(IllegalArgumentException.class, () -> PluginPropertyPreparers.prepare(
                "test", invalid, false, PropertyWriteContext.initialization()));
    }

    @Test
    public void closeWaitsForAnInFlightPrepareAndInvokesHookOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        PreparePluginPropertiesInterface preparer = new PreparePluginPropertiesInterface() {
            @Override
            public PreparedPluginProperties prepare(Properties incoming, boolean merge,
                    PropertyWriteContext context) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                        (outcome, failure) -> { });
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    throw new AssertionError("closed twice");
                }
            }
        };
        PluginPropertyPreparers.PreparerRegistration registration =
                PluginPropertyPreparers.register("test", this, preparer);
        Thread preparing = new Thread(() -> {
            try {
                PluginPropertyPreparers.prepare("test", new Properties(), false,
                        PropertyWriteContext.initialization());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        preparing.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread closing = new Thread(registration::close);
        closing.start();
        assertTrue(closing.isAlive());
        release.countDown();
        preparing.join(2000);
        closing.join(2000);
        registration.close();
        assertTrue(closed.get());
    }

    @Test(timeout = 5000)
    public void recoveryCanActivateInsidePrepareWithoutUpgradingItsInvocationLease() throws Exception {
        AtomicReference<PluginPropertyPreparers.PreparerRegistration> owner = new AtomicReference<>();
        owner.set(PluginPropertyPreparers.register("replay", this, (incoming, merge, context) -> {
            owner.get().activate();
            return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                    (outcome, failure) -> { });
        }));
        owner.get().activateRecoveryOnly();
        FutureTask<PreparedPluginProperties> replay = new FutureTask<>(() ->
                PluginPropertyPreparers.prepare("replay", new Properties(), false,
                        PropertyWriteContext.recovery(1)));
        Thread worker = new Thread(replay);
        worker.setDaemon(true);
        worker.start();
        assertTrue(replay.get(2, TimeUnit.SECONDS).getDirective() instanceof NoChange);
        assertTrue(PluginPropertyPreparers.isOperational("replay", this));
        // Activation remains one-shot; repeated or stale transitions cannot reopen an owner.
        assertThrows(IllegalStateException.class, owner.get()::activate);
        owner.get().close();
        assertThrows(IllegalStateException.class, owner.get()::activate);
    }

    @Test(timeout = 5000)
    public void activationDoesNotWaitForPrepareBlockedOnPublicationButCloseStillDoes()
            throws Exception {
        Object publicationLock = new Object();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        PreparePluginPropertiesInterface preparer = new PreparePluginPropertiesInterface() {
            @Override
            public PreparedPluginProperties prepare(Properties incoming, boolean merge,
                    PropertyWriteContext context) {
                entered.countDown();
                synchronized (publicationLock) {
                    assertFalse(closed.get());
                    return new PreparedPluginProperties(new Properties(), NoChange.INSTANCE,
                            (outcome, failure) -> { });
                }
            }
            @Override
            public void close() { closed.set(true); }
        };
        var registration = PluginPropertyPreparers.register("publication", this, preparer);
        registration.activateRecoveryOnly();
        FutureTask<PreparedPluginProperties> prepare = new FutureTask<>(() ->
                PluginPropertyPreparers.prepare("publication", new Properties(), false,
                        PropertyWriteContext.recovery(1)));
        Thread preparing = new Thread(prepare);
        preparing.setDaemon(true);
        FutureTask<Void> close = new FutureTask<>(() -> { registration.close(); return null; });
        Thread closing = new Thread(close);
        closing.setDaemon(true);
        synchronized (publicationLock) {
            preparing.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // Real completion holds the publication lock while another request has
            // already acquired its engine invocation lease and is waiting for that lock.
            registration.activate();
            closing.start();
            assertFalse(closed.get());
        }
        prepare.get(2, TimeUnit.SECONDS);
        close.get(2, TimeUnit.SECONDS);
        assertTrue(closed.get());
        assertUnavailable(() -> PluginPropertyPreparers.prepare("publication", new Properties(),
                false, PropertyWriteContext.pluginApi()));
    }

    @Test(timeout = 10000)
    public void concurrentActivationIsOneShotAndRecoveryAdmissionRemainsValid() throws Exception {
        var workers = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                var registration = PluginPropertyPreparers.register("race", this, noChangePreparer());
                registration.activateRecoveryOnly();
                CyclicBarrier start = new CyclicBarrier(3);
                java.util.concurrent.Callable<Boolean> activate = () -> {
                    start.await(2, TimeUnit.SECONDS);
                    try { registration.activate(); return true; }
                    catch (IllegalStateException alreadyActive) { return false; }
                };
                var first = workers.submit(activate);
                var second = workers.submit(activate);
                var recovery = workers.submit(() -> {
                    start.await(2, TimeUnit.SECONDS);
                    for (int read = 0; read < 100; read++) {
                        assertTrue(PluginPropertyPreparers.isOperational("race", this));
                        PluginPropertyPreparers.prepare("race", new Properties(), false,
                                PropertyWriteContext.recovery(1));
                    }
                    return true;
                });
                assertTrue(first.get(2, TimeUnit.SECONDS) ^ second.get(2, TimeUnit.SECONDS));
                assertTrue(recovery.get(2, TimeUnit.SECONDS));
                registration.close();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static PreparePluginPropertiesInterface noChangePreparer() {
        return (incoming, merge, context) -> new PreparedPluginProperties(
                new Properties(), NoChange.INSTANCE, (outcome, failure) -> { });
    }

    private static void assertUnavailable(ThrowingRunnable runnable) {
        PluginPropertyWriteException failure = assertThrows(
                PluginPropertyWriteException.class, runnable::run);
        assertEquals(PluginPropertyWriteOutcome.PREPARER_UNAVAILABLE, failure.getOutcome());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
