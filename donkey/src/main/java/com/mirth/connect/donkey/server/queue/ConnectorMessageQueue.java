/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.donkey.server.queue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mirth.connect.donkey.model.event.MessageEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffBundle;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationBatch;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationReason;
import com.mirth.connect.donkey.server.event.EventDispatcher;
import com.mirth.connect.donkey.server.event.MessageEvent;

public abstract class ConnectorMessageQueue {

    private static final HandoffCancellationBatch[] NO_CANCELLATIONS =
            new HandoffCancellationBatch[0];

    protected Map<Long, ConnectorMessage> buffer = new LinkedHashMap<Long, ConnectorMessage>();
    protected Integer size;
    protected ConnectorMessageQueueDataSource dataSource;
    protected final AtomicBoolean timeoutLock = new AtomicBoolean(false);
    protected EventDispatcher eventDispatcher = Donkey.getInstance().getEventDispatcher();
    protected String channelId;
    protected Integer metaDataId;

    private int bufferCapacity = 1000;
    private boolean reachedCapacity = false;
    private boolean invalidated = false;
    private final List<HandoffCancellationBatch> pendingHandoffCancellations =
            new ArrayList<HandoffCancellationBatch>();

    protected abstract ConnectorMessage pollFirstValue();

    protected void reset() {}

    public int getBufferSize() {
        return buffer.size();
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }

