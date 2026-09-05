/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opaque engine carrier for per-listener handoff receipts. Exactly one consumer or cancellation
 * may claim a non-empty bundle.
 */
public final class HandoffBundle {
    static final int OPEN = 0;
    static final int CONSUMED = 1;
    static final int CANCELLED = 2;
    static final HandoffBundle EMPTY = new HandoffBundle(null, LifecycleDispatchToken.EMPTY, null,
            MessageLifecycleListeners.EMPTY_HANDOFF_ENTRIES, CONSUMED);

    private final MessageLifecycleListeners owner;
    private final LifecycleDispatchToken token;
    private final HandoffInfo handoff;
    private final MessageLifecycleListeners.HandoffEntry[] entries;
    private final AtomicInteger state;

    HandoffBundle(MessageLifecycleListeners owner, LifecycleDispatchToken token,
            HandoffInfo handoff, MessageLifecycleListeners.HandoffEntry[] entries) {
        this(owner, token, handoff, entries, OPEN);
    }

    private HandoffBundle(MessageLifecycleListeners owner, LifecycleDispatchToken token,
            HandoffInfo handoff, MessageLifecycleListeners.HandoffEntry[] entries,
            int initialState) {
        this.owner = owner;
        this.token = token;
        this.handoff = handoff;
        this.entries = entries;
        state = new AtomicInteger(initialState);
    }

    public boolean isEmpty() {
        return entries.length == 0;
    }

    boolean belongsTo(MessageLifecycleListeners listeners) {
        return owner == null || owner == listeners;
    }

    LifecycleDispatchToken getToken() {
        return token;
    }

    HandoffInfo getHandoff() {
        return handoff;
    }

    MessageLifecycleListeners.HandoffEntry[] claimForConsumption() {
        return state.compareAndSet(OPEN, CONSUMED) ? entries : null;
    }

    MessageLifecycleListeners.HandoffEntry[] claimForCancellation() {
        return state.compareAndSet(OPEN, CANCELLED) ? entries : null;
    }
}
