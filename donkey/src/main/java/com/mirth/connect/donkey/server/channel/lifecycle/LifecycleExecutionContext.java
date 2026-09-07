/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.donkey.server.channel.lifecycle;

/**
 * Content-free immutable listener snapshot for a synchronous operation delegated to another thread.
 * Capture must acquire no leases/resources and must retain no producer handles, SDK owners, mutable
 * messages or application maps. Discarding an unattached snapshot requires no callback. The engine
 * grants attachment at most once, to the original registration only. No lifecycle operation starts
 * or ends here. Attachment must restore its own partial changes before throwing.
 */
@FunctionalInterface
public interface LifecycleExecutionContext {
    LifecycleExecutionContext NOOP = () -> LifecycleExecutionScope.NOOP;
    LifecycleExecutionScope attach();
}
