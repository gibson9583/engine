/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Worker-owned lexical context only; close restores this thread, never the producer's scope. */
@FunctionalInterface
public interface LifecycleExecutionScope extends AutoCloseable {
    LifecycleExecutionScope NOOP = () -> {};
    @Override void close();
}
