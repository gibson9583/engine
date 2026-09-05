/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Immutable per-listener, per-callback health counters. */
public final class LifecycleCallbackHealth {
    private final LifecycleCallbackKind callbackKind;
    private final long invocationCount;
    private final long failureCount;
    private final long slowInvocationCount;
    private final long maximumDurationNanos;

    LifecycleCallbackHealth(LifecycleCallbackKind callbackKind, long invocationCount,
            long failureCount, long slowInvocationCount, long maximumDurationNanos) {
        this.callbackKind = callbackKind;
        this.invocationCount = invocationCount;
        this.failureCount = failureCount;
        this.slowInvocationCount = slowInvocationCount;
        this.maximumDurationNanos = maximumDurationNanos;
    }

    public LifecycleCallbackKind getCallbackKind() {
        return callbackKind;
    }

    public long getInvocationCount() {
        return invocationCount;
    }

    public long getFailureCount() {
        return failureCount;
    }

    public long getSlowInvocationCount() {
        return slowInvocationCount;
    }

    public long getMaximumDurationNanos() {
        return maximumDurationNanos;
    }
}
