/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong cancellationDuties = new AtomicLong();
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

    /**
     * Pure terminal proof for cancellation duties armed by this control. This is not read
     * completion: callers must first establish that their read can no longer arm a statement.
     * A cancelled scheduled future alone does not make an executing JDBC cancel terminal.
     */
    public boolean isCancellationComplete() {
        return cancellationDuties.get() == 0L;
    }

    /** Installs both JDBC timeout and cancellation before query execution. */
    public CancellationRegistration arm(Statement statement)
            throws SQLException, CheckedReadException {
        Objects.requireNonNull(statement, "statement");
        checkActive();
        CancellationArm arm = new CancellationArm(statement);
        try {
            if (!unbounded) {
                long remaining = Math.max(1L, deadlineNanos - nanoTime.getAsLong());
                long secondNanos = TimeUnit.SECONDS.toNanos(1L);
                long seconds = Math.max(1L, (remaining - 1L) / secondNanos + 1L);
                statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
                arm.deadlineTask = DEADLINE_CANCELLER.schedule(arm, remainingNanos(),
                        TimeUnit.NANOSECONDS);
            }
            arm.registration = Objects.requireNonNull(cancellation.register(arm), "cancellationRegistration");
            checkActive();
            return arm;
        } catch (SQLException | CheckedReadException | RuntimeException | Error failure) {
            try { arm.close(); }
            catch (RuntimeException | Error cleanup) {
                Throwable selected = combine(failure, cleanup);
                if (selected instanceof Error error) throw error;
            }
            throw failure;
        }
    }

    /** Preallocated before scheduling/registration; both cancellation paths share one claim. */
    private final class CancellationArm implements Runnable, CancellationRegistration {
        private final AtomicInteger invocation = new AtomicInteger(); // 0 ready, 1 claimed, 2 terminal
        private final AtomicBoolean closeClaimed = new AtomicBoolean();
        private volatile Statement statement;
        private volatile ScheduledFuture<?> deadlineTask;
        private volatile CancellationRegistration registration;

        private CancellationArm(Statement statement) {
            this.statement = statement;
            cancellationDuties.incrementAndGet();
        }

        @Override
        public void run() {
            if (!invocation.compareAndSet(0, 1)) return;
            Statement selected = statement;
            statement = null;
            try {
                selected.cancel();
            } catch (SQLException ignored) {
                // The read thread performs the authoritative typed cancellation check.
            } finally {
                invocation.set(2);
                cancellationDuties.decrementAndGet();
            }
        }

        @Override
        public void close() {
            if (!closeClaimed.compareAndSet(false, true)) return;
            // Prevent a queued or retained callback from starting foreign work even if
            // scheduling/registration failed after accepting it without returning a handle.
            if (invocation.compareAndSet(0, 2)) {
                statement = null;
                cancellationDuties.decrementAndGet();
            }
            Throwable failure = null;
            try { if (deadlineTask != null) deadlineTask.cancel(false); }
            catch (RuntimeException | Error cleanup) { failure = cleanup; }
            try { if (registration != null) registration.close(); }
            catch (RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
        }
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null || first == next) return next;
        boolean firstFatal = first instanceof VirtualMachineError || first instanceof ThreadDeath;
        boolean nextFatal = next instanceof VirtualMachineError || next instanceof ThreadDeath;
        Throwable primary = nextFatal && !firstFatal ? next : first;
        Throwable secondary = primary == first ? next : first;
        try { primary.addSuppressed(secondary); }
        catch (RuntimeException | Error metadata) {
            if (!firstFatal && !nextFatal
                    && (metadata instanceof VirtualMachineError || metadata instanceof ThreadDeath)) return metadata;
        }
        return primary;
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
