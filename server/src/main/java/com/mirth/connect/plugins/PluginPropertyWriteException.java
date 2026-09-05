/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Objects;

import com.mirth.connect.client.core.ControllerException;

/** Typed failure used by compatibility signatures that cannot return a write result. */
public class PluginPropertyWriteException extends ControllerException {
    private final PluginPropertyWriteOutcome outcome;

    public PluginPropertyWriteException(PluginPropertyWriteOutcome outcome) {
        super("plugin_property_write_" + Objects.requireNonNull(outcome, "outcome").name().toLowerCase());
        this.outcome = outcome;
    }

    public PluginPropertyWriteException(PluginPropertyWriteOutcome outcome, Throwable cause) {
        super("plugin_property_write_" + Objects.requireNonNull(outcome, "outcome").name().toLowerCase(), cause);
        this.outcome = outcome;
    }

    public PluginPropertyWriteOutcome getOutcome() {
        return outcome;
    }
}
