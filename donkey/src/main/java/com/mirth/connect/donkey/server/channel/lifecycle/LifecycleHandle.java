/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** A listener-owned handle that the engine ends exactly once for a started operation. */
public interface LifecycleHandle {
    LifecycleHandle NOOP = result -> {};

    void end(LifecycleResult result);
}
