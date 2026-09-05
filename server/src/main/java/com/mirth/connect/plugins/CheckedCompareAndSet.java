/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Objects;

/** Persist one canonical property only if its exact prior state still matches. */
public final class CheckedCompareAndSet implements PropertyPersistenceDirective {
    private final String propertyName;
    private final ExpectedPropertyValue expected;

    public CheckedCompareAndSet(String propertyName, ExpectedPropertyValue expected) {
        if (propertyName == null || propertyName.isEmpty()) {
            throw new IllegalArgumentException("propertyName is required");
        }
        this.propertyName = propertyName;
        this.expected = Objects.requireNonNull(expected, "expected");
    }

    public String getPropertyName() {
        return propertyName;
    }

    public ExpectedPropertyValue getExpected() {
        return expected;
    }
}
