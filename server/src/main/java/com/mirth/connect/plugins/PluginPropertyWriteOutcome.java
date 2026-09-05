/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

/** Closed outcomes returned by origin-aware extension-property writes. */
public enum PluginPropertyWriteOutcome {
    COMMITTED,
    NO_CHANGE,
    PRESERVED_REJECTED,
    CONFLICT,
    OUTCOME_UNKNOWN,
    PREPARER_UNAVAILABLE,
    LEGACY_APPLIED
}
