/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Properties;
import java.util.Objects;

/** Immutable outcome and optional canonical properties from a write. */
public final class PluginPropertyWriteResult {
    private final PluginPropertyWriteOutcome outcome;
    private final Properties appliedProperties;

    private PluginPropertyWriteResult(PluginPropertyWriteOutcome outcome, Properties appliedProperties) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.appliedProperties = appliedProperties == null ? null
                : PreparedPluginProperties.copyStringProperties(appliedProperties);
    }

    public static PluginPropertyWriteResult withProperties(PluginPropertyWriteOutcome outcome,
            Properties appliedProperties) {
        if (outcome != PluginPropertyWriteOutcome.COMMITTED
                && outcome != PluginPropertyWriteOutcome.NO_CHANGE
                && outcome != PluginPropertyWriteOutcome.LEGACY_APPLIED) {
            throw new IllegalArgumentException("outcome cannot carry applied properties");
        }
        return new PluginPropertyWriteResult(outcome, appliedProperties);
    }

    public static PluginPropertyWriteResult withoutProperties(PluginPropertyWriteOutcome outcome) {
        if (outcome == PluginPropertyWriteOutcome.COMMITTED
                || outcome == PluginPropertyWriteOutcome.NO_CHANGE
                || outcome == PluginPropertyWriteOutcome.LEGACY_APPLIED) {
            throw new IllegalArgumentException("outcome requires applied properties");
        }
        return new PluginPropertyWriteResult(outcome, null);
    }

    public PluginPropertyWriteOutcome getOutcome() {
        return outcome;
    }

    public Properties getAppliedProperties() {
        return appliedProperties == null ? null
                : PreparedPluginProperties.copyStringProperties(appliedProperties);
    }
}
