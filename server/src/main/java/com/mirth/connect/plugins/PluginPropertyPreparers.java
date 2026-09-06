/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Engine-owned, plugin-point-keyed registry for property preparers. */
public final class PluginPropertyPreparers {
    public enum State {
        INITIALIZING,
        ACTIVE,
        RECOVERY_ONLY,
        CLOSED
    }

    private static final ConcurrentMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private PluginPropertyPreparers() {
    }

    public static PreparerRegistration register(String pluginPoint, Object owner,
            PreparePluginPropertiesInterface preparer) {
        if (pluginPoint == null || pluginPoint.isEmpty()) {
            throw new IllegalArgumentException("pluginPoint is required");
        }
        Entry entry = new Entry(pluginPoint, Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(preparer, "preparer"));
        if (ENTRIES.putIfAbsent(pluginPoint, entry) != null) {
            throw new IllegalStateException("property_preparer_already_registered");
        }
        return new PreparerRegistration(entry);
    }

    public static PreparedPluginProperties prepare(String pluginPoint, Properties incoming,
            boolean merge, PropertyWriteContext context) throws Exception {
        Entry entry = ENTRIES.get(pluginPoint);
        if (entry == null) {
            throw new PluginPropertyWriteException(PluginPropertyWriteOutcome.PREPARER_UNAVAILABLE);
        }
        return entry.prepare(incoming, merge, context);
    }

    /** True only for the exact live owner after initialization or recovery activation. */
    public static boolean isOperational(String pluginPoint, Object owner) {
        Entry entry = ENTRIES.get(pluginPoint);
        if (entry == null || entry.owner != owner) {
            return false;
        }
        entry.lock.readLock().lock();
        try {
            State observed = entry.state;
            return ENTRIES.get(pluginPoint) == entry && entry.owner == owner
                    && (observed == State.ACTIVE || observed == State.RECOVERY_ONLY);
        } finally {
            entry.lock.readLock().unlock();
        }
    }

    static void clearForTest() {
        for (Entry entry : ENTRIES.values()) {
            new PreparerRegistration(entry).close();
        }
        ENTRIES.clear();
    }

    public static final class PreparerRegistration implements AutoCloseable {
        private final Entry entry;

        private PreparerRegistration(Entry entry) {
            this.entry = entry;
        }

        public State getState() {
            return entry.state;
        }

        public void activate() {
            entry.transitionToActive();
        }

        public void activateRecoveryOnly() {
            entry.transition(State.INITIALIZING, State.RECOVERY_ONLY);
        }

        /**
         * Gates new invocations and removes this exact registration without waiting
         * for admitted prepares or invoking the close hook. The caller owns bounded
         * preparer cleanup; admitted callbacks retain their existing invocation lease.
         * Repeated calls, including after replacement, are harmless.
         */
        public void unregister() {
            entry.unregister();
        }

        @Override
        public void close() {
            entry.close();
        }
    }

    private static final class Entry {
        private final String pluginPoint;
        private final Object owner;
        private final PreparePluginPropertiesInterface preparer;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private volatile State state = State.INITIALIZING;

        private Entry(String pluginPoint, Object owner, PreparePluginPropertiesInterface preparer) {
            this.pluginPoint = pluginPoint;
            this.owner = owner;
            this.preparer = preparer;
        }

        private PreparedPluginProperties prepare(Properties incoming, boolean merge,
                PropertyWriteContext context) throws Exception {
            lock.readLock().lock();
            try {
                if (!permits(context)) {
                    throw new PluginPropertyWriteException(
                            PluginPropertyWriteOutcome.PREPARER_UNAVAILABLE);
                }
                return Objects.requireNonNull(preparer.prepare(
                        PreparedPluginProperties.copyStringProperties(incoming), merge, context),
                        "prepared properties");
            } finally {
                lock.readLock().unlock();
            }
        }

        private boolean permits(PropertyWriteContext context) {
            Objects.requireNonNull(context, "context");
            State observed = state;
            if (observed == State.INITIALIZING) {
                return context.getOrigin() == PropertyWriteOrigin.INITIALIZATION
                        && context.getPurpose() == PropertyWritePurpose.NORMAL;
            }
            if (observed == State.ACTIVE) {
                return context.getOrigin() != PropertyWriteOrigin.INITIALIZATION
                        && (context.getPurpose() == PropertyWritePurpose.NORMAL
                                || context.getOrigin() == PropertyWriteOrigin.PLUGIN_API
                                && context.getPurpose() == PropertyWritePurpose.RECOVERY);
            }
            return observed == State.RECOVERY_ONLY
                    && context.getOrigin() == PropertyWriteOrigin.PLUGIN_API
                    && context.getPurpose() == PropertyWritePurpose.RECOVERY;
        }

        // State changes use this entry's monitor, separately from invocation ownership.
        // Activation can follow reconciliation inside prepare(), which already holds a
        // read lease. Taking the write lease here would attempt an unsupported lock
        // upgrade; an external activation could also wait behind a preparer that is
        // waiting for the plugin's publication lock. Activation only changes admission
        // for future calls. Close still waits for existing invocations before closing
        // the preparer, and serializes its terminal state with these transitions.
        private synchronized void transition(State expected, State target) {
            if (state != expected || ENTRIES.get(pluginPoint) != this) {
                throw new IllegalStateException("invalid_property_preparer_transition");
            }
            state = target;
        }

        private synchronized void transitionToActive() {
            if ((state != State.INITIALIZING && state != State.RECOVERY_ONLY)
                    || ENTRIES.get(pluginPoint) != this) {
                throw new IllegalStateException("invalid_property_preparer_transition");
            }
            state = State.ACTIVE;
        }

        private synchronized void unregister() {
            state = State.CLOSED;
            ENTRIES.remove(pluginPoint, this);
        }

        private void close() {
            lock.writeLock().lock();
            try {
                synchronized (this) {
                    if (state == State.CLOSED) {
                        return;
                    }
                    state = State.CLOSED;
                    ENTRIES.remove(pluginPoint, this);
                }
                try {
                    preparer.close();
                } catch (Exception e) {
                    throw new IllegalStateException("property_preparer_close_failed", e);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        @SuppressWarnings("unused")
        private Object owner() {
            return owner;
        }
    }
}
