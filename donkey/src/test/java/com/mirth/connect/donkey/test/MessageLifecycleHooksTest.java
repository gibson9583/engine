/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.DonkeyConfiguration;
import com.mirth.connect.donkey.server.DonkeyConnectionPools;
import com.mirth.connect.donkey.server.StartException;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.ChannelException;
import com.mirth.connect.donkey.server.channel.DestinationChainProvider;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.FilterTransformerResult;
import com.mirth.connect.donkey.server.channel.components.FilterTransformerException;
import com.mirth.connect.donkey.server.channel.lifecycle.ExecutionMode;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureCategory;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellation;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffKind;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffReceipt;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleHandle;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleListenerRegistration;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleOutcome;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleResult;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListener;
import com.mirth.connect.donkey.server.channel.lifecycle.StatusChangeInfo;
import com.mirth.connect.donkey.server.controllers.ChannelController;
import com.mirth.connect.donkey.test.util.TestChannel;
import com.mirth.connect.donkey.test.util.TestConnectorProperties;
import com.mirth.connect.donkey.test.util.TestDataType;
import com.mirth.connect.donkey.test.util.TestDestinationConnector;
import com.mirth.connect.donkey.test.util.TestFilterTransformer;
import com.mirth.connect.donkey.test.util.TestResponseTransformer;
import com.mirth.connect.donkey.test.util.TestSourceConnector;
import com.mirth.connect.donkey.test.util.TestUtils;

/** End-to-end lifecycle assertions using the same channel harness as the legacy engine tests. */
public class MessageLifecycleHooksTest {
    private static final AtomicInteger CHANNEL_SEQUENCE = new AtomicInteger();
    private static final long WAIT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private RecordingListener listener;
    private LifecycleListenerRegistration registration;
    private TestChannel channel;

    @BeforeClass
    public static void startEngine() throws StartException {
        DonkeyConfiguration configuration = TestUtils.getEmbeddedDonkeyTestConfiguration(
                "message-lifecycle-hooks");
        DonkeyConnectionPools.getInstance().init(configuration.getDonkeyProperties());
        Donkey.getInstance().startEngine(configuration);
    }

    @AfterClass
    public static void stopEngine() throws StartException {
        Donkey.getInstance().stopEngine();
    }

    @Before
    public void registerListener() {
        listener = new RecordingListener();
        registration = Donkey.getInstance().getMessageLifecycleListeners().register(listener);
    }

    @After
    public void cleanUp() throws Exception {
        Thread.interrupted();
        if (channel != null) {
            try {
                channel.stop();
            } catch (Exception ignored) {
                // The failure-path tests can deliberately stop the channel executor first.
            }
            try {
                channel.undeploy();
            } catch (Exception ignored) {
                // Best effort; storage cleanup below is still required.
            }
            ChannelController.getInstance().removeChannel(channel.getChannelId());
        }
        if (registration != null) {
            registration.unregister();
        }
    }

