/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

/** Initialization-only directive that preserves rejected stored bytes verbatim. */
public final class PreserveRejected implements PropertyPersistenceDirective {
    private final String safeCode;
    private final String safeKey;
    private final String safeCategory;
    private final boolean recoveryPermitted;

    public PreserveRejected(String safeCode, String safeKey, String safeCategory,
            boolean recoveryPermitted) {
        this.safeCode = requireToken(safeCode, "safeCode");
        this.safeKey = requireToken(safeKey, "safeKey");
        this.safeCategory = requireToken(safeCategory, "safeCategory");
        this.recoveryPermitted = recoveryPermitted;
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 128
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public String getSafeCode() {
        return safeCode;
    }

    public String getSafeKey() {
        return safeKey;
    }

    public String getSafeCategory() {
        return safeCategory;
    }

    public boolean isRecoveryPermitted() {
        return recoveryPermitted;
    }
}
