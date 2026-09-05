/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license.
 */
package com.mirth.connect.donkey.server.channel;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Calendar;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.channel.lifecycle.ExecutionMode;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureCategory;
import com.mirth.connect.donkey.server.channel.lifecycle.FailureInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffBundle;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.HandoffKind;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleDispatchToken;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleOutcome;
import com.mirth.connect.donkey.server.channel.lifecycle.LifecycleResult;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageInfo;
import com.mirth.connect.donkey.server.channel.lifecycle.MessageLifecycleListeners;
import com.mirth.connect.donkey.server.channel.lifecycle.StatusChangeInfo;
import com.mirth.connect.donkey.server.channel.components.FilterTransformerException;
import com.mirth.connect.donkey.server.data.DonkeyDao;

/** Internal construction and propagation helpers for the content-free lifecycle SPI. */
final class MessageLifecycleSupport {
    private static final MessageLifecycleListeners EMPTY_LISTENERS =
            new MessageLifecycleListeners();

    private MessageLifecycleSupport() {}

    static MessageLifecycleListeners listeners() {
        MessageLifecycleListeners listeners = Donkey.getInstance().getMessageLifecycleListeners();
        return listeners != null ? listeners : EMPTY_LISTENERS;
    }

    static boolean isEnabled(LifecycleDispatchToken token) {
        return token != null && !token.isEmpty();
    }

    static boolean isEnabled(ConnectorMessage message) {
        return message != null && message.getMessageIncarnationId() > 0L
                && isEnabled(message.getLifecycleDispatchToken());
    }

    static void initialize(ConnectorMessage message, LifecycleDispatchToken token,
            long incarnationId, Connector connector, ExecutionMode executionMode) {
        initialize(message, token, incarnationId, connector.getLifecycleConnectorType(),
                executionMode);
    }

    static void initialize(ConnectorMessage message, LifecycleDispatchToken token,
            long incarnationId, String connectorType, ExecutionMode executionMode) {
        message.setLifecycleDispatchToken(token);
        message.setMessageIncarnationId(incarnationId);
        message.setLifecycleConnectorType(connectorType);
        message.setLifecycleExecutionMode(executionMode);
    }

    static void initializeRecovered(ConnectorMessage message, Connector connector,
            ExecutionMode executionMode) {
        LifecycleDispatchToken token = listeners().captureToken();
        long incarnationId = listeners().allocateMessageIncarnationId(token);
        initialize(message, token, incarnationId, connector, executionMode);
    }

    static void copy(ConnectorMessage source, ConnectorMessage target, Connector connector) {
        initialize(target, source.getLifecycleDispatchToken(), source.getMessageIncarnationId(),
                connector, source.getLifecycleExecutionMode());
    }

    static void initialize(Message message, LifecycleDispatchToken token, long incarnationId) {
        message.setLifecycleDispatchToken(token);
        message.setMessageIncarnationId(incarnationId);
    }

    static MessageInfo snapshot(ConnectorMessage message) {
        if (!isEnabled(message) || message.getStatus() == null) {
            return null;
        }
        Integer chainId = message.getMetaDataId() == 0 ? null : message.getChainId();
        return new MessageInfo(nonNull(message.getServerId()), nonNull(message.getChannelId()),
                nonNull(message.getChannelName()), message.getMessageId(),
                message.getMetaDataId(), nonNull(message.getConnectorName()),
                nonNull(message.getLifecycleConnectorType()), message.getMessageIncarnationId(),
                chainId, message.getStatus(), message.getSendAttempts(),
                time(message.getReceivedDate()), time(message.getSendDate()),
                time(message.getResponseDate()));
    }

    static FailureInfo failure(FailureCategory category, Throwable throwable) {
        return new FailureInfo(category != null ? category : FailureCategory.UNKNOWN,
                throwable != null ? throwable.getClass().getName() : null);
    }

    static FailureCategory failureCategory(Throwable throwable, FailureCategory fallback) {
        if (throwable instanceof FilterTransformerException) {
            FailureCategory category = ((FilterTransformerException) throwable)
                    .getFailureCategory();
            if (category != FailureCategory.UNKNOWN) {
                return category;
            }
        }
        return fallback;
    }

