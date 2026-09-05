/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Objects;

/** Immutable trusted context for an extension-property write. */
public final class PropertyWriteContext {
    private final PropertyWriteOrigin origin;
    private final PropertyWritePurpose purpose;
    private final Integer authenticatedUserId;

    public PropertyWriteContext(PropertyWriteOrigin origin, PropertyWritePurpose purpose,
            Integer authenticatedUserId) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        if (authenticatedUserId != null && authenticatedUserId.intValue() <= 0) {
            throw new IllegalArgumentException("authenticatedUserId must be positive");
        }
        if ((origin == PropertyWriteOrigin.GENERIC_API || purpose == PropertyWritePurpose.RECOVERY)
                && authenticatedUserId == null) {
            throw new IllegalArgumentException("authenticatedUserId must be positive");
        }
        if (origin == PropertyWriteOrigin.INITIALIZATION && authenticatedUserId != null) {
            throw new IllegalArgumentException("initialization cannot carry a user id");
        }
        if (purpose == PropertyWritePurpose.RECOVERY && origin != PropertyWriteOrigin.PLUGIN_API) {
            throw new IllegalArgumentException("recovery is restricted to the plugin API");
        }
        this.authenticatedUserId = authenticatedUserId;
    }

    public static PropertyWriteContext initialization() {
        return new PropertyWriteContext(PropertyWriteOrigin.INITIALIZATION,
                PropertyWritePurpose.NORMAL, null);
    }

    public static PropertyWriteContext normal(PropertyWriteOrigin origin, int authenticatedUserId) {
        return new PropertyWriteContext(origin, PropertyWritePurpose.NORMAL, authenticatedUserId);
    }

    public static PropertyWriteContext recovery(int authenticatedUserId) {
        return new PropertyWriteContext(PropertyWriteOrigin.PLUGIN_API,
                PropertyWritePurpose.RECOVERY, authenticatedUserId);
    }

    /** Context for legacy in-process plugin callers that have no HTTP actor. */
    public static PropertyWriteContext pluginApi() {
        return new PropertyWriteContext(PropertyWriteOrigin.PLUGIN_API,
                PropertyWritePurpose.NORMAL, null);
    }

    public PropertyWriteOrigin getOrigin() {
        return origin;
    }

    public PropertyWritePurpose getPurpose() {
        return purpose;
    }

    public Integer getAuthenticatedUserId() {
        return authenticatedUserId;
    }
}
