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
