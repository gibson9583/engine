/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed terminal outcomes for a lifecycle handle. */
public enum LifecycleOutcome {
    SUCCESS,
    FILTERED,
    ERROR,
    CANCELLED,
    INTERRUPTED,
    ROLLED_BACK
}
