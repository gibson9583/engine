/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

import com.mirth.connect.donkey.model.message.Status;

/**
 * A runtime status transition. Notifications are deliberately non-durable and may later roll back.
 */
public final class StatusChangeInfo {
    private final MessageInfo message;
    private final Status previousStatus;
    private final Status currentStatus;
    private final FailureInfo failure;

    public StatusChangeInfo(MessageInfo message, Status previousStatus, Status currentStatus,
            FailureInfo failure) {
        this.message = Objects.requireNonNull(message, "message");
        this.previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus");
        if (message.getStatus() != currentStatus) {
            throw new IllegalArgumentException("message status must equal currentStatus");
        }
        if ((currentStatus == Status.ERROR) != (failure != null)) {
            throw new IllegalArgumentException(
                    "failure must be present if and only if currentStatus is ERROR");
        }
        this.failure = failure;
    }

    public MessageInfo getMessage() {
        return message;
    }

    public Status getPreviousStatus() {
        return previousStatus;
    }

    public Status getCurrentStatus() {
        return currentStatus;
    }

    public FailureInfo getFailure() {
        return failure;
    }
}
