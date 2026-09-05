/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

/** Trusted engine route that initiated an extension-property write. */
public enum PropertyWriteOrigin {
    INITIALIZATION,
    GENERIC_API,
    PLUGIN_API,
    RESTORE
}
