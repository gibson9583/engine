/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import com.mirth.connect.client.core.ControllerException;

/** Content-safe validation/conflict rejection raised before property mutation. */
public final class PluginPropertyRejectedException extends ControllerException {
    private final String safeCode;
    private final String safeKey;
    private final String safeCategory;
    private final boolean conflict;

    public PluginPropertyRejectedException(String safeCode, String safeKey,
            String safeCategory, boolean conflict) {
        super("plugin_property_rejected");
        this.safeCode = safeToken(safeCode, "safeCode");
        this.safeKey = safeToken(safeKey, "safeKey");
        this.safeCategory = safeToken(safeCategory, "safeCategory");
        this.conflict = conflict;
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

    public boolean isConflict() {
        return conflict;
    }

    private static String safeToken(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 128
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " must be a safe token");
        }
        return value;
    }
}
