/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Ownership token for one exact listener registration. */
public final class LifecycleListenerRegistration {
    private final MessageLifecycleListeners owner;
    private final MessageLifecycleListeners.Registration registration;

    LifecycleListenerRegistration(MessageLifecycleListeners owner,
            MessageLifecycleListeners.Registration registration) {
        this.owner = owner;
        this.registration = registration;
    }

    /** Idempotently unregisters only this exact registration identity. */
    public void unregister() {
        owner.unregister(this);
    }

    public boolean isRegistered() {
        return registration.isActive();
    }

    public boolean isQuarantined() {
        return registration.isQuarantined();
    }

    public int getActiveInvocationCount() {
        return registration.getActiveInvocationCount();
    }

    public int getConsecutiveFailureCount() {
        return registration.getConsecutiveFailureCount();
    }

    public LifecycleCallbackHealth getCallbackHealth(LifecycleCallbackKind callbackKind) {
        return registration.getCallbackHealth(
                Objects.requireNonNull(callbackKind, "callbackKind"));
    }

    MessageLifecycleListeners getOwner() {
        return owner;
    }

    MessageLifecycleListeners.Registration getRegistration() {
        return registration;
    }
}
