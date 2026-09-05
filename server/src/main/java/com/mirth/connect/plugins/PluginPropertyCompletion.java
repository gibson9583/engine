/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

/** Persistence completion delivered exactly once to a property preparer. */
public enum PluginPropertyCompletion {
    COMMITTED,
    NO_CHANGE,
    PRESERVED_REJECTED,
    CONFLICT,
    OUTCOME_UNKNOWN,
    FAILED
}
