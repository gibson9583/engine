/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

import com.mirth.connect.donkey.model.message.Status;

/** Immutable terminal snapshot supplied to a listener-owned lifecycle handle. */
public final class LifecycleResult {
    private final LifecycleOutcome outcome;
    private final MessageInfo finalMessage;
    private final Status responseStatus;
    private final FailureInfo failure;

    public LifecycleResult(LifecycleOutcome outcome, MessageInfo finalMessage,
            Status responseStatus, FailureInfo failure) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        if (responseStatus == Status.ERROR && outcome != LifecycleOutcome.ERROR) {
            throw new IllegalArgumentException("an ERROR response requires an ERROR outcome");
        }
        this.finalMessage = finalMessage;
        this.responseStatus = responseStatus;
        this.failure = failure;
    }

    public LifecycleOutcome getOutcome() {
        return outcome;
    }

    public MessageInfo getFinalMessage() {
        return finalMessage;
    }

    /** A send response status, or null when no non-null response returned. */
    public Status getResponseStatus() {
        return responseStatus;
    }

    public FailureInfo getFailure() {
        return failure;
    }
}
