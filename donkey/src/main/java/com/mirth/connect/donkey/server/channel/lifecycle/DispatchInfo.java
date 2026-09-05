/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel.lifecycle;

import java.util.Objects;

/** Immutable dispatch identity captured before a source message id exists. */
public final class DispatchInfo {
    private final String serverId;
    private final String channelId;
    private final String channelName;
    private final int sourceConnectorMetaDataId;
    private final String sourceConnectorName;
    private final String sourceConnectorType;
    private final InboundParentState inboundParentState;
    private final InboundTraceParent inboundTraceParent;
    private final MessageLineage messageLineage;

    public DispatchInfo(String serverId, String channelId, String channelName,
            int sourceConnectorMetaDataId, String sourceConnectorName, String sourceConnectorType,
            InboundParentState inboundParentState, InboundTraceParent inboundTraceParent,
            MessageLineage messageLineage) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.channelName = Objects.requireNonNull(channelName, "channelName");
        if (sourceConnectorMetaDataId != 0) {
            throw new IllegalArgumentException("sourceConnectorMetaDataId must be zero");
        }
        this.sourceConnectorMetaDataId = sourceConnectorMetaDataId;
        this.sourceConnectorName = Objects.requireNonNull(sourceConnectorName,
                "sourceConnectorName");
        this.sourceConnectorType = Objects.requireNonNull(sourceConnectorType,
                "sourceConnectorType");
        this.inboundParentState = Objects.requireNonNull(inboundParentState,
                "inboundParentState");
        if ((inboundParentState == InboundParentState.VALID) != (inboundTraceParent != null)) {
            throw new IllegalArgumentException(
                    "inboundTraceParent must be present if and only if inboundParentState is VALID");
        }
        this.inboundTraceParent = inboundTraceParent;
        this.messageLineage = messageLineage;
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

    public int getSourceConnectorMetaDataId() {
        return sourceConnectorMetaDataId;
    }

    public String getSourceConnectorName() {
        return sourceConnectorName;
    }

    public String getSourceConnectorType() {
        return sourceConnectorType;
    }

    public InboundParentState getInboundParentState() {
        return inboundParentState;
    }

    public InboundTraceParent getInboundTraceParent() {
        return inboundTraceParent;
    }

    public MessageLineage getMessageLineage() {
        return messageLineage;
    }
}
