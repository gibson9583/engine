# Message lifecycle SPI threading contract

The message lifecycle SPI is a synchronous, in-band extension point. It is separate from the
asynchronous event dispatcher because a listener may need the engine thread's current context for
nested work. The SPI is vendor-neutral and exposes only immutable, content-free values.

This document is normative for listener threading and runtime-status behavior. The public method
Javadocs in `MessageLifecycleListener` carry the same contract at the API boundary.

## Listener obligations

One listener instance can be invoked concurrently from many channels and from several kinds of
engine thread. A listener must:

- be thread-safe;
- return quickly and perform no blocking I/O;
- never call back into engine controllers or wait for message-engine work;
- buffer and export asynchronously;
- restore any thread-local state from its lifecycle handle before `end` returns; and
- use handoff receipts for asynchronous correlation instead of assuming thread affinity.

Installing a listener is privileged in-process code execution. The engine isolates nonfatal
callback failures and measures callback health, but it cannot safely impose a timeout or preempt a
hung listener. A blocked callback blocks real message traffic. A callback that runs in an open DAO
transaction can also delay that transaction; an enclosing failure can roll it back.

## Callback matrix

| Callback | Calling thread | Lock, transaction, and pairing contract |
|---|---|---|
| `onDispatchStart` | The source connector's current thread, renamed as a channel dispatch thread for the operation. A synchronous Channel Writer dispatch uses its upstream send thread. | Runs after the stopped-state guard and before dispatch-thread registration or target-channel process-lock acquisition. Its handle includes target-lock wait and all accepted dispatch work. It ends on the same thread and may end while the target permit is still held. A Channel Writer call can already hold the upstream channel's process-lock permit and destination DAO transaction. Rejections before the state guard are outside the lifecycle. |
| `onSourceMessageCreated` | The accepted dispatch thread. | Runs with the target-channel process-lock permit held, immediately after source identity and lifecycle carriers are initialized. It precedes new-message insertion, overwrite deletion/reset, source-map copying, attachment work, content insertion, connector-message persistence, and message-scoped error events. It is an identity/candidate notification, not a persistence claim. |
| `onProcessStart` | The thread entering source processing: synchronous dispatch, a named source-queue thread, or a channel-executor/recovery thread. | A process-lock permit may be held. The handle covers preprocessing, source transform, destination chains, postprocessing, filtered/error early returns, cancellation, interruption, and transaction completion. It ends on the same thread. |
| `onFilterTransformerStart` | Its enclosing operation's thread: dispatch, source queue, channel executor/recovery, or destination queue. An inline final-chain destination transform can use the dispatch, source-queue, or recovery thread rather than a chain-executor thread. | Both source and destination transforms can run inside an open enclosing DAO transaction. The handle covers filtered, transformed, and exceptional exits and ends on the same thread. |
| `onDestinationChainStart` | The source-processing thread for an inline final chain, or a channel-executor/recovery thread for asynchronous chains. | Nested destination callbacks may run while a DAO transaction is open. The handle covers rejected/disabled, recovery, partial-chain, and exceptional exits and ends on the same thread. |
| `onDestinationQueueStart` | A named destination-queue thread after an acquired or held retry is selected. | The handle spans retry delay, queued transform, send, status writes, DAO commit/rollback/close, and final queue disposition. Listener callbacks are not intentionally invoked while the queue monitor is held. It ends on the same queue thread. |
| `onSendStart` | The enclosing source-process, destination-chain, or destination-queue thread. | Destination sends normally occur inside the enclosing open DAO transaction. The handle covers the actual connector call and response validation and ends on the same thread. |
| `onHandoffCreated` | The producer thread before async submission or attempted source/destination queue insertion. Retry/requeue/rotation creation can occur on a destination-queue thread. | Initial queue creation occurs outside the queue monitor. The returned receipt is one-use and may be consumed or cancelled on another thread. An immediate synchronous retry creates no handoff. |
| `onHandoffCancelled` | The thread that observes rejected, failed, displaced, invalidated, removed, or otherwise discarded transfer. | It need not be the creation thread. Queue code claims cancellation while ownership is protected, then invokes the callback after releasing the queue monitor. Cancellation is runtime cleanup, not a durable event. |
| `onHandoffsAbandoned` | The unregistering thread, or the engine callback thread that crosses the quarantine threshold. | Unregister/quarantine first makes the registration ineligible. Abandonment does not wait for callbacks that already acquired short invocation leases, so it may overlap them. Listener-owned publication must recheck terminal state. |
| `onStatusChanged` | The current source, chain, or destination-queue processing thread. | Runs only after `dao.updateStatus` returns and before the surrounding transaction commits. The transition is a runtime observation and may later roll back. A failed update emits no notification. The enclosing lifecycle result reports rollback. |
| `LifecycleHandle.end` | Exactly the same engine thread that received that handle from a start callback. | The engine ends successful handles exactly once from a lexical `finally`, in reverse listener registration order, even after unregister or quarantine. One failing end does not strand sibling handles. |

