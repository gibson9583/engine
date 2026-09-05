/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Content-free failure classification. Exception messages, causes, and stacks are excluded. */
public final class FailureInfo {
    private final FailureCategory failureCategory;
    private final String exceptionClassName;

    public FailureInfo(FailureCategory failureCategory, String exceptionClassName) {
        this.failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
        this.exceptionClassName = exceptionClassName;
    }

    public FailureCategory getFailureCategory() {
        return failureCategory;
    }

    /** Returns the exception class name, or null when no throwable was retained. */
    public String getExceptionClassName() {
        return exceptionClassName;
    }
}
