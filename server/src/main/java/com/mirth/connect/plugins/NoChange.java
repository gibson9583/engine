/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.plugins;

/** Directs the controller to perform no database mutation. */
public final class NoChange implements PropertyPersistenceDirective {
    public static final NoChange INSTANCE = new NoChange();

    private NoChange() {
    }
}
