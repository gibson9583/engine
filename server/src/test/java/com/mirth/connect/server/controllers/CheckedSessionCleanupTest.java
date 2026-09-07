/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ibatis.session.*;
import org.junit.Test;
import com.mirth.connect.plugins.*;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.model.PropertyWriteProtection;
import com.mirth.connect.server.ExtensionLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.mirth.connect.server.mybatis.KeyValuePair;
import com.mirth.connect.server.util.StatementLock;

public class CheckedSessionCleanupTest {
    @Test public void eachCheckedPathUnlocksAfterEveryOrdinaryAndFatalSessionClose()throws Exception{
        for(int operation=0;operation<3;operation++)for(int closeKind=0;closeKind<4;closeKind++){
            try(Fixture f=new Fixture()){
                int selected=operation;
                Throwable close=failure(closeKind);if(close!=null)doThrow(close).when(f.session).close();
                if(close==null)f.invoke(selected);else assertSame(close,assertThrows(Throwable.class,()->f.invoke(selected)));
                f.assertUnlocked();verify(f.session,times(1)).close();
            }
        }
    }

    @Test public void operationFailureKeepsFirstFatalAndStillClosesAndUnlocks()throws Exception{
        for(int operation=0;operation<3;operation++)for(int primaryKind=1;primaryKind<4;primaryKind++)for(int closeKind=0;closeKind<4;closeKind++){
            try(Fixture f=new Fixture()){
                int selected=operation;
                Throwable primary=failure(primaryKind),close=failure(closeKind);
                if(operation==2)doThrow(primary).when(f.session).selectOne(anyString(),any());
                else doThrow(primary).when(f.session).getConnection();
                if(close!=null)doThrow(close).when(f.session).close();
                Throwable actual=assertThrows(Throwable.class,()->f.invoke(selected));
                if(primary instanceof Error)assertSame(primary,actual);
                else if(close instanceof Error)assertSame(close,actual);
                else assertSame(primary,actual.getCause());
                f.assertUnlocked();verify(f.session,times(1)).close();
            }
        }
    }

    @Test public void failedSessionOpenAlsoReleasesAcquiredReadAndWriteLocks()throws Exception{
        for(int operation=0;operation<3;operation++){
            try(Fixture f=new Fixture()){
                int selected=operation;
                Error original=new OutOfMemoryError("open");doThrow(original).when(f.sessions).openSession(anyBoolean());
                assertSame(original,assertThrows(Throwable.class,()->f.invoke(selected)));f.assertUnlocked();
                verify(f.session,never()).close();
            }
        }
    }

