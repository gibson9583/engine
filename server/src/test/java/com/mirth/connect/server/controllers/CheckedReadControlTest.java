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
