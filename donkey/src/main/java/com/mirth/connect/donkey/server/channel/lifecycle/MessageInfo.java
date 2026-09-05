/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

import com.mirth.connect.donkey.model.message.Status;

/** A scalar snapshot of message and connector identity at one lifecycle boundary. */
public final class MessageInfo {
    private final String serverId;
    private final String channelId;
    private final String channelName;
    private final long messageId;
    private final int metaDataId;
    private final String connectorName;
    private final String connectorType;
    private final long messageIncarnationId;
    private final Integer chainId;
    private final Status status;
    private final int sendAttempts;
    private final Long receivedTimeMillis;
    private final Long sendTimeMillis;
    private final Long responseTimeMillis;

    public MessageInfo(String serverId, String channelId, String channelName, long messageId,
            int metaDataId, String connectorName, String connectorType,
            long messageIncarnationId, Integer chainId, Status status, int sendAttempts,
            Long receivedTimeMillis, Long sendTimeMillis, Long responseTimeMillis) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.channelName = Objects.requireNonNull(channelName, "channelName");
        this.messageId = messageId;
        if (metaDataId < 0) {
            throw new IllegalArgumentException("metaDataId must not be negative");
        }
        this.metaDataId = metaDataId;
        this.connectorName = Objects.requireNonNull(connectorName, "connectorName");
        this.connectorType = Objects.requireNonNull(connectorType, "connectorType");
        if (messageIncarnationId <= 0) {
            throw new IllegalArgumentException("messageIncarnationId must be positive");
        }
        this.messageIncarnationId = messageIncarnationId;
        if (chainId != null && chainId <= 0) {
            throw new IllegalArgumentException("chainId must be positive when present");
        }
        this.chainId = chainId;
        this.status = Objects.requireNonNull(status, "status");
        if (sendAttempts < 0) {
            throw new IllegalArgumentException("sendAttempts must not be negative");
        }
        this.sendAttempts = sendAttempts;
        this.receivedTimeMillis = receivedTimeMillis;
        this.sendTimeMillis = sendTimeMillis;
        this.responseTimeMillis = responseTimeMillis;
    }

    public String getServerId() {
        return serverId;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public long getMessageId() {
        return messageId;
    }

    public int getMetaDataId() {
        return metaDataId;
    }

    public String getConnectorName() {
        return connectorName;
    }

    public String getConnectorType() {
        return connectorType;
    }

    public long getMessageIncarnationId() {
        return messageIncarnationId;
    }

    public Integer getChainId() {
        return chainId;
    }

    public Status getStatus() {
        return status;
    }

    public int getSendAttempts() {
        return sendAttempts;
    }

    public Long getReceivedTimeMillis() {
        return receivedTimeMillis;
    }

    public Long getSendTimeMillis() {
        return sendTimeMillis;
    }

    public Long getResponseTimeMillis() {
        return responseTimeMillis;
    }
}
