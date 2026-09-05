/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** A normalized, content-free W3C trace-parent value. */
public final class InboundTraceParent {
    private final String traceIdHex;
    private final String parentSpanIdHex;
    private final int traceFlagsUnsignedByte;

    public InboundTraceParent(String traceIdHex, String parentSpanIdHex,
            int traceFlagsUnsignedByte) {
        this.traceIdHex = requireLowerHex(traceIdHex, 32, "traceIdHex");
        this.parentSpanIdHex = requireLowerHex(parentSpanIdHex, 16, "parentSpanIdHex");
        if (traceFlagsUnsignedByte < 0 || traceFlagsUnsignedByte > 255) {
            throw new IllegalArgumentException("traceFlagsUnsignedByte must be between 0 and 255");
        }
        this.traceFlagsUnsignedByte = traceFlagsUnsignedByte;
    }

    public String getTraceIdHex() {
        return traceIdHex;
    }

    public String getParentSpanIdHex() {
        return parentSpanIdHex;
    }

    public int getTraceFlagsUnsignedByte() {
        return traceFlagsUnsignedByte;
    }

    private static String requireLowerHex(String value, int length, String name) {
        if (value == null || value.length() != length) {
            throw new IllegalArgumentException(name + " must contain exactly " + length
                    + " lowercase hexadecimal characters");
        }
        boolean nonzero = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                throw new IllegalArgumentException(name
                        + " must contain exactly " + length + " lowercase hexadecimal characters");
            }
            nonzero |= character != '0';
        }
        if (!nonzero) {
            throw new IllegalArgumentException(name + " must not be all zeroes");
        }
        return value;
    }
}
