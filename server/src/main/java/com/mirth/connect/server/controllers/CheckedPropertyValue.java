/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import java.util.Objects;

/** Explicit absent-or-present result from a checked configuration-property read. */
public final class CheckedPropertyValue {
    private static final CheckedPropertyValue ABSENT = new CheckedPropertyValue(false, null);

    private final boolean present;
    private final String value;

    private CheckedPropertyValue(boolean present, String value) {
        this.present = present;
        this.value = value;
    }

    public static CheckedPropertyValue absent() {
        return ABSENT;
    }

    public static CheckedPropertyValue present(String value) {
        return new CheckedPropertyValue(true, Objects.requireNonNull(value, "value"));
    }

    public boolean isPresent() {
        return present;
    }

    public String getValue() {
        return value;
    }
}
