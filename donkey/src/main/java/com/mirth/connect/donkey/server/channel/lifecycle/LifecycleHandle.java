/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/**
 * A listener-owned handle that the engine ends exactly once for a started operation.
 *
 * <p>The engine calls {@link #end(LifecycleResult)} on the same thread that obtained the handle.
 * Handles returned by listeners are ended in reverse registration order so nested thread-local
 * scopes unwind correctly. Ending must return quickly and obey the same no-I/O, no-controller-
 * re-entry, and thread-safety rules as listener callbacks.</p>
 */
public interface LifecycleHandle {
    LifecycleHandle NOOP = result -> {};

    void end(LifecycleResult result);
}