    public void setBufferCapacity(int bufferCapacity) {
        List<HandoffCancellationBatch> cancellations = null;
        try {
            synchronized (this) {
                if (bufferCapacity > 0) {
                    if (bufferCapacity < this.bufferCapacity) {
                        cancellations = claimBufferedHandoffsLocked(
                                HandoffCancellationReason.CAPACITY_REDUCED);
                        buffer.clear();
                    }

                    this.bufferCapacity = bufferCapacity;
                }
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
    }

    public ConnectorMessageQueueDataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(ConnectorMessageQueueDataSource dataSource) {
        channelId = dataSource.getChannelId();
        metaDataId = dataSource.getMetaDataId();

        this.dataSource = dataSource;
        invalidate(false, true);
    }

    public synchronized void updateSize() {
        size = dataSource.getSize();
    }

    public synchronized void updateSizeIfEmpty() {
        if (size == null || size == 0) {
            updateSize();
        }
    }

    public void invalidate(boolean updateSize, boolean reset) {
        List<HandoffCancellationBatch> cancellations = null;
        try {
            synchronized (this) {
                cancellations = invalidateLocked(updateSize, reset);
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
    }

    /**
     * Invalidates while an enclosing transaction already owns this queue monitor. The caller must
     * deliver the returned cancellations only after releasing that monitor.
     */
    public HandoffCancellationBatch[] invalidateWithHandoffsLocked(boolean updateSize,
            boolean reset) {
        requireQueueMonitor();
        return toArray(invalidateLocked(updateSize, reset));
    }

    private List<HandoffCancellationBatch> invalidateLocked(boolean updateSize, boolean reset) {
        List<HandoffCancellationBatch> cancellations = claimBufferedHandoffsLocked(
                HandoffCancellationReason.INVALIDATED);
        buffer.clear();

        if (reset) {
            reset();
        }

        size = null;
        invalidated = true;

        if (updateSize) {
            eventDispatcher.dispatchEvent(new MessageEvent(channelId, metaDataId, MessageEventType.QUEUED, (long) size(), true));
        }
        return cancellations;
    }

    public synchronized boolean contains(ConnectorMessage connectorMessage) {
        return buffer.containsKey(connectorMessage.getMessageId());
    }

    public boolean isEmpty() {
        if (size == null) {
            updateSize();
        }

        return (size == 0);
    }

    public int size() {
        if (size == null) {
            if (dataSource == null) {
                return 0;
            }
            updateSize();
        }

        return size;
    }

    protected void incrementActualSize() {
        size++;
    }

    protected void decrementActualSize() {
        size--;
    }

    public void add(ConnectorMessage connectorMessage) {
        HandoffCancellationBatch[] cancellations = null;
        try {
            synchronized (this) {
                cancellations = offerWithHandoffLocked(connectorMessage, null);
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
    }

    /**
     * Atomically associates a handoff only when the exact object is retained in the bounded
     * buffer. The caller owns this queue monitor and delivers returned cancellations afterward.
     */
    public HandoffCancellationBatch[] offerWithHandoffLocked(ConnectorMessage connectorMessage,
            HandoffBundle handoff) {
        requireQueueMonitor();
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();
        boolean retained = false;
        boolean handoffHandled = handoff == null;

        try {
            if (invalidated) {
                /*
                 * If the buffer's size was already updated after an invalidate, then we need to
                 * increment the size by one in order to account for the new message that was just
                 * added, since this method is only ever called after a new message is added to the
                 * database
                 */
                if (size != null) {
                    incrementActualSize();
                }

                /*
                 * If the buffer was never filled after an invalidate, we can't just insert the
                 * message directly into the buffer because there could be messages that should
                 * process before it. Therefore we'll just fill the buffer to resync it with the
                 * database. This method can only be called after a new message was added to the
                 * database
                 */
                cancellations.addAll(fillBufferLocked(
                        HandoffCancellationReason.REFILL_DISCARDED));
                ConnectorMessage refilled = buffer.get(connectorMessage.getMessageId());
                if (refilled == connectorMessage) {
                    retained = true;
                } else if (refilled != null && handoff != null) {
                    claimHandoffLocked(refilled.takeLifecycleHandoffBundle(),
                            HandoffCancellationReason.DISPLACED, cancellations);
                    // Replacing a value for an existing LinkedHashMap key preserves queue order
                    // while retaining the exact object carrying this producer's lifecycle state.
                    buffer.put(connectorMessage.getMessageId(), connectorMessage);
                    retained = true;
                }
            } else {
                if (size == null) {
                    updateSize();
                }
                if (!reachedCapacity) {
                    if (size < bufferCapacity && !dataSource.isQueueRotated()) {
                        if (canAddNewMessageToBuffer(connectorMessage)) {
                            ConnectorMessage displaced = buffer.put(
                                    connectorMessage.getMessageId(), connectorMessage);
                            if (displaced != null && displaced != connectorMessage) {
                                claimHandoffLocked(displaced.takeLifecycleHandoffBundle(),
                                        HandoffCancellationReason.DISPLACED, cancellations);
                            }
                            retained = true;

                            // If a timed poll is waiting, notify it that an item was added.
                            if (timeoutLock.get()) {
                                synchronized (timeoutLock) {
                                    timeoutLock.notifyAll();
                                    timeoutLock.set(false);
                                }
                            }
                        }
                    } else {
                        reachedCapacity = true;
                    }
                }
                incrementActualSize();
            }

            if (retained) {
                HandoffBundle previous = connectorMessage.takeLifecycleHandoffBundle();
                if (previous != null && previous != handoff) {
                    claimHandoffLocked(previous, HandoffCancellationReason.DISPLACED,
                            cancellations);
                }
                connectorMessage.setLifecycleHandoffBundle(handoff);
                handoffHandled = true;
            } else {
                claimHandoffLocked(handoff, HandoffCancellationReason.PERSISTED_ONLY,
                        cancellations);
                handoffHandled = true;
            }

            eventDispatcher.dispatchEvent(new MessageEvent(channelId, metaDataId,
                    MessageEventType.QUEUED, (long) size(), false));
            return toArray(cancellations);
        } finally {
            if (!handoffHandled) {
                claimHandoffLocked(handoff, HandoffCancellationReason.TRANSFER_FAILED,
                        cancellations);
            }
        }
    }

    protected boolean canAddNewMessageToBuffer(ConnectorMessage connectorMessage) {
        return true;
    }

    public void fillBuffer() {
        List<HandoffCancellationBatch> cancellations = null;
        try {
            synchronized (this) {
                cancellations = fillBufferLocked(HandoffCancellationReason.REFILL_DISCARDED);
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
    }

    protected List<HandoffCancellationBatch> fillBufferLocked(
            HandoffCancellationReason discardReason) {
        if (size == null) {
            updateSize();
        }

        Map<Long, ConnectorMessage> replacement = dataSource.getItems(0,
                Math.min(bufferCapacity, size));
        List<HandoffCancellationBatch> cancellations = claimBufferedHandoffsLocked(discardReason);
        invalidated = false;
        buffer = replacement;

        if (buffer.size() == size) {
            reachedCapacity = false;
        }

        // If there is a poll with timeout waiting, notify that an item was added to the buffer.
        if (buffer.size() > 0 && timeoutLock.get()) {
            synchronized (timeoutLock) {
                timeoutLock.notifyAll();
                timeoutLock.set(false);
            }
        }
        return cancellations;
    }

    public void deliverHandoffCancellations(HandoffCancellationBatch[] cancellations) {
        if (Thread.holdsLock(this)) {
            if (cancellations != null) {
                for (HandoffCancellationBatch cancellation : cancellations) {
                    pendingHandoffCancellations.add(cancellation);
                }
            }
            return;
        }

        List<HandoffCancellationBatch> delivery = new ArrayList<HandoffCancellationBatch>();
        synchronized (this) {
            if (!pendingHandoffCancellations.isEmpty()) {
                delivery.addAll(pendingHandoffCancellations);
                pendingHandoffCancellations.clear();
            }
        }
        if (cancellations != null) {
            for (HandoffCancellationBatch cancellation : cancellations) {
                delivery.add(cancellation);
            }
        }
        Throwable firstFailure = null;
        for (HandoffCancellationBatch cancellation : delivery) {
            try {
                cancellation.deliver();
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    if (isFatal(failure) && !isFatal(firstFailure)) {
                        Throwable previousFailure = firstFailure;
                        firstFailure = failure;
                        failure = previousFailure;
                    }
                    try {
                        firstFailure.addSuppressed(failure);
                    } catch (RuntimeException ignored) {
                        // Preserve the first failure if suppression is unavailable.
                    }
                }
            }
        }
        if (firstFailure != null) {
            rethrowUnchecked(firstFailure);
        }
    }

    protected void deliverHandoffCancellations(List<HandoffCancellationBatch> cancellations) {
        if (cancellations != null) {
            deliverHandoffCancellations(toArray(cancellations));
        }
    }

    protected List<HandoffCancellationBatch> claimBufferedHandoffsLocked(
            HandoffCancellationReason reason) {
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();
        for (ConnectorMessage message : buffer.values()) {
            claimHandoffLocked(message.takeLifecycleHandoffBundle(), reason, cancellations);
        }
        return cancellations;
    }

    protected void claimHandoffLocked(HandoffBundle bundle, HandoffCancellationReason reason,
            List<HandoffCancellationBatch> cancellations) {
        if (bundle != null) {
            HandoffCancellationBatch cancellation = bundle.claimCancellation(reason);
            cancellations.add(cancellation);
            pendingHandoffCancellations.add(cancellation);
        }
    }

    protected HandoffCancellationBatch[] toArray(
            List<HandoffCancellationBatch> cancellations) {
        if (!Thread.holdsLock(this)) {
            synchronized (this) {
                return toArrayLocked(cancellations);
            }
        }
        return toArrayLocked(cancellations);
    }

    private HandoffCancellationBatch[] toArrayLocked(
            List<HandoffCancellationBatch> cancellations) {
        if (cancellations == null || cancellations.isEmpty()) {
            return NO_CANCELLATIONS;
        }
        for (HandoffCancellationBatch cancellation : cancellations) {
            pendingHandoffCancellations.remove(cancellation);
        }
        return cancellations.toArray(new HandoffCancellationBatch[cancellations.size()]);
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private void requireQueueMonitor() {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException("queue monitor must be held");
        }
    }
}