A single message can therefore cross several thread kinds. Thread-local state is valid only within
one retained start/end scope. Source queue, asynchronous chain, destination queue, retry, and
rotation boundaries use explicit one-use handoff receipts. Recovery and persistent-queue refill do
not inherit thread-local context from a previous process.

## Lock and transaction rules

Listeners must treat every callback as lock-sensitive. They must not acquire channel lifecycle
locks, destination queue monitors, or controller locks, and must not call an API that can wait for
those resources. Dispatch begins before process-lock acquisition specifically so the receive scope
includes lock wait; dispatch end may still observe that permit held. Destination callbacks can be
nested in a DAO transaction, so export, retry, and network activity belong on listener-owned
asynchronous workers.

The engine does not intentionally call handoff listener code while holding a source or destination
queue monitor. Queue implementations claim exact-carrier ownership under their monitor, release
the monitor, and only then deliver cancellation. This ordering prevents listener re-entry from
deadlocking queue mutation while preserving one-consumer ownership.

## Runtime status semantics

`onStatusChanged` describes engine execution, not committed database state. The engine first sets
the in-memory status and calls `dao.updateStatus`; only a successful update is followed by the
notification. The surrounding transaction may still fail or roll back. A listener must retain the
status event as observed and use `LifecycleOutcome.ROLLED_BACK` on the enclosing handle result to
represent that later outcome. It must not label the status notification as committed or try to
query the controller/database from the callback.

## Handoff creation reasons

`HandoffInfo.getCreateReason()` copies a closed `HandoffCreateReason` from the actual transfer boundary. Source insertion uses `SOURCE_ENQUEUE`, executor submission uses `ASYNC_CHAIN_SUBMIT`, and initial destination insertion uses `DESTINATION_ENQUEUE`. A retained destination-queue attempt uses `DESTINATION_RETRY`; release for rotation uses `DESTINATION_ROTATION`. `DESTINATION_REQUEUE` is available for an explicit requeue producer; it is not inferred from attempt counts or message identity.

The destination queue freezes its rotation decision once and uses that same decision for reason metadata and eventual carrier transfer after DAO cleanup. Cancelled or failed transfers keep the existing exact receipt cancellation semantics. A reason must match its handoff kind. The original four-argument constructor remains a convenience for initial enqueue/submission; production retry and rotation sites pass their reason explicitly.

## Callback health

`LifecycleListenerRegistration.getCallbackHealth` returns invocation, failure, slow-call,
and maximum-duration statistics for the selected callback. Paired ends retain their start's
operation kind: `DISPATCH_END`, `PROCESS_END`, `FILTER_TRANSFORMER_END`,
`DESTINATION_CHAIN_END`, `DESTINATION_QUEUE_END`, or `SEND_END`. These counts include
retained handles ended after unregister/quarantine and handles unwound after a fatal sibling
start. Repeated aggregate end calls add no extra sample.

`HANDLE_END` remains a compatibility aggregate: counts are the sum of the six end kinds and
maximum duration is their maximum. Consumers must use either that aggregate or the individual
end kinds when computing totals. The aggregate is calculated at read time and does not add a
second failure to the quarantine streak.
