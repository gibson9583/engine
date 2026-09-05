/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.donkey.model.message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mirth.connect.donkey.model.message.attachment.Attachment;
import com.mirth.connect.donkey.server.message.batch.BatchMessageSource;
import com.mirth.connect.donkey.server.channel.lifecycle.InboundParentState;
import com.mirth.connect.donkey.server.channel.lifecycle.InboundTraceParent;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleDispatchToken;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLineage;

public class BatchRawMessage {
    private BatchMessageSource batchMessageSource;
    protected Map<String, Object> sourceMap = new HashMap<String, Object>();
    private List<Attachment> attachments;
    private transient LifecycleDispatchToken lifecycleDispatchToken;
    private transient InboundParentState inboundParentState;
    private transient InboundTraceParent inboundTraceParent;
    private transient MessageLineage messageLineage;

    public BatchRawMessage(BatchMessageSource batchMessageSource) {
        this.batchMessageSource = batchMessageSource;
    }

    public BatchRawMessage(BatchMessageSource batchMessageSource, Map<String, Object> sourceMap) {
        this.batchMessageSource = batchMessageSource;
        this.sourceMap = sourceMap;
    }

    public BatchRawMessage(BatchMessageSource batchMessageSource, Map<String, Object> sourceMap, List<Attachment> attachments) {
        this.batchMessageSource = batchMessageSource;
        this.sourceMap = sourceMap;
        this.attachments = attachments;
    }

    public BatchMessageSource getBatchMessageSource() {
        return batchMessageSource;
    }

    public Map<String, Object> getSourceMap() {
        return sourceMap;
    }

    public void setSourceMap(Map<String, Object> sourceMap) {
        this.sourceMap = sourceMap;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    public LifecycleDispatchToken getLifecycleDispatchToken() {
        return lifecycleDispatchToken;
    }

    public void setLifecycleDispatchToken(LifecycleDispatchToken lifecycleDispatchToken) {
        this.lifecycleDispatchToken = lifecycleDispatchToken;
    }

    public InboundParentState getInboundParentState() {
        return inboundParentState;
    }

    public void setInboundParentState(InboundParentState inboundParentState) {
        this.inboundParentState = inboundParentState;
    }

    public InboundTraceParent getInboundTraceParent() {
        return inboundTraceParent;
    }

    public void setInboundTraceParent(InboundTraceParent inboundTraceParent) {
        this.inboundTraceParent = inboundTraceParent;
    }

    public MessageLineage getMessageLineage() {
        return messageLineage;
    }

    public void setMessageLineage(MessageLineage messageLineage) {
        this.messageLineage = messageLineage;
    }
}