    static FailureInfo failure(ConnectorMessage message, FailureCategory fallback,
            Throwable throwable) {
        FailureInfo failure = message != null ? message.getLifecycleFailureInfo() : null;
        return failure != null ? failure : failure(fallback, throwable);
    }

    static LifecycleResult result(ConnectorMessage message, Throwable throwable,
            boolean rolledBack, Status responseStatus, FailureCategory fallback) {
        LifecycleOutcome outcome;
        FailureInfo failure = null;
        if (hasCause(throwable, CancellationException.class)) {
            outcome = LifecycleOutcome.CANCELLED;
            failure = failure(message, fallback, throwable);
        } else if (isInterrupted(throwable)) {
            outcome = LifecycleOutcome.INTERRUPTED;
            failure = failure(message, fallback, throwable);
        } else if (rolledBack) {
            outcome = LifecycleOutcome.ROLLED_BACK;
            failure = failure(message, fallback, throwable);
        } else if (throwable != null || responseStatus == Status.ERROR
                || message != null && message.getLifecycleFailureInfo() != null
                || message != null && message.getStatus() == Status.ERROR) {
            outcome = LifecycleOutcome.ERROR;
            failure = failure(message, fallback, throwable);
        } else if (message != null && message.getStatus() == Status.FILTERED) {
            outcome = LifecycleOutcome.FILTERED;
        } else {
            outcome = LifecycleOutcome.SUCCESS;
        }
        return new LifecycleResult(outcome, snapshot(message), responseStatus, failure);
    }

    static void updateStatus(DonkeyDao dao, ConnectorMessage message, Status previousStatus,
            Status currentStatus, FailureInfo failure) {
        message.setStatus(currentStatus);
        if (currentStatus == Status.ERROR) {
            if (failure == null) {
                failure = failure(FailureCategory.UNKNOWN, null);
            }
            message.setLifecycleFailureInfo(failure);
        } else {
            failure = null;
            message.setLifecycleFailureInfo(null);
        }
        dao.updateStatus(message, previousStatus);

        if (isEnabled(message)) {
            MessageInfo snapshot = snapshot(message);
            if (snapshot != null) {
                listeners().onStatusChanged(message.getLifecycleDispatchToken(),
                        new StatusChangeInfo(snapshot, previousStatus, currentStatus, failure));
            }
        }
    }

    static HandoffBundle createHandoff(ConnectorMessage message, HandoffKind kind) {
        if (!isEnabled(message)) {
            return null;
        }
        MessageInfo snapshot = snapshot(message);
        if (snapshot == null) {
            return null;
        }
        Integer chainId = kind == HandoffKind.ASYNC_CHAIN ? message.getChainId() : null;
        Long nextAttempt = kind == HandoffKind.DESTINATION_QUEUE
                ? nextAttempt(message) : null;
        return listeners().createHandoffs(message.getLifecycleDispatchToken(),
                new HandoffInfo(kind, snapshot, chainId, nextAttempt));
    }

    static void throwIfFatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError) {
            throw (VirtualMachineError) throwable;
        }
        if (throwable instanceof ThreadDeath) {
            throw (ThreadDeath) throwable;
        }
    }

    private static Long nextAttempt(ConnectorMessage message) {
        return Long.valueOf((long) message.getSendAttempts() + 1L);
    }

    private static Long time(Calendar calendar) {
        return calendar != null ? calendar.getTimeInMillis() : null;
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    private static boolean isInterrupted(Throwable throwable) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        return hasCause(throwable, InterruptedException.class)
                || hasCause(throwable, InterruptedIOException.class)
                || hasCause(throwable, ClosedByInterruptException.class);
    }

    private static boolean hasCause(Throwable throwable,
            Class<? extends Throwable> causeType) {
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<Throwable, Boolean>();
        Throwable current = throwable;
        while (current != null && seen.put(current, Boolean.TRUE) == null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
