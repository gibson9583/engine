/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.DonkeyConfiguration;
import com.mirth.connect.donkey.server.DonkeyConnectionPools;
import com.mirth.connect.donkey.server.StartException;
import com.mirth.connect.donkey.server.channel.lifecycle.ExecutionMode;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffReceipt;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleHandle;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleListenerRegistration;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleOutcome;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleResult;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListener;
import com.mirth.connect.donkey.server.channel.lifecycle.ProcessInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.QueueInfo;
import com.mirth.connect.donkey.server.controllers.ChannelController;
import com.mirth.connect.donkey.test.util.TestChannel;
import com.mirth.connect.donkey.test.util.TestDestinationConnector;
import com.mirth.connect.donkey.test.util.TestUtils;

/** Restart/reconstruction coverage for lifecycle roots that cannot carry persisted context. */
public class MessageLifecycleRecoveryTest {
    private static final AtomicInteger CHANNEL_SEQUENCE = new AtomicInteger();

    private RecoveryListener listener;
    private LifecycleListenerRegistration registration;
    private TestChannel channel;

    @BeforeClass
    public static void startEngine() throws StartException {
        DonkeyConfiguration configuration = TestUtils.getEmbeddedDonkeyTestConfiguration(
                "message-lifecycle-recovery");
        DonkeyConnectionPools.getInstance().init(configuration.getDonkeyProperties());
        Donkey.getInstance().startEngine(configuration);
    }

    @AfterClass
    public static void stopEngine() {
        Donkey.getInstance().stopEngine();
    }

    @Before
    public void registerListener() {
        listener = new RecoveryListener();
        registration = Donkey.getInstance().getMessageLifecycleListeners().register(listener);
    }

    @After
    public void cleanUp() throws Exception {
        if (channel != null) {
            try {
                channel.stop();
            } catch (Exception ignored) {}
            try {
                channel.undeploy();
            } catch (Exception ignored) {}
            ChannelController.getInstance().removeChannel(channel.getChannelId());
        }
        if (registration != null) {
            registration.unregister();
        }
        Thread.interrupted();
    }

    @Test
    public void unfinishedSourceRecoveryStartsAndEndsOneRecoveryRoot() throws Exception {
        String channelId = nextChannelId();
        channel = TestUtils.createDefaultChannel(channelId, TestUtils.DEFAULT_SERVER_ID,
                true, 1, 1);
        TestUtils.createAndStoreNewMessage(new RawMessage(TestUtils.TEST_HL7_MESSAGE),
                channelId, channelId, TestUtils.DEFAULT_SERVER_ID, TestUtils.getDaoFactory());

        channel.deploy();
        channel.start(null);

        assertTrue(listener.processEnded.await(10, TimeUnit.SECONDS));
        assertEquals(1, listener.processStarts.get());
        assertEquals(1, listener.processEnds.get());
        assertEquals(ExecutionMode.RECOVERY, listener.processMode);
        assertEquals(LifecycleOutcome.SUCCESS, listener.processResult.getOutcome());
        assertTrue(listener.processIncarnation > 0L);
    }

    @Test
    public void recoveredMessageUsesOneFreshIncarnationAcrossRemovedDestination()
            throws Exception {
        channel = TestUtils.createDefaultChannel(nextChannelId(), TestUtils.DEFAULT_SERVER_ID,
                true, 1, 1);
        Message recovered = new Message();
        recovered.setMessageId(41L);
        ConnectorMessage source = message(channel, 41L, 0, Status.RECEIVED);
        ConnectorMessage removedDestination = message(channel, 41L, 99, Status.RECEIVED);
        recovered.getConnectorMessages().put(0, source);
        recovered.getConnectorMessages().put(99, removedDestination);

        ((Channel) channel).initializeRecoveryLifecycle(recovered);

        assertNotNull(recovered.getLifecycleDispatchToken());
        assertTrue(recovered.getMessageIncarnationId() > 0L);
        assertSame(recovered.getLifecycleDispatchToken(), source.getLifecycleDispatchToken());
        assertSame(recovered.getLifecycleDispatchToken(),
                removedDestination.getLifecycleDispatchToken());
        assertEquals(recovered.getMessageIncarnationId(), source.getMessageIncarnationId());
        assertEquals(recovered.getMessageIncarnationId(),
                removedDestination.getMessageIncarnationId());
        assertEquals(ExecutionMode.RECOVERY, source.getLifecycleExecutionMode());
        assertEquals(ExecutionMode.RECOVERY,
                removedDestination.getLifecycleExecutionMode());
        assertEquals("", removedDestination.getLifecycleConnectorType());
    }

