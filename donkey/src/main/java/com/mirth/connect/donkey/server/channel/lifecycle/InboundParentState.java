/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Result of inspecting an inbound trace parent without retaining the original header. */
public enum InboundParentState {
    ABSENT,
    VALID,
    INVALID
}
