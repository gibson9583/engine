/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/**
 * Opaque listener-owned state associated with one asynchronous handoff. The engine stores and
 * returns receipts to their creating listener, but never interprets them.
 */
public interface HandoffReceipt {
    HandoffReceipt NOOP = new HandoffReceipt() {};
}
