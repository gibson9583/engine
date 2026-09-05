/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque engine carrier separating an exact cancellation claim from listener delivery. Queue code
 * may retain this batch while unlocking; delivery remains idempotent.
 */
public final class HandoffCancellationBatch {
    static final HandoffCancellationBatch EMPTY = new HandoffCancellationBatch(null,
            MessageLifecycleListeners.EMPTY_HANDOFF_ENTRIES, null, true);

    private final MessageLifecycleListeners owner;
    private final MessageLifecycleListeners.HandoffEntry[] entries;
    private final HandoffCancellation cancellation;
    private final AtomicBoolean delivered;

    HandoffCancellationBatch(MessageLifecycleListeners owner,
            MessageLifecycleListeners.HandoffEntry[] entries,
            HandoffCancellation cancellation) {
        this(owner, entries, cancellation, false);
    }

    private HandoffCancellationBatch(MessageLifecycleListeners owner,
            MessageLifecycleListeners.HandoffEntry[] entries,
            HandoffCancellation cancellation, boolean initiallyDelivered) {
        this.owner = owner;
        this.entries = entries;
        this.cancellation = cancellation;
        delivered = new AtomicBoolean(initiallyDelivered);
    }

    public boolean isEmpty() {
        return entries.length == 0;
    }

    boolean belongsTo(MessageLifecycleListeners listeners) {
        return owner == null || owner == listeners;
    }

    MessageLifecycleListeners.HandoffEntry[] claimForDelivery() {
        return delivered.compareAndSet(false, true) ? entries : null;
    }

    HandoffCancellation getCancellation() {
        return cancellation;
    }
}
