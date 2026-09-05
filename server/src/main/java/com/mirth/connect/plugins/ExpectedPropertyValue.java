/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Objects;

/** Explicit expected state for a checked property write. */
public final class ExpectedPropertyValue {
    private static final ExpectedPropertyValue ABSENT = new ExpectedPropertyValue(false, null);

    private final boolean present;
    private final String value;

    private ExpectedPropertyValue(boolean present, String value) {
        this.present = present;
        this.value = value;
    }

    public static ExpectedPropertyValue absent() {
        return ABSENT;
    }

    public static ExpectedPropertyValue present(String value) {
        return new ExpectedPropertyValue(true, Objects.requireNonNull(value, "value"));
    }

    public boolean isPresent() {
        return present;
    }

    public String getValue() {
        return value;
    }

    public boolean matches(String actual) {
        return present ? Objects.equals(value, actual) : actual == null;
    }
}
