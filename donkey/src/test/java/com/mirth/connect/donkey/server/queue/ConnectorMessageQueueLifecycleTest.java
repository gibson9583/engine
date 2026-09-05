/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.lifecycle.ExecutionMode;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffBundle;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellation;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationBatch;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationReason;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffKind;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffReceipt;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleHandle;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListener;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListeners;
import com.mirth.connect.donkey.server.channel.lifecycle.ProcessInfo;
import com.mirth.connect.donkey.server.event.EventDispatcher;

public class ConnectorMessageQueueLifecycleTest {
    @Test
    public void retainedObjectKeepsExactBundleForConsumer() {
        TestQueue queue = new TestQueue(false);
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger starts = new AtomicInteger();
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return HandoffReceipt.NOOP;
            }

            @Override
            public LifecycleHandle onProcessStart(ProcessInfo process, HandoffReceipt receipt) {
                assertFalse(Thread.holdsLock(queue));
                starts.incrementAndGet();
                return LifecycleHandle.NOOP;
            }
        });
        ConnectorMessage message = message(1);
        HandoffBundle handoff = sourceHandoff(listeners);

        HandoffCancellationBatch[] cancellations;
        synchronized (queue) {
            cancellations = queue.offerWithHandoffLocked(message, handoff);
        }
        queue.deliverHandoffCancellations(cancellations);

        assertSame(message, queue.buffered(1));
        HandoffBundle retained = message.takeLifecycleHandoffBundle();
        assertSame(handoff, retained);
        listeners.onProcessStart(new ProcessInfo(messageInfo(), ExecutionMode.SOURCE_QUEUE),
                retained);
        assertEquals(1, starts.get());
    }

    @Test
    public void persistedOnlyCancellationUsesOwnerAndRunsAfterUnlock() {
        TestQueue queue = new TestQueue(true);
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<HandoffCancellationReason> reason =
                new AtomicReference<HandoffCancellationReason>();
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return HandoffReceipt.NOOP;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt receipt) {
                assertFalse(Thread.holdsLock(queue));
                reason.set(cancellation.getReason());
                cancellations.incrementAndGet();
            }
        });
        HandoffBundle handoff = sourceHandoff(listeners);

        synchronized (queue) {
            HandoffCancellationBatch[] claimed =
                    queue.offerWithHandoffLocked(message(2), handoff);
            queue.deliverHandoffCancellations(claimed);
            assertEquals(0, cancellations.get());
        }
        queue.deliverHandoffCancellations((HandoffCancellationBatch[]) null);
        queue.deliverHandoffCancellations((HandoffCancellationBatch[]) null);

        assertEquals(1, cancellations.get());
        assertSame(HandoffCancellationReason.PERSISTED_ONLY, reason.get());
    }

    @Test
    public void failedRefillLeavesExistingHandoffClaimable() {
        TestQueue queue = new TestQueue(false);
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger cancellations = new AtomicInteger();
        listeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return HandoffReceipt.NOOP;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt receipt) {
                cancellations.incrementAndGet();
            }
        });
        ConnectorMessage message = message(3);
        HandoffBundle handoff = sourceHandoff(listeners);
        synchronized (queue) {
            queue.deliverHandoffCancellations(queue.offerWithHandoffLocked(message, handoff));
        }
        RuntimeException failure = new RuntimeException("refill failed");
        when(queue.dataSource.getItems(0, 1)).thenThrow(failure);

        try {
            queue.fillBuffer();
            fail("expected refill failure");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }

        assertEquals(0, cancellations.get());
        assertSame(handoff, message.takeLifecycleHandoffBundle());
        listeners.cancelHandoffs(handoff, HandoffCancellationReason.CANCELLED);
        assertEquals(1, cancellations.get());
    }

    @Test
    public void failedOfferClaimsUnattachedHandoffAndDeliversAfterUnlock() {
        TestQueue queue = new TestQueue(false);
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<HandoffCancellationReason> reason =
                new AtomicReference<HandoffCancellationReason>();
        listeners.register(cancellingListener(queue, cancellations, reason));
        HandoffBundle handoff = sourceHandoff(listeners);
        RuntimeException failure = new RuntimeException("offer failed");
        when(queue.dataSource.isQueueRotated()).thenThrow(failure);

        try {
            synchronized (queue) {
                queue.offerWithHandoffLocked(message(4), handoff);
            }
            fail("expected offer failure");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }
        assertEquals(0, cancellations.get());
        queue.deliverHandoffCancellations((HandoffCancellationBatch[]) null);

        assertEquals(1, cancellations.get());
        assertSame(HandoffCancellationReason.TRANSFER_FAILED, reason.get());
    }

    @Test
    public void failedPollCancelsSelectedObjectAfterUnlock() {
        SourceQueue queue = new SourceQueue();
        queue.dataSource = mock(ConnectorMessageQueueDataSource.class);
        queue.eventDispatcher = mock(EventDispatcher.class);
        queue.channelId = "channel";
        queue.metaDataId = 0;
        queue.size = 1;
        MessageLifecycleListeners listeners = new MessageLifecycleListeners();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<HandoffCancellationReason> reason =
                new AtomicReference<HandoffCancellationReason>();
        listeners.register(cancellingListener(queue, cancellations, reason));
        ConnectorMessage message = message(5);
        message.setLifecycleHandoffBundle(sourceHandoff(listeners));
        queue.buffer.put(message.getMessageId(), message);
        RuntimeException failure = new RuntimeException("queue event failed");
        doThrow(failure).when(queue.eventDispatcher).dispatchEvent(any());

        try {
            queue.poll();
            fail("expected poll failure");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }

        assertEquals(1, cancellations.get());
        assertSame(HandoffCancellationReason.TRANSFER_FAILED, reason.get());
    }

    @Test
    public void fatalCancellationDoesNotStrandLaterClaimedBatches() {
        TestQueue queue = new TestQueue(false);
        MessageLifecycleListeners firstListeners = new MessageLifecycleListeners();
        MessageLifecycleListeners secondListeners = new MessageLifecycleListeners();
        AtomicInteger firstCancellations = new AtomicInteger();
        AtomicInteger secondCancellations = new AtomicInteger();
        OutOfMemoryError fatal = new OutOfMemoryError("fatal callback");
        firstListeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return HandoffReceipt.NOOP;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt receipt) {
                firstCancellations.incrementAndGet();
                throw fatal;
            }
        });
        secondListeners.register(new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return HandoffReceipt.NOOP;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt receipt) {
                secondCancellations.incrementAndGet();
            }
        });
        HandoffCancellationBatch first = sourceHandoff(firstListeners).claimCancellation(
                HandoffCancellationReason.CANCELLED);
        HandoffCancellationBatch second = sourceHandoff(secondListeners).claimCancellation(
                HandoffCancellationReason.CANCELLED);

        try {
            queue.deliverHandoffCancellations(
                    new HandoffCancellationBatch[] { first, second });
            fail("expected fatal callback failure");
        } catch (OutOfMemoryError actual) {
            assertSame(fatal, actual);
        }

        assertEquals(1, firstCancellations.get());
        assertEquals(1, secondCancellations.get());
        queue.deliverHandoffCancellations(new HandoffCancellationBatch[] { first, second });
        assertEquals(1, firstCancellations.get());
        assertEquals(1, secondCancellations.get());
    }

    private static MessageLifecycleListener cancellingListener(ConnectorMessageQueue queue,
            AtomicInteger cancellations, AtomicReference<HandoffCancellationReason> reason) {
        return new MessageLifecycleListener() {
            @Override
            public HandoffReceipt onHandoffCreated(HandoffInfo handoff) {
                return HandoffReceipt.NOOP;
            }

            @Override
            public void onHandoffCancelled(HandoffCancellation cancellation,
                    HandoffReceipt receipt) {
                assertFalse(Thread.holdsLock(queue));
                reason.set(cancellation.getReason());
                cancellations.incrementAndGet();
            }
        };
    }

    private static ConnectorMessage message(long messageId) {
        return new ConnectorMessage("channel", "Channel", messageId, 0, "server",
                Calendar.getInstance(), Status.RECEIVED);
    }

    private static MessageInfo messageInfo() {
        return new MessageInfo("server", "channel", "Channel", 1, 0, "source",
                "HTTP Listener", 1, null, Status.RECEIVED, 0, 100L, null, null);
    }

    private static HandoffBundle sourceHandoff(MessageLifecycleListeners listeners) {
        return listeners.createHandoffs(listeners.captureToken(),
                new HandoffInfo(HandoffKind.SOURCE_QUEUE, messageInfo(), null, null));
    }

    private static final class TestQueue extends ConnectorMessageQueue {
        private TestQueue(boolean rotated) {
            dataSource = mock(ConnectorMessageQueueDataSource.class);
            when(dataSource.isQueueRotated()).thenReturn(rotated);
            eventDispatcher = mock(EventDispatcher.class);
            channelId = "channel";
            metaDataId = 0;
            size = 0;
        }

        @Override
        protected ConnectorMessage pollFirstValue() {
            return null;
        }

        private ConnectorMessage buffered(long messageId) {
            return buffer.get(messageId);
        }
    }
}
