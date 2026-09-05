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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.StringUtils;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.event.MessageEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.server.event.MessageEvent;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffBundle;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationBatch;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffCancellationReason;
import com.mirth.connect.donkey.util.MessageMaps;
import com.mirth.connect.donkey.util.Serializer;
import com.mirth.connect.donkey.util.xstream.SerializerException;

public class DestinationQueue extends ConnectorMessageQueue {

    private String groupBy;
    private boolean regenerateTemplate;
    private Serializer serializer;
    private MessageMaps messageMaps;
    private Set<Long> checkedOut = new HashSet<Long>();
    private Set<Long> deleted = new HashSet<Long>();
    private boolean rotate = false;
    private int queueBuckets = 1;
    private List<Long> queueThreadIds;
    private HashFunction hashFunction;
    private Map<String, Integer> initialThreadAssignmentMap;

    /*
     * After deleting queued messages, the queue will get invalidated. When a queue thread is
     * processing a message, there is a short period of time between the point the non-QUEUED status
     * is committed to the database, and when the connector message is released from the queue. If
     * the invalidate (and subsequent size update) is done in this period, then the size will be
     * incorrect after the queue thread releases the connector message.
     * 
     * This read/write lock is meant to prevent this. When destination queue threads are about to
     * commit a non-QUEUED status to the database, they need to obtain a read lock. Multiple threads
     * can have concurrent read locks.
     * 
     * When the queue is invalidated due to messages being deleted, the caller first obtains the
     * write lock. This will block until all queue threads have released their respective read
     * locks. Then the invalidation and size update is done, without any queue threads being able to
     * commit statuses to the database.
     */
    private ReentrantReadWriteLock statusUpdateLock = new ReentrantReadWriteLock(true);

    public DestinationQueue(String groupBy, int threadCount, boolean regenerateTemplate, Serializer serializer, MessageMaps messageMaps) {
        this.groupBy = StringUtils.defaultString(groupBy);
        this.regenerateTemplate = regenerateTemplate;
        this.serializer = serializer;
        this.messageMaps = messageMaps;

        if (StringUtils.isNotBlank(groupBy)) {
            queueBuckets = threadCount;

            if (queueBuckets > 1) {
                queueThreadIds = new ArrayList<Long>(queueBuckets);
                hashFunction = Hashing.murmur3_32((int) System.currentTimeMillis());
                initialThreadAssignmentMap = new ConcurrentHashMap<String, Integer>(queueBuckets);
            }
        }
    }

    @Override
    protected ConnectorMessage pollFirstValue() {
        Iterator<Entry<Long, ConnectorMessage>> iterator = buffer.entrySet().iterator();

        while (iterator.hasNext()) {
            ConnectorMessage connectorMessage = iterator.next().getValue();

            /*
             * If there are multiple buckets, then the first value in the buffer may not be the
             * first value for this queue thread. In this case, iterate through the buffer and find
             * the first one that matches (if any).
             */
            if (queueBuckets > 1) {
                Integer bucket = getBucket(connectorMessage);

                if (bucket < queueThreadIds.size() && queueThreadIds.get(bucket).equals(Thread.currentThread().getId())) {
                    iterator.remove();
                    return connectorMessage;
                }
            } else {
                iterator.remove();
                return connectorMessage;
            }
        }

        return null;
    }

    public Lock getStatusUpdateLock() {
        return statusUpdateLock.readLock();
    }

    public Lock getInvalidationLock() {
        return statusUpdateLock.writeLock();
    }

