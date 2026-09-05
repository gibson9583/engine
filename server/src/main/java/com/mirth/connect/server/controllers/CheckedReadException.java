/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import com.mirth.connect.client.core.ControllerException;

/** Typed timeout or cancellation from a controlled checked read. */
public final class CheckedReadException extends ControllerException {
    public enum Reason {
        TIMEOUT,
        CANCELLED
    }

    private final Reason reason;

    public CheckedReadException(Reason reason) {
        super(reason == Reason.TIMEOUT ? "checked_read_timeout" : "checked_read_cancelled");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
