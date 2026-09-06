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

import java.util.Calendar;
import java.util.concurrent.CancellationException;

import org.junit.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureCategory;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleOutcome;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleDispatchToken;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListener;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListeners;
import com.mirth.connect.donkey.server.channel.lifecycle.ExecutionMode;
import com.mirth.connect.donkey.server.data.DonkeyDao;

public class MessageLifecycleSupportTest {
    @Test
    public void transferCallbackCarriesItsExplicitReasonRatherThanInferringFromAttempts() {
        var listeners = MessageLifecycleSupport.listeners();
        var observed = new java.util.concurrent.atomic.AtomicReference<
                com.mirth.connect.donkey.server.channel.lifecycle.HandoffInfo>();
        var registration = listeners.register(new MessageLifecycleListener() {
            @Override
            public com.mirth.connect.donkey.server.channel.lifecycle.HandoffReceipt onHandoffCreated(
                    com.mirth.connect.donkey.server.channel.lifecycle.HandoffInfo info) {
                observed.set(info);
                return com.mirth.connect.donkey.server.channel.lifecycle.HandoffReceipt.NOOP;
            }
        });
        try {
            for (var reason : com.mirth.connect.donkey.server.channel.lifecycle.HandoffCreateReason.values()) {
                var kind = reason.getKind();
                boolean source = kind == com.mirth.connect.donkey.server.channel.lifecycle.HandoffKind.SOURCE_QUEUE;
                ConnectorMessage message = new ConnectorMessage("channel", "Channel", 7L,
                        source ? 0 : 1, "server", Calendar.getInstance(), Status.QUEUED);
                message.setConnectorName(source ? "source" : "destination");
                message.setChainId(source ? 0 : 1);
                // Identical attempt count across all destination reasons proves no inference.
                message.setSendAttempts(4);
                var token = listeners.captureToken();
                MessageLifecycleSupport.initialize(message, token,
                        listeners.allocateMessageIncarnationId(token), "HTTP Sender", ExecutionMode.SYNCHRONOUS);
                MessageLifecycleSupport.createHandoff(message, kind, reason);
                assertSame(reason, observed.get().getCreateReason());
                assertEquals(4, observed.get().getMessage().getSendAttempts());
            }
        } finally {
            registration.unregister();
        }
    }

    @Test
    public void snapshotDoesNotChangeWhenMutableEngineMessageChanges() {
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        listeners.register(new MessageLifecycleListener() {});
        LifecycleDispatchToken token = listeners.captureToken();
        Calendar received = Calendar.getInstance();
        received.setTimeInMillis(100L);
        ConnectorMessage message = new ConnectorMessage("channel", "Channel", 7L, 1,
                "server", received, Status.RECEIVED);
        message.setConnectorName("destination");
        message.setChainId(1);
        message.setSendAttempts(2);
        MessageLifecycleSupport.initialize(message, token,
                listeners.allocateMessageIncarnationId(token), "HTTP Sender",
                ExecutionMode.SYNCHRONOUS);

        MessageInfo snapshot = MessageLifecycleSupport.snapshot(message);

        received.setTimeInMillis(999L);
        message.setChannelId("changed");
        message.setConnectorName("changed");
        message.setChainId(2);
        message.setStatus(Status.ERROR);
        message.setSendAttempts(3);

        assertEquals("channel", snapshot.getChannelId());
        assertEquals("destination", snapshot.getConnectorName());
        assertEquals(Integer.valueOf(1), snapshot.getChainId());
        assertEquals(Status.RECEIVED, snapshot.getStatus());
        assertEquals(2, snapshot.getSendAttempts());
        assertEquals(Long.valueOf(100L), snapshot.getReceivedTimeMillis());
    }

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
