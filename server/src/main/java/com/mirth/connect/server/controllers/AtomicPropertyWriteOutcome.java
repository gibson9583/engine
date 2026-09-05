/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

/** Result of an exact, transactional property compare-and-set. */
public enum AtomicPropertyWriteOutcome {
    COMMITTED,
    CONFLICT,
    OUTCOME_UNKNOWN
}
