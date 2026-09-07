/* Published under the terms of the Mozilla Public License 2.0. */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-consumer telemetry authority; cancellation discards pending snapshots, never worker scopes.
 * The caller's Future controls application cancellation: call() still executes its operation after
 * authority cancellation or prior consumption, with no transferred context.
 */
public final class ExecutionContextBundle {
    static final ExecutionContextBundle EMPTY = new ExecutionContextBundle(null,
            new MessageLifecycleListeners.ExecutionEntry[0]);
    private final MessageLifecycleListeners owner;
    private final AtomicReference<MessageLifecycleListeners.ExecutionEntry[]> pending;

    ExecutionContextBundle(MessageLifecycleListeners owner,
            MessageLifecycleListeners.ExecutionEntry[] entries) {
        this.owner = owner;
        this.pending = new AtomicReference<>(entries);
    }

    public void cancel() { pending.set(null); }

    public <T> T call(Callable<T> operation) throws Exception {
        java.util.Objects.requireNonNull(operation, "operation");
        MessageLifecycleListeners.ExecutionEntry[] entries = pending.getAndSet(null);
        LifecycleExecutionScope scope = owner == null || entries == null
                ? LifecycleExecutionScope.NOOP : owner.attachExecutionContexts(entries);
        Throwable original = null;
        try { return operation.call(); }
        catch (Exception | Error failure) { original = failure; throw failure; }
        finally {
            try { scope.close(); }
            catch (RuntimeException | Error cleanup) {
                if (original == null) throw cleanup;
                Throwable selected = MessageLifecycleListeners.executionPrimary(original, cleanup);
                if (selected != original) {
                    if (selected instanceof Error) throw (Error) selected;
                    if (selected instanceof RuntimeException) throw (RuntimeException) selected;
                }
            }
        }
    }
}
