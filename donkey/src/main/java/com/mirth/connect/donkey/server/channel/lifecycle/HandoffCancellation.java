/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Immutable cancellation of a previously announced handoff. */
public final class HandoffCancellation {
    private final HandoffInfo handoff;
    private final HandoffCancellationReason reason;

    public HandoffCancellation(HandoffInfo handoff, HandoffCancellationReason reason) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public HandoffInfo getHandoff() {
        return handoff;
    }

    public HandoffCancellationReason getReason() {
        return reason;
    }
}
