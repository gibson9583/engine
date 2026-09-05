/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/**
 * Immutable registration-identity snapshot captured once for a message. The engine may copy this
 * exact token to derived in-memory carriers but cannot inspect its listener entries.
 */
public final class LifecycleDispatchToken {
    static final LifecycleDispatchToken EMPTY = new LifecycleDispatchToken(null,
            MessageLifecycleListeners.EMPTY_REGISTRATIONS);

    private final MessageLifecycleListeners owner;
    private final MessageLifecycleListeners.Registration[] registrations;

    LifecycleDispatchToken(MessageLifecycleListeners owner,
            MessageLifecycleListeners.Registration[] registrations) {
        this.owner = owner;
        this.registrations = registrations;
    }

    public boolean isEmpty() {
        return registrations.length == 0;
    }

    boolean belongsTo(MessageLifecycleListeners listeners) {
        return owner == null || owner == listeners;
    }

    MessageLifecycleListeners.Registration[] getRegistrations() {
        return registrations;
    }
}
