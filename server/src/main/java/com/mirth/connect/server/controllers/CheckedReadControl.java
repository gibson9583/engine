/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.LongSupplier;

/** Immutable deadline and cooperative-cancellation contract for a checked read. */
public final class CheckedReadControl {
    private static final ScheduledThreadPoolExecutor DEADLINE_CANCELLER = deadlineCanceller();
    @FunctionalInterface
    public interface CancellationRegistration extends AutoCloseable {
        @Override
        void close();
    }

    public interface Cancellation {
        /** Must invoke a newly registered callback immediately when already cancelled. */
        CancellationRegistration register(Runnable callback);

        boolean isCancelled();
    }

    private static final Cancellation NEVER_CANCELLED = new Cancellation() {
        @Override
        public CancellationRegistration register(Runnable callback) {
            return () -> { };
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    public static final CheckedReadControl NONE =
            new CheckedReadControl(Long.MAX_VALUE, NEVER_CANCELLED, System::nanoTime, true);

    private final long deadlineNanos;
    private final Cancellation cancellation;
    private final LongSupplier nanoTime;
    private final boolean unbounded;

    /**
     * A bounded absolute System.nanoTime deadline. Its signed value may be zero or negative;
     * the interval must be less than 2^63 nanoseconds, as for other nanoTime subtraction.
     * Use NONE explicitly for an unbounded read; Long.MAX_VALUE is a valid bounded deadline.
     */
    public CheckedReadControl(long deadlineNanos, Cancellation cancellation) {
        this(deadlineNanos, cancellation, System::nanoTime, false);
    }

    CheckedReadControl(long deadlineNanos, Cancellation cancellation, LongSupplier nanoTime) {
        this(deadlineNanos, cancellation, nanoTime, false);
    }

    private CheckedReadControl(long deadlineNanos, Cancellation cancellation,
            LongSupplier nanoTime, boolean unbounded) {
        this.deadlineNanos = deadlineNanos;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.unbounded = unbounded;
    }

    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    public Cancellation getCancellation() {
        return cancellation;
    }

    public long remainingNanos() {
        if (unbounded) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadlineNanos - nanoTime.getAsLong());
    }

    public void checkActive() throws CheckedReadException {
        if (cancellation.isCancelled()) {
            throw new CheckedReadException(CheckedReadException.Reason.CANCELLED);
        }
        if (!unbounded && deadlineNanos - nanoTime.getAsLong() <= 0L) {
            throw new CheckedReadException(CheckedReadException.Reason.TIMEOUT);
        }
    }

    /** Installs both JDBC timeout and cancellation before query execution. */
    public CancellationRegistration arm(Statement statement)
            throws SQLException, CheckedReadException {
        Objects.requireNonNull(statement, "statement");
        checkActive();
        if (!unbounded) {
            long remaining = Math.max(1L, deadlineNanos - nanoTime.getAsLong());
            long secondNanos = TimeUnit.SECONDS.toNanos(1L);
            long seconds = Math.max(1L, (remaining - 1L) / secondNanos + 1L);
            statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
        }
        Runnable cancelStatement = () -> {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // The executing thread performs the authoritative typed status check.
            }
        };
        ScheduledFuture<?> deadlineTask = unbounded ? null
                : DEADLINE_CANCELLER.schedule(cancelStatement, remainingNanos(),
                        TimeUnit.NANOSECONDS);
        CancellationRegistration registration = cancellation.register(cancelStatement);
        try {
            checkActive();
            return () -> {
                registration.close();
                if (deadlineTask != null) {
                    deadlineTask.cancel(false);
                }
            };
        } catch (CheckedReadException failure) {
            registration.close();
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
            throw failure;
        }
    }

    private static ScheduledThreadPoolExecutor deadlineCanceller() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "checked-read-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
