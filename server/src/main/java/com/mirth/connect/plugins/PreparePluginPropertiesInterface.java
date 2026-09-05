/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

import java.util.Properties;

import com.mirth.connect.client.core.ControllerException;

/** Privileged extension hook that validates and canonicalizes properties before mutation. */
public interface PreparePluginPropertiesInterface extends AutoCloseable {
    PreparedPluginProperties prepare(Properties incoming, boolean merge,
            PropertyWriteContext context) throws ControllerException;

    @Override
    default void close() throws Exception {
    }
}
