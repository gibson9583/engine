/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.donkey.server.channel.components;

import com.mirth.connect.donkey.model.DonkeyException;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureCategory;

public class FilterTransformerException extends DonkeyException {
    private final FailureCategory failureCategory;

    public FilterTransformerException(String message, Throwable cause, String formattedError) {
        this(message, cause, formattedError, FailureCategory.UNKNOWN);
    }

    public FilterTransformerException(String message, Throwable cause, String formattedError,
            FailureCategory failureCategory) {
        super(message, cause, formattedError);
        this.failureCategory = failureCategory != null ? failureCategory : FailureCategory.UNKNOWN;
    }

    public FailureCategory getFailureCategory() {
        return failureCategory;
    }
}
