/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Copy-on-write lifecycle listener registry and fault-isolating notifier.
 *
 * Listener installation is privileged in-process code execution. Callbacks run on message-engine
 * threads and must return quickly, avoid I/O and controller re-entry, and remain thread-safe.
 */
public final class MessageLifecycleListeners {
    public static final long DEFAULT_SLOW_CALLBACK_THRESHOLD_NANOS =
            TimeUnit.MILLISECONDS.toNanos(10);
    public static final long DEFAULT_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    public static final int DEFAULT_QUARANTINE_THRESHOLD = 100;

    static final Registration[] EMPTY_REGISTRATIONS = new Registration[0];
    static final HandoffEntry[] EMPTY_HANDOFF_ENTRIES = new HandoffEntry[0];
    private static final Logger LOGGER = LogManager.getLogger(MessageLifecycleListeners.class);

    private final LongSupplier nanoClock;
    private final long slowCallbackThresholdNanos;
    private final long warningIntervalNanos;
    private final int quarantineThreshold;
    private final IdentityHashMap<MessageLifecycleListener, Boolean> retiringListeners =
            new IdentityHashMap<MessageLifecycleListener, Boolean>();
    private final AtomicLong nextMessageIncarnationId = new AtomicLong(1L);
    private final AtomicBoolean incarnationExhaustionReported = new AtomicBoolean(false);
    private volatile Registration[] registrations = EMPTY_REGISTRATIONS;

    public MessageLifecycleListeners() {
        this(System::nanoTime, DEFAULT_SLOW_CALLBACK_THRESHOLD_NANOS,
                DEFAULT_WARNING_INTERVAL_NANOS, DEFAULT_QUARANTINE_THRESHOLD);
    }

