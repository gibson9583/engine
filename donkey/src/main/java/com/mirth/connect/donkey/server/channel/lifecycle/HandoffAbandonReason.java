/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Closed registration-level reasons for abandoning every outstanding listener handoff. */
public enum HandoffAbandonReason {
    UNREGISTERED,
    QUARANTINED
}
