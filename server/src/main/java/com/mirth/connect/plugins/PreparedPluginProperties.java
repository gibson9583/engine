/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Objects;
import java.util.Properties;

/** Immutable transaction returned by an extension property preparer. */
public final class PreparedPluginProperties {
    private final Properties canonicalProperties;
    private final PropertyPersistenceDirective directive;
    private final PluginPropertyCompletionHandler completion;

    public PreparedPluginProperties(Properties canonicalProperties,
            PropertyPersistenceDirective directive, PluginPropertyCompletionHandler completion) {
        this.canonicalProperties = copyStringProperties(canonicalProperties);
        this.directive = Objects.requireNonNull(directive, "directive");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    public Properties getCanonicalProperties() {
        return copyStringProperties(canonicalProperties);
    }

    public PropertyPersistenceDirective getDirective() {
        return directive;
    }

    public PluginPropertyCompletionHandler getCompletion() {
        return completion;
    }

    static Properties copyStringProperties(Properties source) {
        Objects.requireNonNull(source, "properties");
        Properties copy = new Properties();
        for (Object key : source.keySet()) {
            Object value = source.get(key);
            if (!(key instanceof String) || !(value instanceof String)) {
                throw new IllegalArgumentException("properties must contain strings only");
            }
            copy.setProperty((String) key, (String) value);
        }
        return copy;
    }
}
