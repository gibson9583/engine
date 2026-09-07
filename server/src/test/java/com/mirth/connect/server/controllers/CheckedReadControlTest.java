/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class CheckedReadControlTest {
    @Test
    public void armInstallsTimeoutAndCancellationBeforeExecution() throws Exception {
        Statement statement = mock(Statement.class);
        TestCancellation cancellation = new TestCancellation();
        CheckedReadControl control = new CheckedReadControl(
                System.nanoTime() + 2_000_000_000L, cancellation);

        CheckedReadControl.CancellationRegistration registration = control.arm(statement);
        verify(statement).setQueryTimeout(intThat(seconds -> seconds >= 1 && seconds <= 2));

        cancellation.cancel();
        verify(statement).cancel();
        CheckedReadException failure = assertThrows(
                CheckedReadException.class, control::checkActive);
        assertEquals(CheckedReadException.Reason.CANCELLED, failure.getReason());
        registration.close();
    }

    @Test
    public void expiredDeadlineIsTypedAndDoesNotArmTheStatement() {
        CheckedReadControl control = new CheckedReadControl(
                System.nanoTime() - 1L, new TestCancellation());
        CheckedReadException failure = assertThrows(
                CheckedReadException.class, () -> control.arm(mock(Statement.class)));
        assertEquals(CheckedReadException.Reason.TIMEOUT, failure.getReason());
    }

    @Test
    public void deadlineSchedulesStatementCancellationAtTheActualNanosecondBoundary()
            throws Exception {
        Statement statement = mock(Statement.class);
        CheckedReadControl control = new CheckedReadControl(
                System.nanoTime() + 100_000_000L, new TestCancellation());
        CheckedReadControl.CancellationRegistration registration = control.arm(statement);

        verify(statement, timeout(1000)).cancel();
        CheckedReadException failure = assertThrows(
                CheckedReadException.class, control::checkActive);
        assertEquals(CheckedReadException.Reason.TIMEOUT, failure.getReason());
        registration.close();
    }

    @Test
    public void signedOriginsZeroAndWrapUseElapsedSubtraction() throws Exception {
        for (long start : new long[] { -100L, -10L, -1L, 0L, Long.MAX_VALUE - 5L, Long.MIN_VALUE + 5L }) {
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(start);
            CheckedReadControl control = new CheckedReadControl(start + 10L, new TestCancellation(), clock::get);
            assertEquals(10L, control.remainingNanos()); control.checkActive();
            clock.set(start + 9L); assertEquals(1L, control.remainingNanos()); control.checkActive();
            clock.set(start + 10L); assertEquals(0L, control.remainingNanos());
            assertEquals(CheckedReadException.Reason.TIMEOUT,
                    assertThrows(CheckedReadException.class, control::checkActive).getReason());
            clock.set(start + 11L); assertEquals(0L, control.remainingNanos());
            assertEquals(CheckedReadException.Reason.TIMEOUT,
                    assertThrows(CheckedReadException.class, control::checkActive).getReason());
        }
    }

    @Test
    public void maximumSignedDeadlineIsBoundedWhileNoneIsExplicitlyUnbounded() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 2_000_000_000L);
        CheckedReadControl control = new CheckedReadControl(Long.MAX_VALUE, new TestCancellation(), clock::get);
        Statement bounded = mock(Statement.class);
        try (var registration = control.arm(bounded)) {
            verify(bounded).setQueryTimeout(2);
            assertEquals(2_000_000_000L, control.remainingNanos());
        }
        clock.set(Long.MAX_VALUE);
        assertEquals(CheckedReadException.Reason.TIMEOUT,
                assertThrows(CheckedReadException.class, control::checkActive).getReason());
        Statement unlimited = mock(Statement.class);
        try (var registration = CheckedReadControl.NONE.arm(unlimited)) {
            CheckedReadControl.NONE.checkActive();
            assertEquals(Long.MAX_VALUE, CheckedReadControl.NONE.remainingNanos());
            org.mockito.Mockito.verifyNoInteractions(unlimited);
        }
    }

    @Test
    public void signedDeadlineCancellationStillPrecedesTimeout() {
        TestCancellation cancellation = new TestCancellation(); cancellation.cancel();
        CheckedReadControl control = new CheckedReadControl(-10L, cancellation, () -> -1L);
        assertEquals(CheckedReadException.Reason.CANCELLED,
                assertThrows(CheckedReadException.class, control::checkActive).getReason());
    }

    @Test
    public void expiredSignedDeadlinesArmNoJdbcWorkAndCancellationDoesNotReadClock() {
        for (long deadline : new long[] { Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE }) {
            TestCancellation cancellation = new TestCancellation();
            CheckedReadControl control = new CheckedReadControl(deadline, cancellation, () -> deadline);
            Statement statement = mock(Statement.class);
            assertEquals(CheckedReadException.Reason.TIMEOUT,
                    assertThrows(CheckedReadException.class, () -> control.arm(statement)).getReason());
            org.mockito.Mockito.verifyNoInteractions(statement);
            org.junit.Assert.assertNull(cancellation.callback.get());
        }
        TestCancellation cancelled = new TestCancellation(); cancelled.cancel();
        CheckedReadControl control = new CheckedReadControl(0L, cancelled,
                () -> { throw new AssertionError("cancelled operation consulted deadline clock"); });
        Statement statement = mock(Statement.class);
        assertEquals(CheckedReadException.Reason.CANCELLED,
                assertThrows(CheckedReadException.class, () -> control.arm(statement)).getReason());
        org.mockito.Mockito.verifyNoInteractions(statement);
    }

    @Test
    public void signedAndWrappedArmingSchedulesActualCancellation() throws Exception {
        for (long initial : new long[] { -1_000_000_000L, -200_000_000L, Long.MAX_VALUE - 100_000_000L }) {
            Statement statement = mock(Statement.class);
            long realStart = System.nanoTime();
            java.util.function.LongSupplier shiftedClock = () -> initial + (System.nanoTime() - realStart);
            CheckedReadControl control = new CheckedReadControl(initial + 200_000_000L,
                    new TestCancellation(), shiftedClock);
            try (var registration = control.arm(statement)) {
                verify(statement).setQueryTimeout(1);
                verify(statement, timeout(2000)).cancel();
                assertEquals(0L, control.remainingNanos());
                assertEquals(CheckedReadException.Reason.TIMEOUT,
                        assertThrows(CheckedReadException.class, control::checkActive).getReason());
            }
        }
    }

    @Test
    public void largestBoundedIntervalClampsJdbcTimeoutWithoutOverflow() throws Exception {
        CheckedReadControl control = new CheckedReadControl(-1L, new TestCancellation(), () -> Long.MIN_VALUE);
        assertEquals(Long.MAX_VALUE, control.remainingNanos());
        Statement statement = mock(Statement.class);
        try (var registration = control.arm(statement)) {
            verify(statement).setQueryTimeout(Integer.MAX_VALUE);
            control.checkActive();
        }
    }

    private static final class TestCancellation implements CheckedReadControl.Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Runnable> callback = new AtomicReference<>();

        @Override
        public CheckedReadControl.CancellationRegistration register(Runnable action) {
            callback.set(action);
            if (cancelled.get()) {
                action.run();
            }
            return () -> callback.compareAndSet(action, null);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        private void cancel() {
            cancelled.set(true);
            Runnable action = callback.get();
            if (action != null) {
                action.run();
            }
        }
    }
}
