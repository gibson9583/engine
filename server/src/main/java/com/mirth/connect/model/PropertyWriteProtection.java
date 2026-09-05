/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * This software is published under the terms of the MPL license.
 */
package com.mirth.connect.model;

/** Persistence policy for an extension's configuration-property namespace. */
public enum PropertyWriteProtection {
    /** Preserve the historical extension-property behavior. */
    COMPATIBILITY,
    /** Refuse every write that has not been prepared by the installed extension. */
    PREPARED_ONLY
}