    MessageLifecycleListeners(LongSupplier nanoClock, long slowCallbackThresholdNanos,
            long warningIntervalNanos, int quarantineThreshold) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (slowCallbackThresholdNanos < 0) {
            throw new IllegalArgumentException("slowCallbackThresholdNanos must not be negative");
        }
        if (warningIntervalNanos <= 0) {
            throw new IllegalArgumentException("warningIntervalNanos must be positive");
        }
        if (quarantineThreshold <= 0) {
            throw new IllegalArgumentException("quarantineThreshold must be positive");
        }
        this.slowCallbackThresholdNanos = slowCallbackThresholdNanos;
        this.warningIntervalNanos = warningIntervalNanos;
        this.quarantineThreshold = quarantineThreshold;
    }

    /** Registers by listener identity, preserving registration order. */
    public synchronized LifecycleListenerRegistration register(
            MessageLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (retiringListeners.containsKey(listener)) {
            throw new IllegalStateException("listener identity is still being abandoned");
        }
        for (Registration existing : registrations) {
            if (existing.listener == listener && existing.isActive()) {
                throw new IllegalStateException("listener identity is already registered");
            }
        }

        Registration registration = new Registration(listener);
        Registration[] updated = Arrays.copyOf(registrations, registrations.length + 1);
        updated[registrations.length] = registration;
        registrations = updated;
        return new LifecycleListenerRegistration(this, registration);
    }

    /** Idempotently unregisters the exact ownership token and never waits for leased callbacks. */
    public void unregister(LifecycleListenerRegistration ownership) {
        Objects.requireNonNull(ownership, "ownership");
        if (ownership.getOwner() != this) {
            throw new IllegalArgumentException("registration belongs to another registry");
        }
        Registration registration = ownership.getRegistration();
        if (beginTermination(registration, Registration.UNREGISTERED)) {
            Throwable fatal;
            try {
                fatal = invokeAbandonment(registration, HandoffAbandonReason.UNREGISTERED);
            } finally {
                finishTermination(registration);
            }
            if (fatal != null) {
                rethrowFatal(fatal);
            }
        }
    }

    /** Performs exactly one volatile-array read. */
    public LifecycleDispatchToken captureToken() {
        Registration[] snapshot = registrations;
        return snapshot.length == 0 ? LifecycleDispatchToken.EMPTY
                : new LifecycleDispatchToken(this, snapshot);
    }

    public int getRegisteredListenerCount() {
        return registrations.length;
    }

    /** Allocates correlation identity only for a non-empty captured token. */
    public long allocateMessageIncarnationId(LifecycleDispatchToken token) {
        validateToken(token);
        if (token.isEmpty()) {
            return 0L;
        }

        while (true) {
            long candidate = nextMessageIncarnationId.get();
            if (candidate <= 0L || candidate == Long.MAX_VALUE) {
                if (incarnationExhaustionReported.compareAndSet(false, true)) {
                    LOGGER.error("Message lifecycle incarnation identifiers are exhausted; "
                            + "new message correlation is disabled for this engine run.");
                }
                return 0L;
            }
            if (nextMessageIncarnationId.compareAndSet(candidate, candidate + 1L)) {
                return candidate;
            }
        }
    }

    public LifecycleHandle onDispatchStart(LifecycleDispatchToken token, DispatchInfo dispatch) {
        return start(token, LifecycleCallbackKind.DISPATCH_START,
                Objects.requireNonNull(dispatch, "dispatch"), null);
    }

    public void onSourceMessageCreated(LifecycleDispatchToken token, MessageInfo source) {
        notifyInstant(token, LifecycleCallbackKind.SOURCE_MESSAGE_CREATED,
                Objects.requireNonNull(source, "source"));
    }

    public LifecycleHandle onProcessStart(LifecycleDispatchToken token, ProcessInfo process) {
        return start(token, LifecycleCallbackKind.PROCESS_START,
                Objects.requireNonNull(process, "process"), null);
    }

    public LifecycleHandle onProcessStart(ProcessInfo process, HandoffBundle handoff) {
        return startFromHandoff(LifecycleCallbackKind.PROCESS_START,
                Objects.requireNonNull(process, "process"), handoff);
    }

    public LifecycleHandle onFilterTransformerStart(LifecycleDispatchToken token,
            MessageInfo message) {
        return start(token, LifecycleCallbackKind.FILTER_TRANSFORMER_START,
                Objects.requireNonNull(message, "message"), null);
    }

    public LifecycleHandle onDestinationChainStart(LifecycleDispatchToken token,
            ChainInfo chain) {
        return start(token, LifecycleCallbackKind.DESTINATION_CHAIN_START,
                Objects.requireNonNull(chain, "chain"), null);
    }

    public LifecycleHandle onDestinationChainStart(ChainInfo chain, HandoffBundle handoff) {
        return startFromHandoff(LifecycleCallbackKind.DESTINATION_CHAIN_START,
                Objects.requireNonNull(chain, "chain"), handoff);
    }

    public LifecycleHandle onDestinationQueueStart(LifecycleDispatchToken token, QueueInfo queue) {
        return start(token, LifecycleCallbackKind.DESTINATION_QUEUE_START,
                Objects.requireNonNull(queue, "queue"), null);
    }

    public LifecycleHandle onDestinationQueueStart(QueueInfo queue, HandoffBundle handoff) {
        return startFromHandoff(LifecycleCallbackKind.DESTINATION_QUEUE_START,
                Objects.requireNonNull(queue, "queue"), handoff);
    }

    public LifecycleHandle onSendStart(LifecycleDispatchToken token, SendInfo send) {
        return start(token, LifecycleCallbackKind.SEND_START,
                Objects.requireNonNull(send, "send"), null);
    }

    public void onStatusChanged(LifecycleDispatchToken token, StatusChangeInfo change) {
        notifyInstant(token, LifecycleCallbackKind.STATUS_CHANGED,
                Objects.requireNonNull(change, "change"));
    }

    public HandoffBundle createHandoffs(LifecycleDispatchToken token, HandoffInfo handoff) {
        validateToken(token);
        Objects.requireNonNull(handoff, "handoff");
        if (token.isEmpty()) {
            return HandoffBundle.EMPTY;
        }

        Registration[] captured = token.getRegistrations();
        HandoffEntry[] entries = new HandoffEntry[captured.length];
        int count = 0;
        for (Registration registration : captured) {
            if (!registration.tryAcquireLease()) {
                continue;
            }
            long started = nanoClock.getAsLong();
            HandoffReceipt receipt = HandoffReceipt.NOOP;
            Throwable callbackFailure = null;
            try {
                receipt = registration.listener.onHandoffCreated(handoff);
            } catch (Throwable failure) {
                callbackFailure = failure;
            }
            try {
                Throwable abandonmentFatal = recordCompletion(registration,
                        LifecycleCallbackKind.HANDOFF_CREATED, started, callbackFailure, true);
                Throwable fatal = resolveFatal(callbackFailure, abandonmentFatal);
                if (fatal != null) {
                    cancelCreatedEntries(entries, count, handoff, fatal);
                    rethrowFatal(fatal);
                    return HandoffBundle.EMPTY;
                }
                if (callbackFailure != null) {
                    receipt = HandoffReceipt.NOOP;
                }
                if (receipt == null) {
                    receipt = HandoffReceipt.NOOP;
                }
                if (!registration.isActive()) {
                    if (receipt != HandoffReceipt.NOOP) {
                        Throwable cancellationFatal = invokeCancellationWhileLeased(registration,
                                new HandoffCancellation(handoff,
                                        HandoffCancellationReason.TRANSFER_FAILED), receipt);
                        if (cancellationFatal != null) {
                            cancelCreatedEntries(entries, count, handoff, cancellationFatal);
                            rethrowFatal(cancellationFatal);
                            return HandoffBundle.EMPTY;
                        }
                    }
                    continue;
                }
                entries[count++] = new HandoffEntry(registration, receipt);
            } finally {
                registration.releaseLease();
            }
        }
        return new HandoffBundle(this, token, handoff, Arrays.copyOf(entries, count));
    }

    /** Claims and cancels a bundle once. Repeated cancellation or prior consumption is a no-op. */
    public void cancelHandoffs(HandoffBundle bundle, HandoffCancellationReason reason) {
        deliverHandoffCancellations(claimHandoffsForCancellation(bundle, reason));
    }

    /**
     * Claims cancellation without invoking listener code, allowing a queue to claim while holding
     * its monitor and deliver only after unlocking.
     */
    public HandoffCancellationBatch claimHandoffsForCancellation(HandoffBundle bundle,
            HandoffCancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        validateBundle(bundle);
        HandoffEntry[] entries = bundle.claimForCancellation();
        if (entries == null || entries.length == 0) {
            return HandoffCancellationBatch.EMPTY;
        }
        return new HandoffCancellationBatch(this, entries,
                new HandoffCancellation(bundle.getHandoff(), reason));
    }

    /** Idempotently delivers a previously claimed cancellation batch. */
    public void deliverHandoffCancellations(HandoffCancellationBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!batch.belongsTo(this)) {
            throw new IllegalArgumentException("cancellation batch belongs to another registry");
        }
        HandoffEntry[] entries = batch.claimForDelivery();
        if (entries == null) {
            return;
        }
        HandoffCancellation cancellation = batch.getCancellation();
        Throwable firstFatal = null;
        for (HandoffEntry entry : entries) {
            Throwable fatal = invokeCancellation(entry, cancellation);
            if (fatal != null) {
                if (firstFatal == null) {
                    firstFatal = fatal;
                } else {
                    addSuppressed(firstFatal, fatal);
                }
            }
        }
        if (firstFatal != null) {
            rethrowFatal(firstFatal);
        }
    }

    private LifecycleHandle startFromHandoff(LifecycleCallbackKind kind, Object info,
            HandoffBundle bundle) {
        validateBundle(bundle);
        HandoffEntry[] entries = bundle.claimForConsumption();
        if (entries == null) {
            return start(bundle.getToken(), kind, info, null);
        }
        return start(bundle.getToken(), kind, info, entries);
    }

    private LifecycleHandle start(LifecycleDispatchToken token, LifecycleCallbackKind kind,
            Object info, HandoffEntry[] handoffEntries) {
        validateToken(token);
        if (token.isEmpty()) {
            return LifecycleHandle.NOOP;
        }

        Registration[] captured = token.getRegistrations();
        HandleEntry[] handles = new HandleEntry[captured.length];
        int count = 0;
        for (Registration registration : captured) {
            HandoffReceipt receipt = findReceipt(registration, handoffEntries);
            if (handoffEntries != null && receipt == null) {
                continue;
            }
            if (receipt == null) {
                receipt = HandoffReceipt.NOOP;
            }
            if (!registration.tryAcquireLease()) {
                continue;
            }

            long started = nanoClock.getAsLong();
            LifecycleHandle handle = null;
            Throwable callbackFailure = null;
            try {
                handle = invokeStart(registration.listener, kind, info, receipt);
            } catch (Throwable failure) {
                callbackFailure = failure;
            }
            try {
                Throwable abandonmentFatal = recordCompletion(registration, kind, started,
                        callbackFailure, true);
                Throwable fatal = resolveFatal(callbackFailure, abandonmentFatal);
                if (fatal != null) {
                    unwindFatalStart(handles, count, fatal);
                    rethrowFatal(fatal);
                    return LifecycleHandle.NOOP;
                }
                if (callbackFailure != null) {
                    continue;
                }
            } finally {
                registration.releaseLease();
            }
            if (handle != null && handle != LifecycleHandle.NOOP) {
                handles[count++] = new HandleEntry(registration, handle);
            }
        }
        if (count == 0) {
            return LifecycleHandle.NOOP;
        }
        return new AggregateHandle(Arrays.copyOf(handles, count));
    }

    private void notifyInstant(LifecycleDispatchToken token, LifecycleCallbackKind kind,
            Object info) {
        validateToken(token);
        if (token.isEmpty()) {
            return;
        }
        for (Registration registration : token.getRegistrations()) {
            if (!registration.tryAcquireLease()) {
                continue;
            }
            long started = nanoClock.getAsLong();
            Throwable callbackFailure = null;
            try {
                invokeInstant(registration.listener, kind, info);
            } catch (Throwable failure) {
                callbackFailure = failure;
            }
            try {
                Throwable abandonmentFatal = recordCompletion(registration, kind, started,
                        callbackFailure, true);
                Throwable fatal = resolveFatal(callbackFailure, abandonmentFatal);
                if (fatal != null) {
                    rethrowFatal(fatal);
                }
            } finally {
                registration.releaseLease();
            }
        }
    }

    private LifecycleHandle invokeStart(MessageLifecycleListener listener,
            LifecycleCallbackKind kind, Object info, HandoffReceipt receipt) {
        switch (kind) {
            case DISPATCH_START:
                return listener.onDispatchStart((DispatchInfo) info);
            case PROCESS_START:
                return listener.onProcessStart((ProcessInfo) info, receipt);
            case FILTER_TRANSFORMER_START:
                return listener.onFilterTransformerStart((MessageInfo) info);
            case DESTINATION_CHAIN_START:
                return listener.onDestinationChainStart((ChainInfo) info, receipt);
            case DESTINATION_QUEUE_START:
                return listener.onDestinationQueueStart((QueueInfo) info, receipt);
            case SEND_START:
                return listener.onSendStart((SendInfo) info);
            default:
                throw new IllegalArgumentException("callback kind is not a start: " + kind);
        }
    }

    private void invokeInstant(MessageLifecycleListener listener, LifecycleCallbackKind kind,
            Object info) {
        switch (kind) {
            case SOURCE_MESSAGE_CREATED:
                listener.onSourceMessageCreated((MessageInfo) info);
                break;
            case STATUS_CHANGED:
                listener.onStatusChanged((StatusChangeInfo) info);
                break;
            default:
                throw new IllegalArgumentException("callback kind is not instantaneous: " + kind);
        }
    }

    private Throwable invokeCancellation(HandoffEntry entry, HandoffCancellation cancellation) {
        Registration registration = entry.registration;
        if (!registration.tryAcquireLease()) {
            return null;
        }
        try {
            return invokeCancellationWhileLeased(registration, cancellation, entry.receipt);
        } finally {
            registration.releaseLease();
        }
    }

    private Throwable invokeCancellationWhileLeased(Registration registration,
            HandoffCancellation cancellation, HandoffReceipt receipt) {
        long started = nanoClock.getAsLong();
        Throwable callbackFailure = null;
        try {
            registration.listener.onHandoffCancelled(cancellation, receipt);
        } catch (Throwable failure) {
            callbackFailure = failure;
        }
        Throwable abandonmentFatal = recordCompletion(registration,
                LifecycleCallbackKind.HANDOFF_CANCELLED, started, callbackFailure, true);
        return resolveFatal(callbackFailure, abandonmentFatal);
    }

    private Throwable invokeAbandonment(Registration registration, HandoffAbandonReason reason) {
        long started = nanoClock.getAsLong();
        Throwable callbackFailure = null;
        try {
            registration.listener.onHandoffsAbandoned(reason);
        } catch (Throwable failure) {
            callbackFailure = failure;
        }
        recordCompletion(registration, LifecycleCallbackKind.HANDOFFS_ABANDONED, started,
                callbackFailure, false);
        return resolveFatal(callbackFailure, null);
    }

    private Throwable recordCompletion(Registration registration, LifecycleCallbackKind kind,
            long started, Throwable failure, boolean canQuarantine) {
        long elapsed = nanoClock.getAsLong() - started;
        if (elapsed < 0) {
            elapsed = 0;
        }
        boolean slow = elapsed > slowCallbackThresholdNanos;
        boolean quarantine = registration.record(kind, elapsed, slow, failure != null,
                canQuarantine && !isFatal(failure) ? quarantineThreshold : Integer.MAX_VALUE);
        if (failure != null || slow) {
            warnRateLimited(registration, kind, elapsed, failure, slow);
        }
        if (quarantine && beginTermination(registration, Registration.QUARANTINED)) {
            try {
                return invokeAbandonment(registration, HandoffAbandonReason.QUARANTINED);
            } finally {
                finishTermination(registration);
            }
        }
        return null;
    }

    private void warnRateLimited(Registration registration, LifecycleCallbackKind kind,
            long elapsed, Throwable failure, boolean slow) {
        long now = nanoClock.getAsLong();
        if (!registration.claimWarning(kind, now, warningIntervalNanos)) {
            return;
        }
        String listenerClass = registration.listener.getClass().getName();
        if (failure != null) {
            LOGGER.warn("Message lifecycle listener {} callback {} failed with {} after {} ns",
                    listenerClass, kind, failure.getClass().getName(), elapsed);
        } else if (slow) {
            LOGGER.warn("Message lifecycle listener {} callback {} was slow ({} ns)",
                    listenerClass, kind, elapsed);
        }
    }

    private void unwindFatalStart(HandleEntry[] handles, int count, Throwable original) {
        LifecycleResult result = new LifecycleResult(LifecycleOutcome.ERROR, null, null,
                new FailureInfo(FailureCategory.UNKNOWN, original.getClass().getName()));
        for (int i = count - 1; i >= 0; i--) {
            Throwable failure = endHandle(handles[i], result);
            if (failure != null) {
                addSuppressed(original, failure);
            }
        }
    }

    private void cancelCreatedEntries(HandoffEntry[] entries, int count, HandoffInfo handoff,
            Throwable original) {
        HandoffCancellation cancellation = new HandoffCancellation(handoff,
                HandoffCancellationReason.TRANSFER_FAILED);
        for (int i = count - 1; i >= 0; i--) {
            Throwable failure = invokeCancellation(entries[i], cancellation);
            if (failure != null) {
                addSuppressed(original, failure);
            }
        }
    }

    private Throwable endHandle(HandleEntry entry, LifecycleResult result) {
        long started = nanoClock.getAsLong();
        Throwable callbackFailure = null;
        try {
            entry.handle.end(result);
        } catch (Throwable failure) {
            callbackFailure = failure;
        }
        Throwable abandonmentFatal = recordCompletion(entry.registration,
                LifecycleCallbackKind.HANDLE_END, started, callbackFailure, true);
        return resolveFatal(callbackFailure, abandonmentFatal);
    }

    private HandoffReceipt findReceipt(Registration registration, HandoffEntry[] entries) {
        if (entries == null) {
            return null;
        }
        for (HandoffEntry entry : entries) {
            if (entry.registration == registration) {
                return entry.receipt;
            }
        }
        return null;
    }

    private synchronized boolean beginTermination(Registration registration, int terminalState) {
        if (!registration.transitionTo(terminalState)) {
            return false;
        }
        Registration[] current = registrations;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == registration) {
                Registration[] updated = new Registration[current.length - 1];
                System.arraycopy(current, 0, updated, 0, i);
                System.arraycopy(current, i + 1, updated, i, current.length - i - 1);
                registrations = updated.length == 0 ? EMPTY_REGISTRATIONS : updated;
                break;
            }
        }
        retiringListeners.put(registration.listener, Boolean.TRUE);
        return true;
    }

    private synchronized void finishTermination(Registration registration) {
        retiringListeners.remove(registration.listener);
    }

    private void validateToken(LifecycleDispatchToken token) {
        Objects.requireNonNull(token, "token");
        if (!token.belongsTo(this)) {
            throw new IllegalArgumentException("dispatch token belongs to another registry");
        }
    }

    private void validateBundle(HandoffBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        if (!bundle.belongsTo(this)) {
            throw new IllegalArgumentException("handoff bundle belongs to another registry");
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        throw (ThreadDeath) failure;
    }

    /**
     * Restores interruption and combines fatal failures without discarding one. Completion is
     * recorded before this method runs, so a fatal raised by quarantine abandonment is primary;
     * a fatal discovered later while inspecting the original callback failure is suppressed.
     */
    private static Throwable resolveFatal(Throwable callbackFailure,
            Throwable abandonmentFatal) {
        Throwable inspectionFatal = restoreInterrupt(callbackFailure);
        if (abandonmentFatal != null) {
            if (callbackFailure != null) {
                addSuppressed(abandonmentFatal, callbackFailure);
            }
            if (inspectionFatal != null && inspectionFatal != callbackFailure) {
                addSuppressed(abandonmentFatal, inspectionFatal);
            }
            return abandonmentFatal;
        }
        return inspectionFatal;
    }

    /**
     * Restores interruption discovered in a callback failure's cause chain. If hostile cause
     * inspection itself raises a fatal JVM error, returns it to the caller so the surrounding
     * lifecycle operation can perform its required best-effort cleanup before rethrowing.
     */
    private static Throwable restoreInterrupt(Throwable failure) {
        if (failure == null) {
            return null;
        }
        if (isFatal(failure)) {
            return failure;
        }
        IdentityHashMap<Throwable, Boolean> inspected =
                new IdentityHashMap<Throwable, Boolean>();
        Throwable current = failure;
        while (current != null && inspected.put(current, Boolean.TRUE) == null) {
            if (isFatal(current)) {
                return current;
            }
            if (current instanceof InterruptedException
                    || current instanceof InterruptedIOException
                    || current instanceof ClosedByInterruptException) {
                Thread.currentThread().interrupt();
            }
            Throwable next;
            try {
                next = current.getCause();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                return fatal;
            } catch (Throwable inspectionFailure) {
                current = inspectionFailure;
                continue;
            }
            current = next;
        }
        return null;
    }

    private static void addSuppressed(Throwable target, Throwable suppressed) {
        if (target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (RuntimeException ignored) {
            // Preserve the primary fatal error even if suppression itself is unavailable.
        }
    }

    static final class Registration {
        static final int ACTIVE = 0;
        static final int UNREGISTERED = 1;
        static final int QUARANTINED = 2;
        private static final long NO_WARNING = Long.MIN_VALUE;

        private final MessageLifecycleListener listener;
        private final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final AtomicInteger activeInvocations = new AtomicInteger();
        private final CallbackStats[] callbackStats;
        private int consecutiveFailures;

        Registration(MessageLifecycleListener listener) {
            this.listener = listener;
            LifecycleCallbackKind[] kinds = LifecycleCallbackKind.values();
            callbackStats = new CallbackStats[kinds.length];
            for (int i = 0; i < kinds.length; i++) {
                callbackStats[i] = new CallbackStats();
            }
        }

        boolean isActive() {
            return state.get() == ACTIVE;
        }

        boolean isQuarantined() {
            return state.get() == QUARANTINED;
        }

        boolean transitionTo(int terminalState) {
            return state.compareAndSet(ACTIVE, terminalState);
        }

        boolean tryAcquireLease() {
            if (!isActive()) {
                return false;
            }
            activeInvocations.incrementAndGet();
            if (isActive()) {
                return true;
            }
            activeInvocations.decrementAndGet();
            return false;
        }

        void releaseLease() {
            activeInvocations.decrementAndGet();
        }

        int getActiveInvocationCount() {
            return activeInvocations.get();
        }

        synchronized boolean record(LifecycleCallbackKind kind, long elapsed, boolean slow,
                boolean failed, int threshold) {
            CallbackStats stats = callbackStats[kind.ordinal()];
            stats.invocationCount++;
            if (failed) {
                stats.failureCount++;
            }
            // Abandonment runs only after the registration is terminal. Its outcome is useful
            // health data, but must not erase or extend the failure streak that caused quarantine.
            if (kind != LifecycleCallbackKind.HANDOFFS_ABANDONED) {
                if (failed) {
                    if (consecutiveFailures < Integer.MAX_VALUE) {
                        consecutiveFailures++;
                    }
                } else {
                    consecutiveFailures = 0;
                }
            }
            if (slow) {
                stats.slowInvocationCount++;
            }
            if (elapsed > stats.maximumDurationNanos) {
                stats.maximumDurationNanos = elapsed;
            }
            return kind != LifecycleCallbackKind.HANDOFFS_ABANDONED && failed
                    && consecutiveFailures >= threshold && isActive();
        }

        synchronized int getConsecutiveFailureCount() {
            return consecutiveFailures;
        }

        synchronized LifecycleCallbackHealth getCallbackHealth(LifecycleCallbackKind kind) {
            CallbackStats stats = callbackStats[kind.ordinal()];
            return new LifecycleCallbackHealth(kind, stats.invocationCount, stats.failureCount,
                    stats.slowInvocationCount, stats.maximumDurationNanos);
        }

        synchronized boolean claimWarning(LifecycleCallbackKind kind, long now, long interval) {
            CallbackStats stats = callbackStats[kind.ordinal()];
            if (stats.lastWarningNanos != NO_WARNING
                    && now - stats.lastWarningNanos < interval) {
                return false;
            }
            stats.lastWarningNanos = now;
            return true;
        }
    }

    private static final class CallbackStats {
        private long invocationCount;
        private long failureCount;
        private long slowInvocationCount;
        private long maximumDurationNanos;
        private long lastWarningNanos = Registration.NO_WARNING;
    }

    static final class HandoffEntry {
        private final Registration registration;
        private final HandoffReceipt receipt;

        HandoffEntry(Registration registration, HandoffReceipt receipt) {
            this.registration = registration;
            this.receipt = receipt;
        }
    }

    private final class AggregateHandle implements LifecycleHandle {
        private final HandleEntry[] handles;
        private final AtomicBoolean ended = new AtomicBoolean();

        private AggregateHandle(HandleEntry[] handles) {
            this.handles = handles;
        }

        @Override
        public void end(LifecycleResult result) {
            Objects.requireNonNull(result, "result");
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            Throwable firstFatal = null;
            for (int i = handles.length - 1; i >= 0; i--) {
                Throwable failure = endHandle(handles[i], result);
                if (failure != null) {
                    if (firstFatal == null) {
                        firstFatal = failure;
                    } else {
                        addSuppressed(firstFatal, failure);
                    }
                }
            }
            if (firstFatal != null) {
                rethrowFatal(firstFatal);
            }
        }
    }

    private static final class HandleEntry {
        private final Registration registration;
        private final LifecycleHandle handle;

        private HandleEntry(Registration registration, LifecycleHandle handle) {
            this.registration = registration;
            this.handle = handle;
        }
    }
}
