/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CancellationException;

import org.junit.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureCategory;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleOutcome;
import com.mirth.connect.donkey.server.data.DonkeyDao;

public class MessageLifecycleSupportTest {
    @Test
    public void statusHelperMutatesBeforeDaoAndClearsStaleFailureOnRecovery() {
        DonkeyDao dao = mock(DonkeyDao.class);
        ConnectorMessage message = new ConnectorMessage();
        message.setStatus(Status.RECEIVED);
        FailureInfo failure = new FailureInfo(FailureCategory.TRANSFORMER,
                IllegalStateException.class.getName());
        doAnswer(invocation -> {
            assertEquals(Status.ERROR, message.getStatus());
            assertSame(failure, message.getLifecycleFailureInfo());
            return null;
        }).when(dao).updateStatus(message, Status.RECEIVED);

        MessageLifecycleSupport.updateStatus(dao, message, Status.RECEIVED, Status.ERROR,
                failure);
        MessageLifecycleSupport.updateStatus(dao, message, Status.ERROR, Status.QUEUED,
                failure);

        assertEquals(Status.QUEUED, message.getStatus());
        assertNull(message.getLifecycleFailureInfo());
        verify(dao, times(1)).updateStatus(message, Status.RECEIVED);
        verify(dao, times(1)).updateStatus(message, Status.ERROR);
    }

    @Test
    public void terminalOutcomeDistinguishesCancellationInterruptionRollbackAndError() {
        assertEquals(LifecycleOutcome.CANCELLED,
                MessageLifecycleSupport.result(null,
                        new IllegalStateException(new CancellationException()), false, null,
                        FailureCategory.UNKNOWN).getOutcome());
        assertEquals(LifecycleOutcome.INTERRUPTED,
                MessageLifecycleSupport.result(null,
                        new IllegalStateException(new InterruptedException()), false, null,
                        FailureCategory.UNKNOWN).getOutcome());
        assertEquals(LifecycleOutcome.ROLLED_BACK,
                MessageLifecycleSupport.result(null, new IllegalStateException(), true, null,
                        FailureCategory.UNKNOWN).getOutcome());
        assertEquals(LifecycleOutcome.ERROR,
                MessageLifecycleSupport.result(null, new IllegalStateException(), false, null,
                        FailureCategory.UNKNOWN).getOutcome());
    }
}
