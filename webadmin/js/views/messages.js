/**
 * Message Browser View - Search and view messages for a channel.
 */
var MessagesView = (function () {
    'use strict';

    var currentChannelId = null;
    var currentOffset = 0;
    var currentLimit = 20;
    var channels = {};

    function render(container, params) {
        currentChannelId = params.channelId || null;
        currentOffset = parseInt(params.offset, 10) || 0;

        container.innerHTML =
            '<div class="container-fluid py-4">' +
                '<h4 class="mb-4"><i class="bi bi-envelope me-2"></i>Message Browser</h4>' +
                '<!-- Channel Selector & Search -->' +
                '<div class="card shadow-sm mb-4">' +
                    '<div class="card-body">' +
                        '<div class="row g-3">' +
                            '<div class="col-md-4">' +
                                '<label for="msg-channel" class="form-label">Channel</label>' +
                                '<select class="form-select" id="msg-channel">' +
                                    '<option value="">Loading channels...</option>' +
                                '</select>' +
                            '</div>' +
                            '<div class="col-md-3">' +
                                '<label for="msg-status" class="form-label">Status</label>' +
                                '<select class="form-select" id="msg-status">' +
                                    '<option value="">All Statuses</option>' +
                                    '<option value="RECEIVED">Received</option>' +
                                    '<option value="FILTERED">Filtered</option>' +
                                    '<option value="TRANSFORMED">Transformed</option>' +
                                    '<option value="SENT">Sent</option>' +
                                    '<option value="QUEUED">Queued</option>' +
                                    '<option value="ERROR">Error</option>' +
                                    '<option value="PENDING">Pending</option>' +
                                '</select>' +
                            '</div>' +
                            '<div class="col-md-3">' +
                                '<label for="msg-text-search" class="form-label">Text Search</label>' +
                                '<input type="text" class="form-control" id="msg-text-search" placeholder="Search content...">' +
                            '</div>' +
                            '<div class="col-md-2 d-flex align-items-end">' +
                                '<button class="btn btn-primary w-100" id="msg-search-btn">' +
                                    '<i class="bi bi-search me-1"></i> Search' +
                                '</button>' +
                            '</div>' +
                        '</div>' +
                        '<div class="row g-3 mt-1">' +
                            '<div class="col-md-3">' +
                                '<label for="msg-start-date" class="form-label">Start Date</label>' +
                                '<input type="datetime-local" class="form-control" id="msg-start-date">' +
                            '</div>' +
                            '<div class="col-md-3">' +
                                '<label for="msg-end-date" class="form-label">End Date</label>' +
                                '<input type="datetime-local" class="form-control" id="msg-end-date">' +
                            '</div>' +
                            '<div class="col-md-3">' +
                                '<label for="msg-id-min" class="form-label">Min Message ID</label>' +
                                '<input type="number" class="form-control" id="msg-id-min" placeholder="Min ID">' +
                            '</div>' +
                            '<div class="col-md-3">' +
                                '<label for="msg-id-max" class="form-label">Max Message ID</label>' +
                                '<input type="number" class="form-control" id="msg-id-max" placeholder="Max ID">' +
                            '</div>' +
                        '</div>' +
                        '<div class="row g-3 mt-1">' +
                            '<div class="col-md-2">' +
                                '<div class="form-check mt-4">' +
                                    '<input class="form-check-input" type="checkbox" id="msg-errors-only">' +
                                    '<label class="form-check-label" for="msg-errors-only">Errors only</label>' +
                                '</div>' +
                            '</div>' +
                            '<div class="col-md-2">' +
                                '<div class="form-check mt-4">' +
                                    '<input class="form-check-input" type="checkbox" id="msg-has-attachment">' +
                                    '<label class="form-check-label" for="msg-has-attachment">Has attachment</label>' +
                                '</div>' +
                            '</div>' +
                        '</div>' +
                    '</div>' +
                '</div>' +
                '<!-- Results -->' +
                '<div id="msg-results">' +
                    '<div class="text-center text-muted py-5">Select a channel and click Search to browse messages.</div>' +
                '</div>' +
            '</div>';

        document.getElementById('msg-search-btn').addEventListener('click', doSearch);
        document.getElementById('msg-channel').addEventListener('change', onChannelChange);

        // Enter key triggers search
        ['msg-text-search', 'msg-id-min', 'msg-id-max'].forEach(function (id) {
            document.getElementById(id).addEventListener('keypress', function (e) {
                if (e.key === 'Enter') doSearch();
            });
        });

        loadChannels();

        return { destroy: destroy };
    }

    function loadChannels() {
        MirthAPI.getChannelIdsAndNames()
            .then(function (idsAndNames) {
                channels = idsAndNames || {};
                var select = document.getElementById('msg-channel');
                if (!select) return;

                var options = '<option value="">-- Select Channel --</option>';
                var entries = Object.entries(channels).sort(function (a, b) {
                    return a[1].localeCompare(b[1]);
                });
                entries.forEach(function (entry) {
                    var selected = entry[0] === currentChannelId ? ' selected' : '';
                    options += '<option value="' + escapeAttr(entry[0]) + '"' + selected + '>' +
                        escapeHtml(entry[1]) + '</option>';
                });
                select.innerHTML = options;

                if (currentChannelId) {
                    doSearch();
                }
            })
            .catch(function (err) {
                var select = document.getElementById('msg-channel');
                if (select) {
                    select.innerHTML = '<option value="">Failed to load channels</option>';
                }
            });
    }

    function onChannelChange() {
        currentChannelId = document.getElementById('msg-channel').value;
        currentOffset = 0;
    }

    function buildSearchParams() {
        var params = {
            includeContent: true,
            offset: currentOffset,
            limit: currentLimit
        };

        var status = document.getElementById('msg-status').value;
        if (status) params.status = status;

        var textSearch = document.getElementById('msg-text-search').value.trim();
        if (textSearch) params.textSearch = textSearch;

        var startDate = document.getElementById('msg-start-date').value;
        if (startDate) params.startDate = new Date(startDate).toISOString();

        var endDate = document.getElementById('msg-end-date').value;
        if (endDate) params.endDate = new Date(endDate).toISOString();

        var minId = document.getElementById('msg-id-min').value;
        if (minId) params.minMessageId = minId;

        var maxId = document.getElementById('msg-id-max').value;
        if (maxId) params.maxMessageId = maxId;

        var errorsOnly = document.getElementById('msg-errors-only').checked;
        if (errorsOnly) params.error = true;

        var hasAttachment = document.getElementById('msg-has-attachment').checked;
        if (hasAttachment) params.attachment = true;

        return params;
    }

    function doSearch() {
        var channelId = document.getElementById('msg-channel').value;
        if (!channelId) {
            App.showToast('Please select a channel first.', 'warning');
            return;
        }
        currentChannelId = channelId;

        var resultsEl = document.getElementById('msg-results');
        resultsEl.innerHTML =
            '<div class="text-center py-5">' +
                '<div class="spinner-border text-primary"></div>' +
                '<p class="text-muted mt-2">Searching messages...</p>' +
            '</div>';

        var params = buildSearchParams();

        // Get count and messages in parallel
        Promise.all([
            MirthAPI.getMessages(channelId, params),
            MirthAPI.getMessageCount(channelId, params)
        ])
        .then(function (results) {
            var messages = results[0];
            var count = results[1];
            renderResults(messages, count);
        })
        .catch(function (err) {
            resultsEl.innerHTML =
                '<div class="alert alert-danger">' +
                    '<i class="bi bi-exclamation-triangle me-2"></i>Failed to search messages: ' +
                    escapeHtml(err.message) +
                '</div>';
        });
    }

    function renderResults(messages, totalCount) {
        var resultsEl = document.getElementById('msg-results');
        if (!resultsEl) return;

        if (!messages || messages.length === 0) {
            resultsEl.innerHTML =
                '<div class="text-center text-muted py-5">' +
                    '<i class="bi bi-inbox display-4"></i>' +
                    '<p class="mt-2">No messages found matching your criteria.</p>' +
                '</div>';
            return;
        }

        var channelName = channels[currentChannelId] || currentChannelId;
        var totalPages = Math.ceil(totalCount / currentLimit);
        var currentPage = Math.floor(currentOffset / currentLimit) + 1;

        var html =
            '<div class="d-flex justify-content-between align-items-center mb-3">' +
                '<span class="text-muted">' +
                    'Showing ' + (currentOffset + 1) + '-' +
                    Math.min(currentOffset + messages.length, totalCount) +
                    ' of ' + formatNumber(totalCount) + ' messages in <strong>' +
                    escapeHtml(channelName) + '</strong>' +
                '</span>' +
            '</div>' +
            '<div class="card shadow-sm">' +
                '<div class="card-body p-0">' +
                    '<div class="table-responsive">' +
                        '<table class="table table-hover table-sm mb-0">' +
                            '<thead class="table-light">' +
                                '<tr>' +
                                    '<th>ID</th>' +
                                    '<th>Received Date</th>' +
                                    '<th>Connector</th>' +
                                    '<th>Status</th>' +
                                    '<th>Actions</th>' +
                                '</tr>' +
                            '</thead>' +
                            '<tbody>';

        messages.forEach(function (msg) {
            var connectorMessages = msg.connectorMessages || {};
            var connectorKeys = Object.keys(connectorMessages).sort(function (a, b) {
                return parseInt(a, 10) - parseInt(b, 10);
            });

            if (connectorKeys.length === 0) {
                html += renderMessageRow(msg, null);
            } else {
                connectorKeys.forEach(function (metaDataId, index) {
                    html += renderMessageRow(msg, connectorMessages[metaDataId], index === 0, connectorKeys.length);
                });
            }
        });

        html += '</tbody></table></div></div></div>';

        // Pagination
        if (totalPages > 1) {
            html += renderPagination(currentPage, totalPages);
        }

        resultsEl.innerHTML = html;

        // Attach event listeners
        resultsEl.querySelectorAll('[data-action="view-message"]').forEach(function (btn) {
            btn.addEventListener('click', onViewMessage);
        });
        resultsEl.querySelectorAll('[data-action="reprocess"]').forEach(function (btn) {
            btn.addEventListener('click', onReprocessMessage);
        });
        resultsEl.querySelectorAll('[data-page]').forEach(function (btn) {
            btn.addEventListener('click', onPageClick);
        });
    }

    function renderMessageRow(msg, connMsg, isFirst, totalConnectors) {
        var messageId = msg.messageId;
        var receivedDate = msg.receivedDate ? formatDate(msg.receivedDate) : 'N/A';

        if (!connMsg) {
            return '<tr>' +
                '<td><code>' + messageId + '</code></td>' +
                '<td>' + receivedDate + '</td>' +
                '<td>-</td>' +
                '<td>-</td>' +
                '<td>' + messageActions(messageId) + '</td>' +
            '</tr>';
        }

        var connectorName = connMsg.connectorName || ('Connector ' + connMsg.metaDataId);
        var status = connMsg.status || 'UNKNOWN';

        var rowspan = isFirst ? ' rowspan="' + totalConnectors + '"' : '';
        var html = '<tr>';

        if (isFirst) {
            html += '<td' + rowspan + '><code>' + messageId + '</code></td>';
            html += '<td' + rowspan + '>' + receivedDate + '</td>';
        }

        html += '<td>' +
            '<span class="small">' +
                (connMsg.metaDataId === 0 ? '<i class="bi bi-box-arrow-in-right me-1"></i>' : '<i class="bi bi-box-arrow-right me-1"></i>') +
                escapeHtml(connectorName) +
            '</span>' +
        '</td>';
        html += '<td>' + statusBadge(status) + '</td>';

        if (isFirst) {
            html += '<td' + rowspan + '>' + messageActions(messageId) + '</td>';
        }

        html += '</tr>';
        return html;
    }

    function messageActions(messageId) {
        return '<div class="btn-group btn-group-sm">' +
            '<button class="btn btn-outline-primary" data-action="view-message" ' +
                'data-message-id="' + messageId + '" title="View Details">' +
                '<i class="bi bi-eye"></i>' +
            '</button>' +
            '<button class="btn btn-outline-secondary" data-action="reprocess" ' +
                'data-message-id="' + messageId + '" title="Reprocess">' +
                '<i class="bi bi-arrow-repeat"></i>' +
            '</button>' +
        '</div>';
    }

    function statusBadge(status) {
        var cls = 'secondary';
        switch (status) {
            case 'RECEIVED': cls = 'info'; break;
            case 'FILTERED': cls = 'warning'; break;
            case 'TRANSFORMED': cls = 'primary'; break;
            case 'SENT': cls = 'success'; break;
            case 'QUEUED': cls = 'info'; break;
            case 'ERROR': cls = 'danger'; break;
            case 'PENDING': cls = 'secondary'; break;
        }
        return '<span class="badge bg-' + cls + '">' + escapeHtml(status) + '</span>';
    }

    function renderPagination(currentPage, totalPages) {
        var html = '<nav class="mt-3"><ul class="pagination justify-content-center">';

        html += '<li class="page-item' + (currentPage <= 1 ? ' disabled' : '') + '">' +
            '<a class="page-link" href="#" data-page="' + (currentPage - 1) + '">Previous</a></li>';

        var startPage = Math.max(1, currentPage - 2);
        var endPage = Math.min(totalPages, currentPage + 2);

        if (startPage > 1) {
            html += '<li class="page-item"><a class="page-link" href="#" data-page="1">1</a></li>';
            if (startPage > 2) html += '<li class="page-item disabled"><span class="page-link">...</span></li>';
        }

        for (var p = startPage; p <= endPage; p++) {
            html += '<li class="page-item' + (p === currentPage ? ' active' : '') + '">' +
                '<a class="page-link" href="#" data-page="' + p + '">' + p + '</a></li>';
        }

        if (endPage < totalPages) {
            if (endPage < totalPages - 1) html += '<li class="page-item disabled"><span class="page-link">...</span></li>';
            html += '<li class="page-item"><a class="page-link" href="#" data-page="' + totalPages + '">' + totalPages + '</a></li>';
        }

        html += '<li class="page-item' + (currentPage >= totalPages ? ' disabled' : '') + '">' +
            '<a class="page-link" href="#" data-page="' + (currentPage + 1) + '">Next</a></li>';

        html += '</ul></nav>';
        return html;
    }

    function onPageClick(e) {
        e.preventDefault();
        var page = parseInt(e.currentTarget.getAttribute('data-page'), 10);
        if (isNaN(page) || page < 1) return;
        currentOffset = (page - 1) * currentLimit;
        doSearch();
    }

    function onViewMessage(e) {
        var messageId = e.currentTarget.getAttribute('data-message-id');
        showMessageDetail(messageId);
    }

    function showMessageDetail(messageId) {
        MirthAPI.getMessage(currentChannelId, messageId)
            .then(function (msg) {
                renderMessageModal(msg);
            })
            .catch(function (err) {
                App.showToast('Failed to load message: ' + err.message, 'danger');
            });
    }

    function renderMessageModal(msg) {
        // Remove existing modal
        var existing = document.getElementById('msg-detail-modal');
        if (existing) existing.remove();

        var connectorMessages = msg.connectorMessages || {};
        var connectorKeys = Object.keys(connectorMessages).sort(function (a, b) {
            return parseInt(a, 10) - parseInt(b, 10);
        });

        var tabsHtml = '';
        var contentHtml = '';

        connectorKeys.forEach(function (metaDataId, index) {
            var connMsg = connectorMessages[metaDataId];
            var connName = connMsg.connectorName || ('Connector ' + metaDataId);
            var status = connMsg.status || 'UNKNOWN';
            var active = index === 0 ? ' active' : '';
            var show = index === 0 ? ' show active' : '';

            tabsHtml += '<li class="nav-item">' +
                '<button class="nav-link' + active + '" data-bs-toggle="tab" data-bs-target="#conn-' + metaDataId + '">' +
                    escapeHtml(connName) + ' ' + statusBadge(status) +
                '</button>' +
            '</li>';

            var contentTypes = ['rawData', 'processedRawData', 'transformedData', 'encodedData', 'sentData', 'responseData'];
            var contentPanels = '';

            contentTypes.forEach(function (type) {
                var data = getContentData(connMsg, type);
                if (data) {
                    var label = type.replace(/([A-Z])/g, ' $1').replace(/^./, function (s) { return s.toUpperCase(); });
                    contentPanels += '<div class="mb-3">' +
                        '<label class="form-label fw-bold">' + label + '</label>' +
                        '<pre class="bg-light p-3 border rounded" style="max-height: 300px; overflow: auto;">' +
                            '<code>' + escapeHtml(data) + '</code>' +
                        '</pre>' +
                    '</div>';
                }
            });

            // Errors
            if (connMsg.processingError || connMsg.postProcessorError || connMsg.responseError) {
                var errors = [connMsg.processingError, connMsg.postProcessorError, connMsg.responseError]
                    .filter(Boolean).join('\n---\n');
                contentPanels += '<div class="mb-3">' +
                    '<label class="form-label fw-bold text-danger">Errors</label>' +
                    '<pre class="bg-danger bg-opacity-10 p-3 border border-danger rounded" style="max-height: 200px; overflow: auto;">' +
                        '<code>' + escapeHtml(errors) + '</code>' +
                    '</pre>' +
                '</div>';
            }

            if (!contentPanels) {
                contentPanels = '<p class="text-muted">No content available for this connector.</p>';
            }

            contentHtml += '<div class="tab-pane fade' + show + '" id="conn-' + metaDataId + '">' +
                '<div class="mb-3">' +
                    '<div class="row">' +
                        '<div class="col-md-4"><strong>Connector:</strong> ' + escapeHtml(connName) + '</div>' +
                        '<div class="col-md-4"><strong>Status:</strong> ' + statusBadge(status) + '</div>' +
                        '<div class="col-md-4"><strong>Send Attempts:</strong> ' + (connMsg.sendAttempts || 0) + '</div>' +
                    '</div>' +
                '</div>' +
                contentPanels +
            '</div>';
        });

        var modalHtml =
            '<div class="modal fade" id="msg-detail-modal" tabindex="-1">' +
                '<div class="modal-dialog modal-xl modal-dialog-scrollable">' +
                    '<div class="modal-content">' +
                        '<div class="modal-header">' +
                            '<h5 class="modal-title">Message #' + msg.messageId + '</h5>' +
                            '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>' +
                        '</div>' +
                        '<div class="modal-body">' +
                            '<div class="mb-3">' +
                                '<div class="row">' +
                                    '<div class="col-md-4"><strong>Message ID:</strong> ' + msg.messageId + '</div>' +
                                    '<div class="col-md-4"><strong>Received:</strong> ' + formatDate(msg.receivedDate) + '</div>' +
                                    '<div class="col-md-4"><strong>Processed:</strong> ' +
                                        (msg.processed ? '<span class="badge bg-success">Yes</span>' : '<span class="badge bg-warning">No</span>') +
                                    '</div>' +
                                '</div>' +
                            '</div>' +
                            '<ul class="nav nav-tabs mb-3">' + tabsHtml + '</ul>' +
                            '<div class="tab-content">' + contentHtml + '</div>' +
                        '</div>' +
                        '<div class="modal-footer">' +
                            '<button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Close</button>' +
                            '<button type="button" class="btn btn-primary" id="modal-reprocess-btn" data-message-id="' + msg.messageId + '">' +
                                '<i class="bi bi-arrow-repeat me-1"></i>Reprocess' +
                            '</button>' +
                        '</div>' +
                    '</div>' +
                '</div>' +
            '</div>';

        document.body.insertAdjacentHTML('beforeend', modalHtml);
        var modalEl = document.getElementById('msg-detail-modal');
        var modal = new bootstrap.Modal(modalEl);

        document.getElementById('modal-reprocess-btn').addEventListener('click', function () {
            onReprocessMessage({ currentTarget: this });
            modal.hide();
        });

        modalEl.addEventListener('hidden.bs.modal', function () {
            modalEl.remove();
        });

        modal.show();
    }

    function getContentData(connMsg, type) {
        // The API nests content data in various ways
        if (connMsg[type]) {
            if (typeof connMsg[type] === 'string') return connMsg[type];
            if (connMsg[type].content) return connMsg[type].content;
        }
        // Try messageContent map pattern
        if (connMsg.messageContent && connMsg.messageContent[type]) {
            var mc = connMsg.messageContent[type];
            return typeof mc === 'string' ? mc : mc.content;
        }
        // Some connMsg have raw/encoded/etc directly
        var altNames = {
            'rawData': 'raw',
            'processedRawData': 'processedRaw',
            'transformedData': 'transformed',
            'encodedData': 'encoded',
            'sentData': 'sent',
            'responseData': 'response'
        };
        var alt = altNames[type];
        if (alt && connMsg[alt]) {
            if (typeof connMsg[alt] === 'string') return connMsg[alt];
            if (connMsg[alt].content) return connMsg[alt].content;
        }
        return null;
    }

    function onReprocessMessage(e) {
        var messageId = e.currentTarget.getAttribute('data-message-id');
        if (!confirm('Reprocess message #' + messageId + '?')) return;

        MirthAPI.reprocessMessage(currentChannelId, messageId, false)
            .then(function () {
                App.showToast('Message #' + messageId + ' reprocessing started.', 'success');
            })
            .catch(function (err) {
                App.showToast('Failed to reprocess: ' + err.message, 'danger');
            });
    }

    function formatDate(dateVal) {
        if (!dateVal) return 'N/A';
        try {
            // Handle various date formats from the API
            var d;
            if (typeof dateVal === 'string') {
                d = new Date(dateVal);
            } else if (dateVal.time) {
                d = new Date(dateVal.time);
            } else if (dateVal.timeInMillis) {
                d = new Date(dateVal.timeInMillis);
            } else {
                d = new Date(dateVal);
            }
            if (isNaN(d.getTime())) return String(dateVal);
            return d.toLocaleString();
        } catch (e) {
            return String(dateVal);
        }
    }

    function formatNumber(n) {
        if (n === undefined || n === null) return '0';
        return Number(n).toLocaleString();
    }

    function escapeHtml(str) {
        if (!str) return '';
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(String(str)));
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return escapeHtml(str).replace(/"/g, '&quot;');
    }

    function destroy() {}

    return { render: render };
})();
