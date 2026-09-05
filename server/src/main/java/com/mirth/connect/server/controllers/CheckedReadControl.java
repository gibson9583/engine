/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

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
            new CheckedReadControl(Long.MAX_VALUE, NEVER_CANCELLED);

    private final long deadlineNanos;
    private final Cancellation cancellation;

    public CheckedReadControl(long deadlineNanos, Cancellation cancellation) {
        if (deadlineNanos <= 0L) {
            throw new IllegalArgumentException("deadlineNanos must be positive");
        }
        this.deadlineNanos = deadlineNanos;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    public Cancellation getCancellation() {
        return cancellation;
    }

    public long remainingNanos() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    public void checkActive() throws CheckedReadException {
        if (cancellation.isCancelled()) {
            throw new CheckedReadException(CheckedReadException.Reason.CANCELLED);
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            throw new CheckedReadException(CheckedReadException.Reason.TIMEOUT);
        }
    }

    /** Installs both JDBC timeout and cancellation before query execution. */
    public CancellationRegistration arm(Statement statement)
            throws SQLException, CheckedReadException {
        Objects.requireNonNull(statement, "statement");
        checkActive();
        if (deadlineNanos != Long.MAX_VALUE) {
            long remaining = Math.max(1L, deadlineNanos - System.nanoTime());
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
        ScheduledFuture<?> deadlineTask = deadlineNanos == Long.MAX_VALUE ? null
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