    @Test
    public void persistedQueueRefillGetsFreshRootAndCompleteQueuePairing()
            throws Exception {
        channel = TestUtils.createDefaultChannel(nextChannelId(), TestUtils.DEFAULT_SERVER_ID,
                true, 1, 1);
        TestDestinationConnector destination = (TestDestinationConnector) channel
                .getDestinationChainProviders().get(0).getDestinationConnectors().get(1);
        ConnectorMessage reconstructed = message(channel, 51L, 1, Status.QUEUED);
        reconstructed.setChainId(1);

        MessageLifecycleSupport.initializeRecovered(reconstructed, destination,
                ExecutionMode.PERSISTED_QUEUE_REFILL);
        QueueInfo queue = new QueueInfo(MessageLifecycleSupport.snapshot(reconstructed),
                reconstructed.getLifecycleExecutionMode(), 1L);
        LifecycleHandle handle = Donkey.getInstance().getMessageLifecycleListeners()
                .onDestinationQueueStart(reconstructed.getLifecycleDispatchToken(), queue);
        handle.end(new LifecycleResult(LifecycleOutcome.SUCCESS,
                MessageLifecycleSupport.snapshot(reconstructed), null, null));

        assertEquals(ExecutionMode.PERSISTED_QUEUE_REFILL, listener.queueMode);
        assertEquals(1, listener.queueStarts.get());
        assertEquals(1, listener.queueEnds.get());
        assertTrue(reconstructed.getMessageIncarnationId() > 0L);
    }

    private static ConnectorMessage message(TestChannel channel, long messageId, int metadataId,
            Status status) {
        ConnectorMessage message = new ConnectorMessage(channel.getChannelId(),
                channel.getName(), messageId, metadataId, channel.getServerId(),
                Calendar.getInstance(), status);
        message.setConnectorName(metadataId == 0 ? "source" : "destination");
        return message;
    }

    private static String nextChannelId() {
        return "lifecycle-recovery-" + CHANNEL_SEQUENCE.incrementAndGet();
    }

    private static final class RecoveryListener implements MessageLifecycleListener {
        private final AtomicInteger processStarts = new AtomicInteger();
        private final AtomicInteger processEnds = new AtomicInteger();
        private final AtomicInteger queueStarts = new AtomicInteger();
        private final AtomicInteger queueEnds = new AtomicInteger();
        private final CountDownLatch processEnded = new CountDownLatch(1);
        private volatile ExecutionMode processMode;
        private volatile ExecutionMode queueMode;
        private volatile long processIncarnation;
        private volatile LifecycleResult processResult;

        @Override
        public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
            processStarts.incrementAndGet();
            processMode = process.getExecutionMode();
            processIncarnation = process.getMessage().getMessageIncarnationId();
            return result -> {
                processResult = result;
                processEnds.incrementAndGet();
                processEnded.countDown();
            };
        }

        @Override
        public LifecycleHandle onDestinationQueueStart(QueueInfo queue,
                HandoffReceipt receipt) {
            queueStarts.incrementAndGet();
            queueMode = queue.getExecutionMode();
            return result -> queueEnds.incrementAndGet();
        }
    }
}