    @Test
    public void synchronousPathPairsEveryScopeWithoutHandoffs() throws Exception {
        channel = defaultChannel(true, 1);
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.DISPATCH, 1);
        assertCounts(Kind.DISPATCH, 1, Kind.PROCESS, 1, Kind.CHAIN, 1, Kind.SEND, 1);
        assertEquals(2, listener.started(Kind.FILTER).size());
        assertEquals(1, listener.sources.size());
        assertTrue(listener.handoffs.isEmpty());
        listener.assertEveryStartEndedOnce();
        listener.assertAllEndedOnStartingThread();
        assertEquals(LifecycleOutcome.SUCCESS, listener.only(Kind.DISPATCH).result.getOutcome());
        assertEquals(LifecycleOutcome.SUCCESS, listener.only(Kind.PROCESS).result.getOutcome());
        assertEquals(LifecycleOutcome.SUCCESS, listener.only(Kind.CHAIN).result.getOutcome());
        assertEquals(LifecycleOutcome.SUCCESS, listener.only(Kind.SEND).result.getOutcome());
        listener.assertOneIncarnation();
    }

    @Test
    public void sourceQueuedPathConsumesTheExactHandoffOnce() throws Exception {
        channel = defaultChannel(false, 1);
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.PROCESS, 1);
        Scope process = listener.only(Kind.PROCESS);
        assertEquals(ExecutionMode.SOURCE_QUEUE, process.mode);
        HandoffRecord handoff = listener.onlyHandoff(HandoffKind.SOURCE_QUEUE);
        assertSame(handoff.receipt, process.receipt);
        assertEquals(1, handoff.consumed.get());
        assertEquals(0, handoff.cancelled.get());
        listener.assertEveryHandoffSettledOnce();
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void destinationQueuedPathMakesQueueScopeCurrentBeforeNestedCallbacks()
            throws Exception {
        channel = defaultChannel(true, 1);
        TestDestinationConnector destination = destination(channel, 0);
        DestinationConnectorProperties properties = ((TestConnectorProperties) destination
                .getConnectorProperties()).getDestinationConnectorProperties();
        properties.setQueueEnabled(true);
        properties.setSendFirst(false);
        properties.setRegenerateTemplate(true);
        properties.setIncludeFilterTransformer(true);
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.QUEUE, 1);
        Scope queue = listener.only(Kind.QUEUE);
        assertEquals(ExecutionMode.DESTINATION_QUEUE, queue.mode);
        assertEquals(1L, queue.attempt);
        assertTrue(listener.started(Kind.SEND).stream()
                .allMatch(scope -> scope.queueScopeCurrent));
        List<ObservedStatusChange> queuedChanges = new ArrayList<ObservedStatusChange>();
        for (ObservedStatusChange change : listener.statusChanges) {
            if (change.change.getMessage().getMetaDataId() == 1
                    && change.change.getPreviousStatus() == Status.QUEUED) {
                queuedChanges.add(change);
            }
        }
        assertFalse(queuedChanges.isEmpty());
        assertTrue(queuedChanges.stream().allMatch(change -> change.queueCurrent));
        HandoffRecord handoff = listener.onlyHandoff(HandoffKind.DESTINATION_QUEUE);
        assertSame(handoff.receipt, queue.receipt);
        listener.assertEveryHandoffSettledOnce();
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void filteredAndErrorEarlyExitsStillEndProcess() throws Exception {
        channel = defaultChannel(true, 1);
        TestFilterTransformer filtered = new TestFilterTransformer();
        filtered.setFiltered(true);
        channel.getSourceConnector().getFilterTransformerExecutor()
                .setFilterTransformer(filtered);
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.PROCESS, 1);
        assertEquals(LifecycleOutcome.FILTERED,
                listener.only(Kind.PROCESS).result.getOutcome());
        assertEquals(LifecycleOutcome.FILTERED,
                listener.only(Kind.DISPATCH).result.getOutcome());
        assertTrue(listener.started(Kind.CHAIN).isEmpty());
        assertTrue(listener.started(Kind.SEND).isEmpty());
        listener.assertEveryStartEndedOnce();

        stopAndRemoveCurrentChannel();
        listener.clear();
        channel = defaultChannel(true, 1);
        channel.getSourceConnector().getFilterTransformerExecutor().setFilterTransformer(
                new TestFilterTransformer() {
                    @Override
                    public FilterTransformerResult doFilterTransform(ConnectorMessage message)
                            throws FilterTransformerException {
                        throw new FilterTransformerException("source transform failed",
                                new IllegalStateException(), "safe test error",
                                FailureCategory.TRANSFORMER);
                    }
                });
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.PROCESS, 1);
        assertEquals(LifecycleOutcome.ERROR, listener.only(Kind.PROCESS).result.getOutcome());
        assertEquals(FailureCategory.TRANSFORMER,
                listener.only(Kind.PROCESS).result.getFailure().getFailureCategory());
        assertTrue(listener.started(Kind.CHAIN).isEmpty());
        assertTrue(listener.started(Kind.SEND).isEmpty());
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void overwriteAndImportReuseMessageIdButNeverIncarnationOrHandoff()
            throws Exception {
        channel = defaultChannel(true, 1);
        deployAndStart();
        TestSourceConnector source = (TestSourceConnector) channel.getSourceConnector();
        DispatchResult first = source.dispatchRawMessage(
                new RawMessage(TestUtils.TEST_HL7_MESSAGE));
        source.finishDispatch(first);

        RawMessage overwrite = new RawMessage(TestUtils.TEST_HL7_MESSAGE);
        overwrite.setImported(true);
        overwrite.setOverwrite(true);
        overwrite.setOriginalMessageId(first.getMessageId());
        DispatchResult second = source.dispatchRawMessage(overwrite);
        source.finishDispatch(second);

        listener.awaitEnded(Kind.DISPATCH, 2);
        assertEquals(first.getMessageId(), second.getMessageId());
        assertEquals(2, listener.sources.size());
        assertEquals(first.getMessageId(), listener.sources.get(0).getMessageId());
        assertEquals(second.getMessageId(), listener.sources.get(1).getMessageId());
        assertTrue(listener.sources.get(0).getMessageIncarnationId()
                != listener.sources.get(1).getMessageIncarnationId());
        assertTrue(listener.handoffs.isEmpty());
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void asyncAndInlineChainsShareIncarnationAndConsumeOnlyAsyncHandoff()
            throws Exception {
        channel = defaultChannel(true, 2);
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.CHAIN, 2);
        List<Scope> chains = listener.started(Kind.CHAIN);
        assertEquals(2, chains.size());
        assertEquals(1, chains.stream()
                .filter(scope -> scope.mode == ExecutionMode.ASYNC_CHAIN).count());
        assertEquals(1, chains.stream()
                .filter(scope -> scope.mode == ExecutionMode.SYNCHRONOUS).count());
        HandoffRecord handoff = listener.onlyHandoff(HandoffKind.ASYNC_CHAIN);
        Scope asynchronous = chains.stream()
                .filter(scope -> scope.mode == ExecutionMode.ASYNC_CHAIN).findFirst().get();
        assertSame(handoff.receipt, asynchronous.receipt);
        listener.assertEveryHandoffSettledOnce();
        listener.assertEveryStartEndedOnce();
        listener.assertOneIncarnation();
    }

    @Test
    public void immediateRetryAdvancesAttemptWithoutCreatingHandoff() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        channel = channelWithDestination(true, new TestDestinationConnector() {
            @Override
            public Response send(com.mirth.connect.donkey.model.channel.ConnectorProperties props,
                    ConnectorMessage message) {
                return new Response(sends.incrementAndGet() == 1 ? Status.ERROR : Status.SENT,
                        "response");
            }
        }, properties -> {
            properties.setQueueEnabled(true);
            properties.setSendFirst(true);
            properties.setRetryCount(1);
            properties.setRetryIntervalMillis(0);
        });
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.SEND, 2);
        assertEquals(Arrays.asList(1L, 2L), listener.attempts(Kind.SEND));
        assertTrue(listener.handoffs.isEmpty());
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void queuedRetryAndRotationAdvanceAttemptAndPairEachHandoff() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        channel = channelWithDestination(true, new TestDestinationConnector() {
            @Override
            public Response send(com.mirth.connect.donkey.model.channel.ConnectorProperties props,
                    ConnectorMessage message) {
                return new Response(sends.incrementAndGet() < 3 ? Status.QUEUED : Status.SENT,
                        "response");
            }
        }, properties -> {
            properties.setQueueEnabled(true);
            properties.setSendFirst(true);
            properties.setRetryCount(0);
            properties.setRetryIntervalMillis(1);
            properties.setRotate(true);
        });
        deployAndStart();

        ((TestSourceConnector) channel.getSourceConnector())
                .readTestMessage(TestUtils.TEST_HL7_MESSAGE);

        listener.awaitEnded(Kind.QUEUE, 2);
        awaitValue(sends, 3);
        listener.awaitEnded(Kind.SEND, 3);
        assertEquals(Arrays.asList(1L, 2L, 3L), listener.attempts(Kind.SEND));
        assertEquals(Arrays.asList(2L, 3L), listener.attempts(Kind.QUEUE));
        assertEquals(Arrays.asList(2L, 3L), listener.handoffAttempts());
        listener.assertEveryHandoffSettledOnce();
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void heldRetryHasExplicitReasonAndRetainsCarrier() throws Exception {
        assertRetryDisposition(false, false);
    }

    @Test
    public void rotationHasExplicitReasonAndReleasesCarrier() throws Exception {
        assertRetryDisposition(true, false);
    }

    @Test
    public void rotationDecisionSurvivesCallbackChangingConfiguration() throws Exception {
        assertRetryDisposition(true, true);
    }

    @Test
    public void heldRetryDecisionSurvivesCallbackChangingConfiguration() throws Exception {
        assertRetryDisposition(false, true);
    }

    @Test
    public void committedRotationSurvivesDaoCloseFailure() throws Exception {
        assertRetryDisposition(true, false, true);
    }

    @Test
    public void committedHeldRetrySurvivesDaoCloseFailure() throws Exception {
        assertRetryDisposition(false, false, true);
    }

    private void assertRetryDisposition(boolean rotate, boolean flipDuringCreation) throws Exception {
        assertRetryDisposition(rotate, flipDuringCreation, false);
    }

    private void assertRetryDisposition(boolean rotate, boolean flipDuringCreation,
            boolean failDaoClose) throws Exception {
        AtomicInteger sends = new AtomicInteger();
        channel = channelWithDestination(true, new TestDestinationConnector() {
            @Override
            public Response send(com.mirth.connect.donkey.model.channel.ConnectorProperties props,
                    ConnectorMessage message) {
                return new Response(sends.incrementAndGet() < 3 ? Status.QUEUED : Status.SENT,
                        "response");
            }
        }, properties -> {
            properties.setQueueEnabled(true);
            properties.setSendFirst(true);
            properties.setRetryCount(0);
            properties.setRetryIntervalMillis(1);
            properties.setRotate(rotate);
        });
        var destination = destination(channel, 0);
        List<Boolean> retainedAtEnd = new CopyOnWriteArrayList<>();
        listener.endObserver = scope -> {
            if (scope.kind == Kind.QUEUE && scope.message.getSendAttempts() == 1) {
                retainedAtEnd.add(destination.getQueue().isCheckedOut(scope.message.getMessageId()));
            }
        };
        listener.handoffObserver = info -> {
            if (flipDuringCreation && info.getKind() == HandoffKind.DESTINATION_QUEUE
                    && info.getNextSendAttempt() == 3) {
                ((TestConnectorProperties) destination.getConnectorProperties())
                        .getDestinationConnectorProperties().setRotate(!rotate);
            }
        };
        deployAndStart();
        AtomicInteger closeFailures = new AtomicInteger();
        if (failDaoClose) {
            Field daoFactoryField = com.mirth.connect.donkey.server.channel.DestinationConnector.class
                    .getDeclaredField("daoFactory");
            daoFactoryField.setAccessible(true);
            Object originalFactory = daoFactoryField.get(destination);
            daoFactoryField.set(destination, java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {com.mirth.connect.donkey.server.data.DonkeyDaoFactory.class},
                    (proxy, method, args) -> {
                        Object returned;
                        try {
                            returned = method.invoke(originalFactory, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (!method.getName().equals("getDao")) return returned;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {com.mirth.connect.donkey.server.data.DonkeyDao.class},
                                (daoProxy, daoMethod, daoArgs) -> {
                                    Object daoResult;
                                    try {
                                        daoResult = daoMethod.invoke(returned, daoArgs);
                                    } catch (java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                    if (daoMethod.getName().equals("close")
                                            && closeFailures.compareAndSet(0, 1)) {
                                        throw new IllegalStateException("injected close after committed queue attempt");
                                    }
                                    return daoResult;
                                });
                    }));
        }
        ((TestSourceConnector) channel.getSourceConnector()).readTestMessage(TestUtils.TEST_HL7_MESSAGE);
        listener.awaitEnded(Kind.QUEUE, 2);
        assertEquals(failDaoClose ? 1 : 0, closeFailures.get());
        assertEquals(2, listener.handoffs.size());
        assertEquals(com.mirth.connect.donkey.server.channel.lifecycle.HandoffCreateReason.DESTINATION_ENQUEUE,
                listener.handoffs.get(0).info.getCreateReason());
        assertEquals(rotate
                ? com.mirth.connect.donkey.server.channel.lifecycle.HandoffCreateReason.DESTINATION_ROTATION
                : com.mirth.connect.donkey.server.channel.lifecycle.HandoffCreateReason.DESTINATION_RETRY,
                listener.handoffs.get(1).info.getCreateReason());
        assertEquals(Arrays.asList(!rotate), retainedAtEnd);
        listener.assertEveryHandoffSettledOnce();
        listener.assertEveryStartEndedOnce();
    }

    @Test
    public void stoppedRotatingQueueStillReleasesItsAcquiredCarrier() throws Exception {
        assertStoppedRotationReleasesCarrier(false);
    }

    @Test
    public void interruptedRotatingQueueStillReleasesItsAcquiredCarrier() throws Exception {
        assertStoppedRotationReleasesCarrier(true);
    }

    private void assertStoppedRotationReleasesCarrier(boolean interrupt) throws Exception {
        AtomicInteger sends = new AtomicInteger();
        channel = channelWithDestination(true, new TestDestinationConnector() {
            @Override
            public Response send(com.mirth.connect.donkey.model.channel.ConnectorProperties props,
                    ConnectorMessage message) {
                if (sends.incrementAndGet() == 2) {
                    if (interrupt) {
                        MessageLifecycleHooksTest.<RuntimeException>throwForTest(
                                new InterruptedException("injected connector interruption"));
                    } else {
                        updateCurrentState(com.mirth.connect.donkey.model.channel.DeployedState.STOPPING);
                    }
                }
                return new Response(Status.QUEUED, "response");
            }
        }, properties -> {
            properties.setQueueEnabled(true);
            properties.setSendFirst(true);
            properties.setRetryCount(0);
            properties.setRetryIntervalMillis(1);
            properties.setRotate(true);
        });
        deployAndStart();
        ((TestSourceConnector) channel.getSourceConnector()).readTestMessage(TestUtils.TEST_HL7_MESSAGE);
        listener.awaitEnded(Kind.QUEUE, 1);
        assertEquals(1, listener.handoffs.size());
        assertFalse("A rotating queue must release its acquired carrier even when stopping",
                destination(channel, 0).getQueue().isCheckedOut(listener.sources.get(0).getMessageId()));
        listener.assertEveryHandoffSettledOnce();
        listener.assertEveryStartEndedOnce();
    }

    // TestDestinationConnector narrows the production send() throws clause; preserve
    // the actual checked exception so this test exercises the production catch branch.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwForTest(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    public void rejectedAsyncChainSubmissionCancelsHandoffAndEndsInterruptedScopes()
            throws Exception {
        channel = defaultChannel(true, 2);
        deployAndStart();
        Field executorField = Channel.class.getDeclaredField("channelExecutor");
        executorField.setAccessible(true);
        ((ExecutorService) executorField.get(channel)).shutdownNow();

        try {
            ((TestSourceConnector) channel.getSourceConnector())
                    .readTestMessage(TestUtils.TEST_HL7_MESSAGE);
        } catch (ChannelException expected) {
            // The rejected async chain is deliberately translated to interruption.
        } finally {
            Thread.interrupted();
        }

        listener.awaitEnded(Kind.PROCESS, 1);
        assertEquals(LifecycleOutcome.INTERRUPTED,
                listener.only(Kind.PROCESS).result.getOutcome());
        HandoffRecord handoff = listener.onlyHandoff(HandoffKind.ASYNC_CHAIN);
        assertEquals(0, handoff.consumed.get());
        assertEquals(1, handoff.cancelled.get());
        assertEquals(com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationReason.SUBMISSION_REJECTED,
                handoff.cancellation.getReason());
        listener.assertEveryStartEndedOnce();
    }

    private TestChannel defaultChannel(boolean respondAfterProcessing, int chains)
            throws Exception {
        return TestUtils.createDefaultChannel(nextChannelId(), TestUtils.DEFAULT_SERVER_ID,
                respondAfterProcessing, chains, 1);
    }

    private TestChannel channelWithDestination(boolean respondAfterProcessing,
            TestDestinationConnector replacement, PropertiesCustomizer customizer) throws Exception {
        TestChannel result = defaultChannel(respondAfterProcessing, 1);
        TestConnectorProperties connectorProperties = new TestConnectorProperties();
        customizer.customize(connectorProperties.getDestinationConnectorProperties());
        replacement.setChannel(result);
        TestUtils.initDestinationConnector(replacement, result.getChannelId(),
                result.getServerId(), connectorProperties, TestUtils.DEFAULT_DESTINATION_NAME,
                new TestDataType(), new TestDataType(), new TestResponseTransformer(), 1);
        replacement.setMetaDataReplacer(result.getSourceConnector().getMetaDataReplacer());
        replacement.setMetaDataColumns(result.getMetaDataColumns());
        replacement.setFilterTransformerExecutor(TestUtils.createDefaultFilterTransformerExecutor());
        result.getDestinationChainProviders().get(0).addDestination(1, replacement);
        return result;
    }

    private static TestDestinationConnector destination(TestChannel value, int chainIndex) {
        return (TestDestinationConnector) value.getDestinationChainProviders().get(chainIndex)
                .getDestinationConnectors().values().iterator().next();
    }

    private void deployAndStart() throws Exception {
        channel.deploy();
        channel.start(null);
    }

    private void stopAndRemoveCurrentChannel() throws Exception {
        channel.stop();
        channel.undeploy();
        ChannelController.getInstance().removeChannel(channel.getChannelId());
        channel = null;
    }

    private static String nextChannelId() {
        return "lifecycle-hooks-" + CHANNEL_SEQUENCE.incrementAndGet();
    }

    private static void awaitValue(AtomicInteger value, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (value.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, value.get());
    }

    private void assertCounts(Object... expected) {
        for (int i = 0; i < expected.length; i += 2) {
            assertEquals(expected[i].toString(), expected[i + 1],
                    listener.started((Kind) expected[i]).size());
        }
    }

    private interface PropertiesCustomizer {
        void customize(DestinationConnectorProperties properties);
    }

    private enum Kind {
        DISPATCH,
        PROCESS,
        FILTER,
        CHAIN,
        QUEUE,
        SEND
    }

    private static final class RecordingListener implements MessageLifecycleListener {
        private java.util.function.Consumer<HandoffInfo> handoffObserver = info -> {};
        private java.util.function.Consumer<Scope> endObserver = scope -> {};
        private final List<Scope> scopes = new CopyOnWriteArrayList<Scope>();
        private final List<MessageInfo> sources = new CopyOnWriteArrayList<MessageInfo>();
        private final List<HandoffRecord> handoffs = new CopyOnWriteArrayList<HandoffRecord>();
        private final List<ObservedStatusChange> statusChanges =
                new CopyOnWriteArrayList<ObservedStatusChange>();
        private final ThreadLocal<Integer> queueDepth = new ThreadLocal<Integer>() {
            @Override
            protected Integer initialValue() {
                return 0;
            }
        };

        @Override
        public LifecycleHandle onDispatchStart(
                com.mirth.connect.donkey.server.channel.lifecycle.DispatchInfo dispatch) {
            return start(Kind.DISPATCH, null, null, HandoffReceipt.NOOP, 0);
        }

        @Override
        public void onSourceMessageCreated(MessageInfo source) {
            sources.add(source);
        }

        @Override
        public LifecycleHandle onProcessStart(
                com.mirth.connect.donkey.server.channel.lifecycle.ProcessInfo process,
                HandoffReceipt receipt) {
            consume(receipt);
            return start(Kind.PROCESS, process.getMessage(), process.getExecutionMode(), receipt,
                    0);
        }

        @Override
        public LifecycleHandle onFilterTransformerStart(MessageInfo message) {
            return start(Kind.FILTER, message, null, HandoffReceipt.NOOP, 0);
        }

        @Override
        public LifecycleHandle onDestinationChainStart(
                com.mirth.connect.donkey.server.channel.lifecycle.ChainInfo chain,
                HandoffReceipt receipt) {
            consume(receipt);
            return start(Kind.CHAIN, chain.getMessage(), chain.getExecutionMode(), receipt, 0);
        }

        @Override
        public LifecycleHandle onDestinationQueueStart(
                com.mirth.connect.donkey.server.channel.lifecycle.QueueInfo queue,
                HandoffReceipt receipt) {
            consume(receipt);
            return start(Kind.QUEUE, queue.getMessage(), queue.getExecutionMode(), receipt,
                    queue.getNextSendAttempt());
        }

        @Override
        public LifecycleHandle onSendStart(
                com.mirth.connect.donkey.server.channel.lifecycle.SendInfo send) {
            return start(Kind.SEND, send.getMessage(), send.getExecutionMode(),
                    HandoffReceipt.NOOP, send.getAttempt());
        }

        @Override
        public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
            Receipt receipt = new Receipt();
            handoffs.add(new HandoffRecord(handoff, receipt));
            handoffObserver.accept(handoff);
            return receipt;
        }

        @Override
        public void onHandoffCancelled(HandoffCancellation cancellation,
                HandoffReceipt receipt) {
            HandoffRecord record = find(receipt);
            if (record != null) {
                record.cancellation = cancellation;
                record.cancelled.incrementAndGet();
            }
        }

        @Override
        public void onStatusChanged(StatusChangeInfo change) {
            statusChanges.add(new ObservedStatusChange(change, queueDepth.get() > 0));
        }

        private LifecycleHandle start(Kind kind, MessageInfo message, ExecutionMode mode,
                HandoffReceipt receipt, long attempt) {
            boolean queueCurrent = queueDepth.get() > 0;
            if (kind == Kind.QUEUE) {
                queueDepth.set(queueDepth.get() + 1);
            }
            Scope scope = new Scope(kind, message, mode, receipt, attempt,
                    Thread.currentThread().getId(), queueCurrent || kind == Kind.QUEUE);
            scopes.add(scope);
            return result -> {
                scope.result = result;
                scope.endThread = Thread.currentThread().getId();
                endObserver.accept(scope);
                scope.ends.incrementAndGet();
                if (kind == Kind.QUEUE) {
                    queueDepth.set(queueDepth.get() - 1);
                }
            };
        }

        private void consume(HandoffReceipt receipt) {
            HandoffRecord record = find(receipt);
            if (record != null) {
                record.consumed.incrementAndGet();
            }
        }

        private HandoffRecord find(HandoffReceipt receipt) {
            for (HandoffRecord record : handoffs) {
                if (record.receipt == receipt) {
                    return record;
                }
            }
            return null;
        }

        private List<Scope> started(Kind kind) {
            List<Scope> result = new ArrayList<Scope>();
            for (Scope scope : scopes) {
                if (scope.kind == kind) {
                    result.add(scope);
                }
            }
            return result;
        }

        private Scope only(Kind kind) {
            List<Scope> matches = started(kind);
            assertEquals(kind.toString(), 1, matches.size());
            return matches.get(0);
        }

        private HandoffRecord onlyHandoff(HandoffKind kind) {
            List<HandoffRecord> matches = new ArrayList<HandoffRecord>();
            for (HandoffRecord handoff : handoffs) {
                if (handoff.info.getKind() == kind) {
                    matches.add(handoff);
                }
            }
            assertEquals(kind.toString(), 1, matches.size());
            return matches.get(0);
        }

        private List<Long> attempts(Kind kind) {
            List<Long> attempts = new ArrayList<Long>();
            for (Scope scope : started(kind)) {
                attempts.add(scope.attempt);
            }
            return attempts;
        }

        private List<Long> handoffAttempts() {
            List<Long> attempts = new ArrayList<Long>();
            for (HandoffRecord handoff : handoffs) {
                if (handoff.info.getKind() == HandoffKind.DESTINATION_QUEUE) {
                    attempts.add(handoff.info.getNextSendAttempt());
                }
            }
            return attempts;
        }

        private void awaitEnded(Kind kind, int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            while (System.nanoTime() < deadline) {
                int ended = 0;
                for (Scope scope : started(kind)) {
                    if (scope.ends.get() == 1) {
                        ended++;
                    }
                }
                if (ended >= count) {
                    return;
                }
                Thread.sleep(10);
            }
            assertEquals(kind + " ended scopes", count,
                    started(kind).stream().filter(scope -> scope.ends.get() == 1).count());
        }

        private void assertEveryStartEndedOnce() {
            for (Scope scope : scopes) {
                assertNotNull(scope.kind + " result", scope.result);
                assertEquals(scope.kind + " end count", 1, scope.ends.get());
            }
        }

        private void assertAllEndedOnStartingThread() {
            for (Scope scope : scopes) {
                assertEquals(scope.kind + " thread", scope.startThread, scope.endThread);
            }
        }

        private void assertEveryHandoffSettledOnce() {
            for (HandoffRecord handoff : handoffs) {
                assertEquals(handoff.info.getKind().toString(), 1,
                        handoff.consumed.get() + handoff.cancelled.get());
            }
        }

        private void assertOneIncarnation() {
            long incarnation = sources.get(0).getMessageIncarnationId();
            assertTrue(incarnation > 0);
            for (Scope scope : scopes) {
                if (scope.message != null) {
                    assertEquals(scope.kind.toString(), incarnation,
                            scope.message.getMessageIncarnationId());
                }
                if (scope.result != null && scope.result.getFinalMessage() != null) {
                    assertEquals(scope.kind.toString(), incarnation,
                            scope.result.getFinalMessage().getMessageIncarnationId());
                }
            }
        }

        private void clear() {
            scopes.clear();
            sources.clear();
            handoffs.clear();
            statusChanges.clear();
        }
    }

    private static final class Scope {
        private final Kind kind;
        private final MessageInfo message;
        private final ExecutionMode mode;
        private final HandoffReceipt receipt;
        private final long attempt;
        private final long startThread;
        private final boolean queueScopeCurrent;
        private final AtomicInteger ends = new AtomicInteger();
        private volatile long endThread;
        private volatile LifecycleResult result;

        private Scope(Kind kind, MessageInfo message, ExecutionMode mode,
                HandoffReceipt receipt, long attempt, long startThread,
                boolean queueScopeCurrent) {
            this.kind = kind;
            this.message = message;
            this.mode = mode;
            this.receipt = receipt;
            this.attempt = attempt;
            this.startThread = startThread;
            this.queueScopeCurrent = queueScopeCurrent;
        }
    }

    private static final class HandoffRecord {
        private final HandoffInfo info;
        private final Receipt receipt;
        private final AtomicInteger consumed = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private volatile HandoffCancellation cancellation;

        private HandoffRecord(HandoffInfo info, Receipt receipt) {
            this.info = info;
            this.receipt = receipt;
        }
    }

    private static final class Receipt implements HandoffReceipt {}

    private static final class ObservedStatusChange {
        private final StatusChangeInfo change;
        private final boolean queueCurrent;

        private ObservedStatusChange(StatusChangeInfo change, boolean queueCurrent) {
            this.change = change;
            this.queueCurrent = queueCurrent;
        }
    }
}
