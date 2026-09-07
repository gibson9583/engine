/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

/** Per-call durability evidence that survives an exceptional return from a checked write. */
public final class CheckedPropertyWriteReceipt {
    public enum State { NOT_COMMITTED, OUTCOME_UNKNOWN, COMMITTED, CONFLICT }
    private volatile State state = State.NOT_COMMITTED;
    private boolean used;

    /** Must succeed before acquiring any transaction resource. A receipt is never reusable. */
    public synchronized void begin() {
        if (used) throw new IllegalStateException("checked_write_receipt_reused");
        used = true;
    }
    public State state() { return state; }
    // These evidence updates allocate nothing. A proven commit is never downgraded by cleanup.
    public void commitAttempted() { if (state == State.NOT_COMMITTED) state = State.OUTCOME_UNKNOWN; }
    public void committed() { state = State.COMMITTED; }
    public void conflict() { if (state != State.COMMITTED) state = State.CONFLICT; }
    public void notCommitted() {
        if (state != State.COMMITTED && state != State.CONFLICT) state = State.NOT_COMMITTED;
    }
}
