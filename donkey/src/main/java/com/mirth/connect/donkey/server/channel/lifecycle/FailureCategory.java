/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

/** Safe failure categories aligned with message-relevant engine error event types. */
public enum FailureCategory {
    UNKNOWN,
    SOURCE_CONNECTOR,
    DESTINATION_CONNECTOR,
    SERIALIZER,
    FILTER,
    TRANSFORMER,
    USER_DEFINED_TRANSFORMER,
    RESPONSE_VALIDATION,
    RESPONSE_TRANSFORMER,
    ATTACHMENT_HANDLER,
    PREPROCESSOR,
    POSTPROCESSOR
}