    @Test public void cancellationFailureAfterActualReadLockClaimCannotStrandTheLock()throws Exception{
        for(int kind=1;kind<4;kind++)try(Fixture f=new Fixture()){
            Throwable original=failure(kind);java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
            CheckedReadControl control=new CheckedReadControl(System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1),new CheckedReadControl.Cancellation(){
                public boolean isCancelled(){
                    if(checks.incrementAndGet()==2){if(original instanceof Error error)throw error;throw (RuntimeException)original;}
                    return false;
                }
                public CheckedReadControl.CancellationRegistration register(Runnable callback){throw new AssertionError("not reached");}
            });
            assertSame(original,assertThrows(Throwable.class,()->f.controller.readPropertyChecked("otel","policy.v1",control)));
            f.assertUnlocked();verify(f.sessions,never()).openSession(anyBoolean());
        }
    }

    @Test public void verificationFailureKeepsFatalThroughBothSessionClosures()throws Exception{
        for(int kind=2;kind<4;kind++)try(Fixture f=new Fixture()){
            SqlSession verification=mock(SqlSession.class);when(f.sessions.openSession(true)).thenReturn(verification);
            doThrow(new IllegalStateException("commit uncertain")).when(f.session).commit();
            Throwable fatal=failure(kind);doThrow(fatal).when(verification).getConnection();
            doThrow(new IllegalStateException("verification close")).when(verification).close();
            doThrow(new IllegalStateException("write close")).when(f.session).close();
            assertSame(fatal,assertThrows(Throwable.class,()->f.invoke(2)));f.assertUnlocked();
            verify(verification,times(1)).close();verify(f.session,times(1)).close();
        }
    }

    @Test public void ordinaryJdbcReadFailureCannotHideFatalResourceClose() throws Exception {
        for (int operation = 0; operation < 2; operation++) for (int resource = 0; resource < 2; resource++) {
            for (int kind = 2; kind < 4; kind++) try (Fixture f = new Fixture()) {
                int selected = operation; Throwable fatal = failure(kind);
                doThrow(new SQLException("ordinary query failure")).when(f.rows).next();
                if (resource == 0) doThrow(fatal).when(f.rows).close(); else doThrow(fatal).when(f.sql).close();
                Throwable actual = assertThrows(Throwable.class, () -> f.invoke(selected));
                f.assertUnlocked(); verify(f.session, times(1)).close();
                assertSame("first fatal JDBC cleanup must remain the propagated failure", fatal, actual);
            }
        }
    }

    @Test public void verificationJdbcCleanupFatalCannotBecomeUnknownOutcome() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlSession verification = mock(SqlSession.class);
            when(f.sessions.openSession(true)).thenReturn(verification);
            when(verification.getConnection()).thenReturn(f.connection);
            doThrow(new IllegalStateException("ambiguous commit")).when(f.session).commit();
            doThrow(new SQLException("ordinary verification failure")).when(f.rows).next();
            Error fatal = new OutOfMemoryError("verification resource cleanup");
            doThrow(fatal).when(f.sql).close();
            AtomicReference<AtomicPropertyWriteOutcome> outcome = new AtomicReference<>();
            Throwable actual = null;
            try { outcome.set(f.controller.compareAndSetPropertyAtomically("otel", "policy.v1", ExpectedPropertyValue.present("old"), "new")); }
            catch (Throwable failure) { actual = failure; }
            f.assertUnlocked(); verify(verification, times(1)).close(); verify(f.session, times(1)).close();
            assertSame("fatal JDBC cleanup must not be converted to " + outcome.get(), fatal, actual);
        }
    }

    @Test public void successfulCommitWithOrdinarySessionCloseFailureMustNotCompleteFailed() throws Exception {
        try (Fixture f = new Fixture()) {
            doThrow(new IllegalStateException("session close after commit")).when(f.session).close();
            List<PluginPropertyCompletion> completions = new ArrayList<>();
            DefaultExtensionController extension = extension(f.controller);
            try (var registration = register(completions)) {
                registration.activate();
                try { extension.setPluginProperties("checked-cleanup-review", new Properties(), false, PropertyWriteContext.pluginApi()); }
                catch (Exception acceptedCleanupFailure) { /* Completion must still describe proven persistence. */ }
                verify(f.session, times(1)).commit(); verify(f.session, times(1)).close(); f.assertUnlocked();
                assertEquals("normal commit return must not be reported as failed persistence", List.of(PluginPropertyCompletion.COMMITTED), completions);
            }
        }
    }

    @Test public void fatalBeforeCommitMustDischargePreparedCompletionExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            Error fatal = new OutOfMemoryError("read before commit");
            doThrow(fatal).when(f.session).selectOne(anyString(), any());
            List<PluginPropertyCompletion> completions = new ArrayList<>();
            DefaultExtensionController extension = extension(f.controller);
            try (var registration = register(completions)) {
                registration.activate();
                assertSame(fatal, assertThrows(Error.class, () -> extension.setPluginProperties("checked-cleanup-review", new Properties(), false, PropertyWriteContext.pluginApi())));
                verify(f.session, never()).commit(); verify(f.session, times(1)).close(); f.assertUnlocked();
                assertEquals("prepared owner must receive completion even when persistence raises Error", List.of(PluginPropertyCompletion.FAILED), completions);
            }
        }
    }

    @Test(timeout = 10000) public void blockedCloseRetainsRealLockUntilActualExitOnEveryPath() throws Exception {
        for (int operation = 0; operation < 3; operation++) try (Fixture f = new Fixture()) {
            int selected = operation;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> result = new AtomicReference<>();
            doAnswer(call -> { entered.countDown(); for (;;) { try { release.await(); break; } catch (InterruptedException ignored) { } } return null; }).when(f.session).close();
            Thread worker = new Thread(() -> { try { f.invoke(selected); } catch (Throwable failure) { result.set(failure); } });
            try {
                worker.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); worker.interrupt();
                assertTrue(worker.isAlive());
                if (operation == 2) assertTrue(f.lock.isWriteLocked()); else assertEquals(1, f.lock.getReadLockCount());
                verify(f.session, times(1)).close();
            } finally { release.countDown(); worker.join(1000); assertFalse(worker.isAlive()); }
            assertNull(result.get()); f.assertUnlocked();
        }
    }

    @Test public void unlockFailureAfterActualReleasePreservesFirstFatalAcrossAllCheckedPaths() throws Exception {
        for (int operation = 0; operation < 3; operation++) for (int firstFatalStage = 0; firstFatalStage < 4; firstFatalStage++) {
            try (Fixture f = new Fixture()) {
                int selected = operation;
                Throwable primary = failure(firstFatalStage == 0 ? 2 : 1);
                Throwable close = failure(firstFatalStage == 1 ? 3 : 1);
                Throwable unlock = failure(firstFatalStage == 2 ? 2 : 1);
                if (operation == 2) doThrow(primary).when(f.session).selectOne(anyString(), any());
                else doThrow(primary).when(f.session).getConnection();
                doThrow(close).when(f.session).close();
                StatementLock lock = spy(f.instances.get(DefaultConfigurationController.VACUUM_LOCK_STATEMENT_ID));
                f.instances.put(DefaultConfigurationController.VACUUM_LOCK_STATEMENT_ID, lock);
                java.util.concurrent.atomic.AtomicInteger unlocks = new java.util.concurrent.atomic.AtomicInteger();
                org.mockito.stubbing.Answer<Object> failAfterUnlock = invocation -> {
                    invocation.callRealMethod(); unlocks.incrementAndGet(); throw unlock;
                };
                doAnswer(failAfterUnlock).when(lock).readUnlock(); doAnswer(failAfterUnlock).when(lock).writeUnlock();
                Throwable actual = assertThrows(Throwable.class, () -> f.invoke(selected));
                f.assertUnlocked(); assertEquals(1, unlocks.get()); verify(f.session, times(1)).close();
                if (firstFatalStage == 0) assertSame(primary, actual);
                else if (firstFatalStage == 1) assertSame(close, actual);
                else if (firstFatalStage == 2) assertSame(unlock, actual);
                else assertSame(primary, actual.getCause());
            }
        }
    }

    @Test public void suppressionMetadataFailureCannotReplaceAnEarlierFatalOrPreventUnlock() throws Exception {
        for (int operation = 0; operation < 3; operation++) try (Fixture f = new Fixture()) {
            int selected = operation;
            Error original = spy(new OutOfMemoryError("first fatal"));
            Error metadata = new ThreadDeath();
            doThrow(metadata).when(original).addSuppressed(any(Throwable.class));
            if (operation == 2) doThrow(original).when(f.session).selectOne(anyString(), any());
            else doThrow(original).when(f.session).getConnection();
            doThrow(new IllegalStateException("ordinary session close")).when(f.session).close();
            assertSame(original, assertThrows(Throwable.class, () -> f.invoke(selected)));
            f.assertUnlocked(); verify(f.session, times(1)).close();
        }
    }

    @Test public void commitEvidencePrecedesEveryVerificationCleanupAndDrivesExactCompletion() throws Exception {
        for (String observed : List.of("new", "old", "different", "unavailable"))
            for (int resource = 0; resource < 3; resource++) for (int kind = 2; kind < 4; kind++)
                try (Fixture f = new Fixture()) {
                    SqlSession verification = mock(SqlSession.class);
                    when(f.sessions.openSession(true)).thenReturn(verification);
                    when(verification.getConnection()).thenReturn(f.connection);
                    doThrow(new IllegalStateException("uncertain commit")).when(f.session).commit();
                    if (observed.equals("unavailable")) doThrow(new SQLException("verification unavailable")).when(f.rows).next();
                    else { when(f.rows.next()).thenReturn(true); when(f.rows.getString(1)).thenReturn(observed); }
                    Throwable fatal = failure(kind);
                    if (resource == 0) doThrow(fatal).when(f.rows).close();
                    else if (resource == 1) doThrow(fatal).when(f.sql).close();
                    else doThrow(fatal).when(verification).close();
                    List<PluginPropertyCompletion> completions = new ArrayList<>();
                    try (var registration = register(completions)) {
                        registration.activate();
                        assertSame(fatal, assertThrows(Error.class, () -> extension(f.controller).setPluginProperties(
                                "checked-cleanup-review", new Properties(), false, PropertyWriteContext.pluginApi())));
                        PluginPropertyCompletion expected = observed.equals("new") ? PluginPropertyCompletion.COMMITTED
                                : observed.equals("old") ? PluginPropertyCompletion.FAILED
                                : observed.equals("different") ? PluginPropertyCompletion.CONFLICT : PluginPropertyCompletion.OUTCOME_UNKNOWN;
                        assertEquals(List.of(expected), completions);
                    }
                    f.assertUnlocked(); verify(f.rows, times(1)).close(); verify(f.sql, times(1)).close();
                    verify(verification, times(1)).close(); verify(f.session, times(1)).close();
                }
    }

    @Test public void fatalCommitIsUnknownAndFirstFatalSurvivesRollbackCloseAndCompletionFailures() throws Exception {
        for (boolean duringCommit : List.of(false, true)) for (int kind = 2; kind < 4; kind++)
            try (Fixture f = new Fixture()) {
                Throwable original = failure(kind), later = failure(kind == 2 ? 3 : 2);
                if (duringCommit) doThrow(original).when(f.session).commit();
                else doThrow(original).when(f.session).selectOne(anyString(), any());
                doThrow(later).when(f.session).rollback(); doThrow(later).when(f.session).close();
                List<PluginPropertyCompletion> outcomes = new ArrayList<>(); List<Throwable> failures = new ArrayList<>();
                try (var registration = PluginPropertyPreparers.register("checked-cleanup-review", outcomes, (incoming, merge, context) -> {
                    Properties values = new Properties(); values.setProperty("policy.v1", "new");
                    return new PreparedPluginProperties(values, new CheckedCompareAndSet("policy.v1", ExpectedPropertyValue.present("old")),
                            (outcome, failure) -> { outcomes.add(outcome); failures.add(failure); throw (Error) later; });
                })) {
                    registration.activate();
                    assertSame(original, assertThrows(Error.class, () -> extension(f.controller).setPluginProperties(
                            "checked-cleanup-review", new Properties(), false, PropertyWriteContext.pluginApi())));
                    assertEquals(List.of(duringCommit ? PluginPropertyCompletion.OUTCOME_UNKNOWN : PluginPropertyCompletion.FAILED), outcomes);
                    if (duringCommit) assertNull(failures.get(0)); else assertSame(original, failures.get(0));
                }
                verify(f.session, times(1)).rollback(); verify(f.session, times(1)).close();
                verify(f.sessions, never()).openSession(true); f.assertUnlocked();
            }
    }

    @Test public void receiptCannotBeReusedAndOldOnlyControllersCannotPerformProtectedWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            CheckedPropertyWriteReceipt receipt = new CheckedPropertyWriteReceipt();
            assertEquals(AtomicPropertyWriteOutcome.COMMITTED, f.controller.compareAndSetPropertyAtomically(
                    "otel", "policy.v1", ExpectedPropertyValue.present("old"), "new", receipt));
            assertEquals(CheckedPropertyWriteReceipt.State.COMMITTED, receipt.state());
            receipt.notCommitted(); receipt.conflict(); receipt.commitAttempted();
            assertEquals(CheckedPropertyWriteReceipt.State.COMMITTED, receipt.state());
            assertThrows(IllegalStateException.class, () -> f.controller.compareAndSetPropertyAtomically(
                    "otel", "policy.v1", ExpectedPropertyValue.present("old"), "new", receipt));
            verify(f.sessions, times(1)).openSession(false); f.assertUnlocked();
        }
        ConfigurationController oldOnly = mock(ConfigurationController.class, CALLS_REAL_METHODS);
        doReturn(AtomicPropertyWriteOutcome.COMMITTED).when(oldOnly).compareAndSetPropertyAtomically(any(), any(), any(), any());
        List<PluginPropertyCompletion> completions = new ArrayList<>();
        try (var registration = register(completions)) {
            registration.activate();
            assertThrows(com.mirth.connect.client.core.ControllerException.class, () -> extension(oldOnly).setPluginProperties(
                    "checked-cleanup-review", new Properties(), false, PropertyWriteContext.pluginApi()));
            assertEquals(List.of(PluginPropertyCompletion.FAILED), completions);
            verify(oldOnly, never()).compareAndSetPropertyAtomically(any(), any(), any(), any());
        }
    }

    @Test public void jdbcCleanupTimeoutRetainsTypedReadFailureOnBothSurfaces() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (int operation = 0; operation < 2; operation++) for (int resource = 0; resource < 2; resource++)
            try (Fixture f = new Fixture()) {
                int selected = operation;
                SQLTimeoutException timeout = new SQLTimeoutException("foreign cleanup timeout");
                if (resource == 0) doThrow(timeout).when(f.rows).close(); else doThrow(timeout).when(f.sql).close();
                Throwable actual = assertThrows(Throwable.class, () -> f.invoke(selected));
                f.assertUnlocked(); verify(f.rows, times(1)).close(); verify(f.sql, times(1)).close(); verify(f.session, times(1)).close();
                if (!(actual instanceof CheckedReadException checked) || checked.getReason() != CheckedReadException.Reason.TIMEOUT)
                    mismatches.add(operation + "/" + resource + ": " + actual.getClass().getSimpleName());
            }
        assertEquals("JDBC cleanup remains part of the typed checked-read operation", List.of(), mismatches);
    }

    @Test public void jdbcCleanupRuntimeFailureRetainsContentSafeReadBoundary() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (int operation = 0; operation < 2; operation++) for (int resource = 0; resource < 2; resource++)
            try (Fixture f = new Fixture()) {
                int selected = operation;
                RuntimeException foreign = new IllegalStateException("foreign database credentials");
                if (resource == 0) doThrow(foreign).when(f.rows).close(); else doThrow(foreign).when(f.sql).close();
                Throwable actual = assertThrows(Throwable.class, () -> f.invoke(selected));
                f.assertUnlocked(); verify(f.rows, times(1)).close(); verify(f.sql, times(1)).close(); verify(f.session, times(1)).close();
                String safe = operation == 0 ? "checked_property_read_failed" : "checked_property_group_read_failed";
                if (!(actual instanceof com.mirth.connect.client.core.ControllerException) || !safe.equals(actual.getMessage()))
                    mismatches.add(operation + "/" + resource + ": " + actual.getClass().getSimpleName());
            }
        assertEquals("foreign cleanup errors must not escape the existing safe checked-read boundary", List.of(), mismatches);
    }

    @Test public void responseAllocationAndCompletionFailuresCannotRedeliverOrDowngradeCommit() throws Exception {
        for (int kind = 2; kind < 4; kind++) for (boolean callbackFails : List.of(false, true))
            try (Fixture f = new Fixture()) {
                Error fatal = (Error) failure(kind);
                List<PluginPropertyCompletion> outcomes = new ArrayList<>();
                try (var registration = PluginPropertyPreparers.register("checked-cleanup-review", outcomes, (incoming, merge, context) -> {
                    Properties values = new Properties(); values.setProperty("policy.v1", "new");
                    return new PreparedPluginProperties(values, new CheckedCompareAndSet("policy.v1", ExpectedPropertyValue.present("old")),
                            (outcome, failure) -> { outcomes.add(outcome); assertNull(failure); if (callbackFails) throw fatal; });
                }); var results = mockStatic(PluginPropertyWriteResult.class, CALLS_REAL_METHODS)) {
                    results.when(() -> PluginPropertyWriteResult.withProperties(eq(PluginPropertyWriteOutcome.COMMITTED), any(Properties.class))).thenThrow(fatal);
                    registration.activate();
                    assertSame(fatal, assertThrows(Error.class, () -> extension(f.controller).setPluginProperties(
                            "checked-cleanup-review", new Properties(), false, PropertyWriteContext.pluginApi())));
                    assertEquals(List.of(PluginPropertyCompletion.COMMITTED), outcomes);
                    verify(f.session, times(1)).commit(); verify(f.session, times(1)).close(); f.assertUnlocked();
                }
            }
    }

    @Test(timeout = 10000) public void concurrentReceiptReplayCannotEnterAnotherTransaction() throws Exception {
        try (Fixture f = new Fixture()) {
            CheckedPropertyWriteReceipt receipt = new CheckedPropertyWriteReceipt();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            doAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; }).when(f.session).commit();
            Thread owner = new Thread(() -> {
                try { assertEquals(AtomicPropertyWriteOutcome.COMMITTED, f.controller.compareAndSetPropertyAtomically(
                        "otel", "policy.v1", ExpectedPropertyValue.present("old"), "new", receipt)); }
                catch (Throwable caught) { failure.set(caught); }
            });
            try {
                owner.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(CheckedPropertyWriteReceipt.State.OUTCOME_UNKNOWN, receipt.state());
                assertThrows(IllegalStateException.class, () -> f.controller.compareAndSetPropertyAtomically(
                        "otel", "policy.v1", ExpectedPropertyValue.present("old"), "new", receipt));
                verify(f.sessions, times(1)).openSession(false); assertTrue(f.lock.isWriteLocked());
            } finally { release.countDown(); owner.join(3000); assertFalse(owner.isAlive()); }
            assertNull(failure.get()); assertEquals(CheckedPropertyWriteReceipt.State.COMMITTED, receipt.state()); f.assertUnlocked();
        }
    }

    @Test public void nonWritingDirectivesRetainExactOnceCompletionOnFatalCallbacks() throws Exception {
        for (boolean preserve : List.of(false, true)) for (int kind = 2; kind < 4; kind++) try (Fixture f = new Fixture()) {
            Error fatal = (Error) failure(kind); List<PluginPropertyCompletion> outcomes = new ArrayList<>();
            try (var registration = PluginPropertyPreparers.register("checked-cleanup-review", outcomes, (incoming, merge, context) ->
                    new PreparedPluginProperties(new Properties(), preserve ? new PreserveRejected("invalid", "policy.v1", "stored", true) : NoChange.INSTANCE,
                            (outcome, failure) -> { outcomes.add(outcome); assertNull(failure); throw fatal; }))) {
                if (!preserve) registration.activate();
                assertSame(fatal, assertThrows(Error.class, () -> extension(f.controller).setPluginProperties(
                        "checked-cleanup-review", new Properties(), false, preserve ? PropertyWriteContext.initialization() : PropertyWriteContext.pluginApi())));
                assertEquals(List.of(preserve ? PluginPropertyCompletion.PRESERVED_REJECTED : PluginPropertyCompletion.NO_CHANGE), outcomes);
                verifyNoInteractions(f.sessions); f.assertUnlocked();
            }
        }
    }

    @Test public void cancellationOrDeadlineDuringCleanupRetainsActionTimeFailureWithEarlierSqlError() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (int operation = 0; operation < 2; operation++) for (boolean bodyFails : List.of(false, true))
            for (boolean cancelled : List.of(false, true)) for (int resource = 0; resource < 2; resource++) try (Fixture f = new Fixture()) {
                java.util.concurrent.atomic.AtomicBoolean cancellation = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
                CheckedReadControl control = new CheckedReadControl(TimeUnit.SECONDS.toNanos(5), new CheckedReadControl.Cancellation() {
                    public boolean isCancelled() { return cancellation.get(); }
                    public CheckedReadControl.CancellationRegistration register(Runnable callback) { return () -> {}; }
                }, clock::get);
                if (bodyFails) doThrow(new SQLException("earlier SQL error")).when(f.rows).next();
                org.mockito.stubbing.Answer<Object> close = call -> {
                    if (cancelled) cancellation.set(true); else clock.set(TimeUnit.SECONDS.toNanos(6));
                    throw new SQLException("ordinary cleanup error");
                };
                if (resource == 0) doAnswer(close).when(f.rows).close(); else doAnswer(close).when(f.sql).close();
                int selected = operation;
                Throwable actual = assertThrows(Throwable.class, () -> {
                    if (selected == 0) f.controller.readPropertyChecked("otel", "policy.v1", control);
                    else f.controller.readPropertiesForGroupChecked("otel", control);
                });
                f.assertUnlocked(); verify(f.rows, times(1)).close(); verify(f.sql, times(1)).close(); verify(f.session, times(1)).close();
                assertTrue(control.isCancellationComplete());
                CheckedReadException.Reason expected = cancelled ? CheckedReadException.Reason.CANCELLED : CheckedReadException.Reason.TIMEOUT;
                if (!(actual instanceof CheckedReadException typed) || typed.getReason() != expected)
                    mismatches.add(operation + "/body=" + bodyFails + "/" + expected + "/resource=" + resource + ":" + actual.getClass().getSimpleName());
            }
        assertEquals("controlled failure must be checked after the owned JDBC cleanup, as before", List.of(), mismatches);
    }

    @Test public void cleanupTranslationPreservesEarlierSqlErrorAndAlwaysPromotesLaterSessionFatal() throws Exception {
        for (int operation = 0; operation < 2; operation++) for (int kind = 0; kind < 4; kind++) try (Fixture f = new Fixture()) {
            int selected = operation; SQLException original = new SQLException("first SQL failure");
            if (kind == 0) doThrow(original).when(f.rows).next();
            doThrow(new SQLTimeoutException("later close timeout")).when(f.rows).close();
            Throwable sessionFailure = kind < 2 ? null : failure(kind);
            if (sessionFailure != null) doThrow(sessionFailure).when(f.session).close();
            Throwable actual = assertThrows(Throwable.class, () -> f.invoke(selected));
            if (kind == 0) assertSame(original, actual.getCause());
            else if (kind == 1) assertEquals(CheckedReadException.Reason.TIMEOUT, ((CheckedReadException) actual).getReason());
            else assertSame(sessionFailure, actual);
            f.assertUnlocked(); verify(f.rows, times(1)).close(); verify(f.sql, times(1)).close(); verify(f.session, times(1)).close();
        }
    }

    @Test public void fatalControlledTranslationAfterJdbcCleanupStillClosesSessionAndUnlocks() throws Exception {
        for (int operation = 0; operation < 2; operation++) for (int kind = 2; kind < 4; kind++) try (Fixture f = new Fixture()) {
            Error first = (Error) failure(kind), later = (Error) failure(kind == 2 ? 3 : 2);
            java.util.concurrent.atomic.AtomicBoolean translating = new java.util.concurrent.atomic.AtomicBoolean();
            CheckedReadControl control = new CheckedReadControl(System.nanoTime() + TimeUnit.SECONDS.toNanos(5), new CheckedReadControl.Cancellation() {
                public boolean isCancelled() { if (translating.get()) throw first; return false; }
                public CheckedReadControl.CancellationRegistration register(Runnable callback) { return () -> {}; }
            });
            doAnswer(call -> { translating.set(true); throw new SQLException("ordinary JDBC close"); }).when(f.rows).close();
            doThrow(later).when(f.session).close();
            int selected = operation;
            assertSame(first, assertThrows(Error.class, () -> {
                if (selected == 0) f.controller.readPropertyChecked("otel", "policy.v1", control);
                else f.controller.readPropertiesForGroupChecked("otel", control);
            }));
            verify(f.rows, times(1)).close(); verify(f.sql, times(1)).close(); verify(f.session, times(1)).close();
            assertTrue(control.isCancellationComplete()); f.assertUnlocked();
        }
    }

    private static DefaultExtensionController extension(ConfigurationController configuration) {
        ExtensionLoader loader = mock(ExtensionLoader.class);
        PluginMetaData metadata = new PluginMetaData(); metadata.setName("checked-cleanup-review");
        metadata.setPropertyWriteProtection(PropertyWriteProtection.PREPARED_ONLY);
        when(loader.getPluginMetaData()).thenReturn(Map.of("checked-cleanup-review", metadata));
        return new DefaultExtensionController(configuration, loader);
    }

    private static PluginPropertyPreparers.PreparerRegistration register(List<PluginPropertyCompletion> completions) {
        return PluginPropertyPreparers.register("checked-cleanup-review", completions, (incoming, merge, context) -> {
            Properties canonical = new Properties(); canonical.setProperty("policy.v1", "new");
            return new PreparedPluginProperties(canonical, new CheckedCompareAndSet("policy.v1", ExpectedPropertyValue.present("old")),
                    (outcome, failure) -> {
                        assertEquals(outcome == PluginPropertyCompletion.FAILED, failure != null);
                        completions.add(outcome);
                    });
        });
    }

    private static Throwable failure(int kind){return switch(kind){case 0->null;case 1->new IllegalStateException("cleanup");case 2->new OutOfMemoryError("cleanup");default->new ThreadDeath();};}
    private static final class Fixture implements AutoCloseable {
        final SqlSession session=mock(SqlSession.class);final SqlSessionManager sessions=mock(SqlSessionManager.class);
        final Connection connection=mock(Connection.class);final PreparedStatement sql=mock(PreparedStatement.class);final ResultSet rows=mock(ResultSet.class);
        final DefaultConfigurationController controller;final ReentrantReadWriteLock lock;
        final Map<String,StatementLock> instances;final StatementLock prior;
        @SuppressWarnings("unchecked") Fixture()throws Exception{
            Constructor<StatementLock> c=StatementLock.class.getDeclaredConstructor(boolean.class);c.setAccessible(true);
            StatementLock statement=c.newInstance(true);Field lockField=StatementLock.class.getDeclaredField("vacuumLock");lockField.setAccessible(true);
            lock=(ReentrantReadWriteLock)lockField.get(statement);
            Field registry=StatementLock.class.getDeclaredField("instances");registry.setAccessible(true);instances=(Map<String,StatementLock>)registry.get(null);
            prior=instances.put(DefaultConfigurationController.VACUUM_LOCK_STATEMENT_ID,statement);
            when(sessions.openSession(anyBoolean())).thenReturn(session);
            when(session.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(sql);when(sql.executeQuery()).thenReturn(rows);
            when(session.selectOne(anyString(),any())).thenReturn(new KeyValuePair("policy.v1","old"));when(session.update(anyString(),any())).thenReturn(1);
            controller=new DefaultConfigurationController(()->sessions,()->sessions,true);
        }
        void invoke(int operation)throws Exception{
            if(operation==0)controller.readPropertyChecked("otel","policy.v1");
            else if(operation==1)controller.readPropertiesForGroupChecked("otel");
            else assertEquals(AtomicPropertyWriteOutcome.COMMITTED,controller.compareAndSetPropertyAtomically("otel","policy.v1",ExpectedPropertyValue.present("old"),"new"));
        }
        void assertUnlocked(){assertEquals(0,lock.getReadLockCount());assertFalse(lock.isWriteLocked());}
        public void close(){
            while(lock.getReadHoldCount()>0)lock.readLock().unlock();while(lock.isWriteLockedByCurrentThread())lock.writeLock().unlock();
            if(prior==null)instances.remove(DefaultConfigurationController.VACUUM_LOCK_STATEMENT_ID);else instances.put(DefaultConfigurationController.VACUUM_LOCK_STATEMENT_ID,prior);
        }
    }
}
