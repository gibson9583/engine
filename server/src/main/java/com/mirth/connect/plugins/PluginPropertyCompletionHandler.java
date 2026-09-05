/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

/** Receives the final result of one prepared persistence attempt. */
@FunctionalInterface
public interface PluginPropertyCompletionHandler {
    void complete(PluginPropertyCompletion outcome, Throwable failure) throws Exception;
}