    @Override
    protected void reset() {
        checkedOut.clear();
        deleted.clear();
        if (queueBuckets > 1) {
            queueThreadIds.clear();
        }
        if (rotate) {
            dataSource.getRotateThreadMap().clear();
        }
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    public synchronized void registerThreadId() {
        Long threadId = Thread.currentThread().getId();

        if (queueBuckets > 1) {
            queueThreadIds.add(threadId);
        }

        if (rotate) {
            dataSource.getRotateThreadMap().put(threadId, false);
        }
    }

    public boolean hasBeenRotated() {
        if (rotate) {
            // Check to see if the data source has rotated the queue
            Long threadId = Thread.currentThread().getId();
            Boolean rotated = dataSource.getRotateThreadMap().get(threadId);
            if (rotated == null || rotated) {
                if (rotated == null) {
                    rotated = false;
                }
                // Update the map, clearing the flag
                dataSource.getRotateThreadMap().put(threadId, false);
            }
            return rotated;
        }
        return false;
    }

    public ConnectorMessage acquire() {
        ConnectorMessage connectorMessage = null;
        boolean acquired = false;
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();

        try {
            synchronized (this) {
                if (size() - checkedOut.size() > 0) {
                    boolean bufferFilled = false;

                    do {
                        if (size == null) {
                            updateSize();
                        }

                        if (size > 0) {
                            connectorMessage = pollFirstValue();

                            /*
                             * A null value can mean every buffered item belongs to another queue
                             * bucket. Refill only when the buffer itself is empty.
                             */
                            if (connectorMessage == null && buffer.size() == 0) {
                                if (bufferFilled) {
                                    break;
                                }

                                cancellations.addAll(fillBufferLocked(
                                        HandoffCancellationReason.REFILL_DISCARDED));
                                bufferFilled = true;

                                connectorMessage = pollFirstValue();
                            }

                            if (connectorMessage != null && rotate) {
                                dataSource.setLastItem(connectorMessage);
                            }
                        }
                        if (connectorMessage != null
                                && checkedOut.contains(connectorMessage.getMessageId())) {
                            claimHandoffLocked(connectorMessage.takeLifecycleHandoffBundle(),
                                    HandoffCancellationReason.REFILL_DISCARDED, cancellations);
                        }
                    } while (connectorMessage != null
                            && checkedOut.contains(connectorMessage.getMessageId()));
                }

                if (connectorMessage != null) {
                    checkedOut.add(connectorMessage.getMessageId());
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

    public void release(ConnectorMessage connectorMessage, boolean finished) {
        HandoffCancellationBatch[] cancellations = null;
        try {
            synchronized (this) {
                cancellations = releaseWithHandoffLocked(connectorMessage, finished, null);
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
    }

    /** Releases an acquired object and atomically attaches a continuation handoff if retained. */
    public HandoffCancellationBatch[] releaseWithHandoffLocked(
            ConnectorMessage connectorMessage, boolean finished, HandoffBundle handoff) {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException("queue monitor must be held");
        }
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();
        boolean handoffHandled = handoff == null;
        try {
            if (connectorMessage != null) {
                if (size != null) {
                    Long messageId = connectorMessage.getMessageId();

                    if (finished) {
                        decrementActualSize();

                        ConnectorMessage removed = buffer.remove(messageId);
                        if (removed != null && removed != connectorMessage) {
                            claimHandoffLocked(removed.takeLifecycleHandoffBundle(),
                                    HandoffCancellationReason.REMOVED, cancellations);
                        }
                    } else {
                        if (buffer.containsKey(messageId)) {
                            ConnectorMessage displaced = buffer.put(messageId, connectorMessage);
                            if (displaced != null && displaced != connectorMessage) {
                                claimHandoffLocked(displaced.takeLifecycleHandoffBundle(),
                                        HandoffCancellationReason.DISPLACED, cancellations);
                            }
                        }

                        dataSource.rotateQueue();
                    }
                }

                HandoffBundle previous = connectorMessage.takeLifecycleHandoffBundle();
                if (previous != null && previous != handoff) {
                    claimHandoffLocked(previous, HandoffCancellationReason.REMOVED,
                            cancellations);
                }
                if (!finished && buffer.get(connectorMessage.getMessageId()) == connectorMessage) {
                    connectorMessage.setLifecycleHandoffBundle(handoff);
                } else {
                    claimHandoffLocked(handoff, finished ? HandoffCancellationReason.REMOVED
                            : HandoffCancellationReason.PERSISTED_ONLY, cancellations);
                }
                handoffHandled = true;

                checkedOut.remove(connectorMessage.getMessageId());

                if (finished) {
                    eventDispatcher.dispatchEvent(new MessageEvent(channelId, metaDataId,
                            MessageEventType.QUEUED, (long) size(), true));
                }
            }
            return toArray(cancellations);
        } finally {
            if (!handoffHandled) {
                claimHandoffLocked(handoff, HandoffCancellationReason.TRANSFER_FAILED,
                        cancellations);
            }
        }
    }

    public boolean isCheckedOut(Long messageId) {
        boolean isCheckedOut;
        List<HandoffCancellationBatch> cancellations = new ArrayList<HandoffCancellationBatch>();
        try {
            synchronized (this) {
                isCheckedOut = checkedOut.contains(messageId);

            /*
             * If the message is no longer checked out and it was previously marked as deleted, we
             * want to remove it from the deleted list as well as the buffer so that it does not get
             * acquired again.
             */
                if (!isCheckedOut && deleted.contains(messageId)) {
                    deleted.remove(messageId);
                    ConnectorMessage removed = buffer.remove(messageId);
                    if (removed != null) {
                        claimHandoffLocked(removed.takeLifecycleHandoffBundle(),
                                HandoffCancellationReason.REMOVED, cancellations);
                    }
                    updateSize();
                }
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
        return isCheckedOut;
    }

    public synchronized void markAsDeleted(Long messageId) {
        deleted.add(messageId);
    }

    public boolean releaseIfDeleted(ConnectorMessage connectorMessage) {
        boolean released = false;
        HandoffCancellationBatch[] cancellations = null;
        try {
            synchronized (this) {
                if (deleted.contains(connectorMessage.getMessageId())) {
                    cancellations = releaseWithHandoffLocked(connectorMessage, true, null);
                    released = true;
                }
            }
        } finally {
            deliverHandoffCancellations(cancellations);
        }
        return released;
    }

    private Integer getBucket(ConnectorMessage connectorMessage) {
        Integer bucket = connectorMessage.getQueueBucket();

        // Get the bucket if we haven't already, or if value replacement needs to be done
        if (bucket == null || regenerateTemplate) {
            String groupByVariable = groupBy;

            /*
             * If we're not regenerating connector properties, then we need to get the group by
             * variable from the actual persisted sent content, because it could be different than
             * what is being used in the currently deployed revision.
             */
            if (!regenerateTemplate) {
                try {
                    ConnectorProperties sentProperties = connectorMessage.getSentProperties();
                    if (sentProperties == null) {
                        sentProperties = serializer.deserialize(connectorMessage.getSent().getContent(), ConnectorProperties.class);
                        connectorMessage.setSentProperties(sentProperties);
                    }

                    groupByVariable = StringUtils.defaultString(((DestinationConnectorPropertiesInterface) sentProperties).getDestinationConnectorProperties().getThreadAssignmentVariable());
                } catch (SerializerException e) {
                }
            }

            String groupByValue = String.valueOf(messageMaps.get(groupByVariable, connectorMessage));

            // Attempt to get the bucket from the initial assignment map first
            bucket = initialThreadAssignmentMap.get(groupByValue);

            if (bucket == null) {
                /*
                 * If the initial assignment map isn't yet full, assign the next available queue
                 * bucket directly to the assignment value. Otherwise, calculate the bucket using
                 * the hash function.
                 */
                if (initialThreadAssignmentMap.size() < queueBuckets) {
                    synchronized (initialThreadAssignmentMap) {
                        int size = initialThreadAssignmentMap.size();
                        if (size < queueBuckets) {
                            bucket = size;
                            initialThreadAssignmentMap.put(groupByValue, bucket);
                        }
                    }
                }

                if (bucket == null) {
                    // Calculate the 32-bit hash, then reduce it to one of the buckets
                    bucket = Math.abs(hashFunction.hashUnencodedChars(groupByValue).asInt() % queueBuckets);
                }
            }

            connectorMessage.setQueueBucket(bucket);
        }

        return bucket;
    }
}
