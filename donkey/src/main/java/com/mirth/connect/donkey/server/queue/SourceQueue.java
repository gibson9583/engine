/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.donkey.server.queue;

import java.util.Collections;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.mirth.connect.donkey.model.event.MessageEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.server.event.MessageEvent;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationBatch;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationReason;

public class SourceQueue extends ConnectorMessageQueue {

    private Set<Long> checkedOut = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    @Override
    protected ConnectorMessage pollFirstValue() {
        Iterator<Entry<Long, ConnectorMessage>> iterator = buffer.entrySet().iterator();

        if (iterator.hasNext()) {
            ConnectorMessage connectorMessage = iterator.next().getValue();

            iterator.remove();

            return connectorMessage;
        }

        return null;
    }

    public ConnectorMessage poll() {
        ConnectorMessage connectorMessage = null;
        boolean acquired = false;
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();
        try {
            synchronized (this) {
                if (size == null) {
                    updateSize();
                }

                if (size > 0) {
                    connectorMessage = pollFirstValue();

                    // If the database has items but the buffer is empty, refill and retry.
                    if (connectorMessage == null) {
                        cancellations.addAll(fillBufferLocked(
                                HandoffCancellationReason.REFILL_DISCARDED));
                        connectorMessage = pollFirstValue();
                    }

                    /*
                     * Ensure the same message is not polled concurrently. The caller must later
                     * call finish to remove the message ID from the checked-out set.
                     */
                    while (connectorMessage != null
                            && checkedOut.contains(connectorMessage.getMessageId())) {
                        claimHandoffLocked(connectorMessage.takeLifecycleHandoffBundle(),
                                HandoffCancellationReason.REFILL_DISCARDED, cancellations);
                        connectorMessage = pollFirstValue();
                    }
                }

                if (connectorMessage != null) {
                    decrementActualSize();
                    checkedOut.add(connectorMessage.getMessageId());
                    eventDispatcher.dispatchEvent(new MessageEvent(channelId, metaDataId,
                            MessageEventType.QUEUED, (long) size(), true));
                }
                acquired = true;
            }
        } finally {
            if (!acquired && connectorMessage != null) {
                synchronized (this) {
                    checkedOut.remove(connectorMessage.getMessageId());
                    claimHandoffLocked(connectorMessage.takeLifecycleHandoffBundle(),
                            HandoffCancellationReason.TRANSFER_FAILED, cancellations);
                }
            }
            deliverHandoffCancellations(cancellations);
        }
        return connectorMessage;
    }

    public void finish(ConnectorMessage connectorMessage) {
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();
        try {
            synchronized (this) {
                if (connectorMessage != null) {
                    Long messageId = connectorMessage.getMessageId();

                    ConnectorMessage removed = buffer.remove(messageId);
                    if (removed != null) {
                        claimHandoffLocked(removed.takeLifecycleHandoffBundle(),
                                HandoffCancellationReason.REMOVED, cancellations);
                    }

                    checkedOut.remove(messageId);
                }
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
    }

    @Override
    protected void reset() {
        checkedOut.clear();
    }

    public synchronized void decrementSize() {
        if (size != null) {
            decrementActualSize();
        }

        eventDispatcher.dispatchEvent(new MessageEvent(channelId, metaDataId, MessageEventType.QUEUED, (long) size(), true));
    }

    public ConnectorMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        waitTimeout(timeout, unit);

        return poll();
    }

    private void waitTimeout(long timeout, TimeUnit unit) throws InterruptedException {
        /*
         * If there are no queued messages, then we want to wait. Otherwise, it's possible that
         * multiple queue threads all have messages checked out and the buffer is full. In this case
         * we also want to wait until at least one of the messages has finished.
         */
        if ((size == null || size == 0 || checkedOut.size() == getBufferCapacity()) && timeout > 0) {
            synchronized (timeoutLock) {
                timeoutLock.set(true);
                timeoutLock.wait(TimeUnit.MILLISECONDS.convert(timeout, unit));
            }
        }
    }
}
