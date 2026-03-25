/**
 * Message Browser View - Search, browse, and inspect messages.
 * Mimics the classic Mirth admin message browser with split pane layout.
 */
var MessagesView = (function () {
    'use strict';

    var currentChannelId = null;
    var channels = {};
    var currentOffset = 0;
    var currentLimit = 20;
    var totalCount = 0;
    var currentMessages = [];
    var selectedMessage = null;

    function render(container, params) {
        currentChannelId = params.channelId || null;
        currentOffset = parseInt(params.offset, 10) || 0;

        container.innerHTML =
            '<div class="msg-split">' +
                '<div class="msg-split-top">' +
                    '<!-- Search Panel -->' +
                    '<div class="msg-search-panel">' +
                        '<div class="msg-search-row">' +
                            '<label>Channel:</label>' +
                            '<select id="msg-channel" class="search-select"><option value="">Loading...</option></select>' +
                            '<label style="min-width:40px;">Text:</label>' +
                            '<input type="text" id="msg-text-search" placeholder="Search message content..." style="width:200px;">' +
                            '<label style="min-width:40px;">From:</label>' +
                            '<input type="datetime-local" id="msg-start-date" style="width:180px;">' +
                            '<label style="min-width:25px;">To:</label>' +
                            '<input type="datetime-local" id="msg-end-date" style="width:180px;">' +
                        '</div>' +
                        '<div class="msg-search-row">' +
                            '<label>Status:</label>' +
                            '<div class="msg-status-checks">' +
                                statusCheck('RECEIVED', 'Received', true) +
                                statusCheck('FILTERED', 'Filtered', true) +
                                statusCheck('TRANSFORMED', 'Transformed', true) +
                                statusCheck('SENT', 'Sent', true) +
                                statusCheck('QUEUED', 'Queued', true) +
                                statusCheck('ERROR', 'Error', true) +
                                statusCheck('PENDING', 'Pending', true) +
                            '</div>' +
                        '</div>' +
                        '<div class="msg-search-row">' +
                            '<label>Msg ID:</label>' +
                            '<input type="number" id="msg-id-min" placeholder="Min" style="width:90px;">' +
                            '<span style="color:#999;">&ndash;</span>' +
                            '<input type="number" id="msg-id-max" placeholder="Max" style="width:90px;">' +
                            '<div style="margin-left:auto; display:flex; gap:6px;">' +
                                '<button class="msg-btn msg-btn-primary" id="msg-search-btn">' +
                                    '<i class="bi bi-search"></i> Search' +
                                '</button>' +
                                '<button class="msg-btn" id="msg-count-btn">Count</button>' +
                                '<button class="msg-btn" id="msg-reset-btn">Reset</button>' +
                            '</div>' +
                        '</div>' +
                    '</div>' +
                    '<!-- Results bar -->' +
                    '<div class="msg-results-bar" id="msg-results-bar">' +
                        '<span id="msg-results-label">Select a channel and click Search.</span>' +
                        '<div class="msg-pagination" id="msg-pagination" style="display:none;">' +
                            '<button id="msg-prev-btn" disabled>&laquo; Previous</button>' +
                            '<span>Page <input type="text" id="msg-page-num" value="1" readonly> of <span id="msg-total-pages">1</span></span>' +
                            '<button id="msg-next-btn" disabled>Next &raquo;</button>' +
                        '</div>' +
                    '</div>' +
                    '<!-- Results table -->' +
                    '<div class="data-table-wrap">' +
                        '<table class="data-table" id="msg-table">' +
                            '<thead>' +
                                '<tr>' +
                                    '<th style="width:80px;">Id</th>' +
                                    '<th>Connector</th>' +
                                    '<th style="width:80px;">Status</th>' +
                                    '<th style="width:160px;">Received Date</th>' +
                                    '<th style="width:160px;">Response Date</th>' +
                                    '<th style="width:50px;">Errors</th>' +
                                '</tr>' +
                            '</thead>' +
                            '<tbody id="msg-body">' +
                                '<tr class="empty-row"><td colspan="6">No results.</td></tr>' +
                            '</tbody>' +
                        '</table>' +
                    '</div>' +
                '</div>' +
                '<!-- Detail pane -->' +
                '<div class="msg-split-bottom" id="msg-detail-pane" style="display:none;">' +
                    '<div class="msg-detail-tabs" id="msg-detail-tabs"></div>' +
                    '<div class="msg-content-radios" id="msg-content-radios"></div>' +
                    '<div class="msg-content-area" id="msg-content-area">' +
                        '<pre>Select a message to view content.</pre>' +
                    '</div>' +
                '</div>' +
            '</div>';

        // Bind events
        document.getElementById('msg-search-btn').addEventListener('click', doSearch);
        document.getElementById('msg-count-btn').addEventListener('click', doCount);
        document.getElementById('msg-reset-btn').addEventListener('click', resetFilters);
        document.getElementById('msg-prev-btn').addEventListener('click', prevPage);
        document.getElementById('msg-next-btn').addEventListener('click', nextPage);
        document.getElementById('msg-channel').addEventListener('change', function () {
            currentChannelId = this.value;
            currentOffset = 0;
        });

        // Enter triggers search
        ['msg-text-search', 'msg-id-min', 'msg-id-max'].forEach(function (id) {
            document.getElementById(id).addEventListener('keypress', function (e) {
                if (e.key === 'Enter') doSearch();
            });
        });

        // Task pane
        App.setTaskActions('Message Tasks', [
            { icon: 'bi-arrow-clockwise', label: 'Refresh', shortcut: 'R', action: doSearch },
            { icon: 'bi-search', label: 'Search Messages', shortcut: '', action: doSearch },
            { icon: 'bi-arrow-repeat', label: 'Reprocess Message', shortcut: '', action: reprocessSelected, id: 'task-reprocess' }
        ]);

        loadChannels();

        return { destroy: destroy };
    }

    function statusCheck(value, label, checked) {
        return '<label><input type="checkbox" value="' + value + '" class="msg-status-cb"' + (checked ? ' checked' : '') + '> ' + label + '</label>';
    }

    function loadChannels() {
        MirthAPI.getChannelIdsAndNames()
            .then(function (data) {
                channels = data || {};
                var sel = document.getElementById('msg-channel');
                if (!sel) return;

                var opts = '<option value="">-- Select Channel --</option>';
                Object.entries(channels).sort(function (a, b) { return a[1].localeCompare(b[1]); })
                    .forEach(function (e) {
                        opts += '<option value="' + escAttr(e[0]) + '"' + (e[0] === currentChannelId ? ' selected' : '') + '>' + esc(e[1]) + '</option>';
                    });
                sel.innerHTML = opts;
                if (currentChannelId) doSearch();
            })
            .catch(function () {
                var sel = document.getElementById('msg-channel');
                if (sel) sel.innerHTML = '<option value="">Failed to load channels</option>';
            });
    }

    function buildParams() {
        var params = { includeContent: true, offset: currentOffset, limit: currentLimit };

        // Collect checked statuses
        var statuses = [];
        document.querySelectorAll('.msg-status-cb:checked').forEach(function (cb) { statuses.push(cb.value); });
        if (statuses.length > 0 && statuses.length < 7) params.status = statuses;

        var text = document.getElementById('msg-text-search').value.trim();
        if (text) params.textSearch = text;

        var start = document.getElementById('msg-start-date').value;
        if (start) params.startDate = new Date(start).toISOString();

        var end = document.getElementById('msg-end-date').value;
        if (end) params.endDate = new Date(end).toISOString();

        var minId = document.getElementById('msg-id-min').value;
        if (minId) params.minMessageId = minId;

        var maxId = document.getElementById('msg-id-max').value;
        if (maxId) params.maxMessageId = maxId;

        return params;
    }

    function doSearch() {
        currentChannelId = document.getElementById('msg-channel').value;
        if (!currentChannelId) { App.showToast('Please select a channel.', 'warning'); return; }

        App.showWorking(true);
        var params = buildParams();

        Promise.all([
            MirthAPI.getMessages(currentChannelId, params),
            MirthAPI.getMessageCount(currentChannelId, params)
        ])
        .then(function (r) {
            currentMessages = r[0] || [];
            totalCount = typeof r[1] === 'number' ? r[1] : parseInt(r[1], 10) || 0;
            renderResults();
        })
        .catch(function (err) {
            document.getElementById('msg-body').innerHTML = '<tr class="empty-row"><td colspan="6">Error: ' + esc(err.message) + '</td></tr>';
            document.getElementById('msg-results-label').textContent = 'Search failed.';
        })
        .finally(function () { App.showWorking(false); });
    }

    function doCount() {
        currentChannelId = document.getElementById('msg-channel').value;
        if (!currentChannelId) { App.showToast('Please select a channel.', 'warning'); return; }

        App.showWorking(true);
        MirthAPI.getMessageCount(currentChannelId, buildParams())
            .then(function (count) {
                var n = typeof count === 'number' ? count : parseInt(count, 10) || 0;
                document.getElementById('msg-results-label').textContent = n.toLocaleString() + ' messages match the current filter.';
            })
            .catch(function (err) {
                document.getElementById('msg-results-label').textContent = 'Count failed: ' + err.message;
            })
            .finally(function () { App.showWorking(false); });
    }

    function renderResults() {
        var tbody = document.getElementById('msg-body');
        if (!tbody) return;

        var totalPages = Math.max(1, Math.ceil(totalCount / currentLimit));
        var currentPage = Math.floor(currentOffset / currentLimit) + 1;

        // Results label
        var label = document.getElementById('msg-results-label');
        if (currentMessages.length === 0) {
            label.textContent = 'No messages found.';
        } else {
            label.textContent = 'Showing ' + (currentOffset + 1) + '-' + (currentOffset + currentMessages.length) + ' of ' + totalCount.toLocaleString();
        }

        // Pagination
        var pag = document.getElementById('msg-pagination');
        pag.style.display = totalPages > 1 ? '' : 'none';
        document.getElementById('msg-page-num').value = currentPage;
        document.getElementById('msg-total-pages').textContent = totalPages;
        document.getElementById('msg-prev-btn').disabled = currentPage <= 1;
        document.getElementById('msg-next-btn').disabled = currentPage >= totalPages;

        if (currentMessages.length === 0) {
            tbody.innerHTML = '<tr class="empty-row"><td colspan="6">No messages found matching the filter.</td></tr>';
            return;
        }

        var html = '';
        currentMessages.forEach(function (msg) {
            var conns = msg.connectorMessages || {};
            var keys = Object.keys(conns).sort(function (a, b) { return a - b; });

            keys.forEach(function (metaId, idx) {
                var cm = conns[metaId];
                var isFirst = idx === 0;
                var connName = cm.connectorName || ('Connector ' + metaId);
                var status = cm.status || 'UNKNOWN';
                var hasError = cm.processingError || cm.postProcessorError || cm.responseError;
                var respDate = cm.responseDate ? fmtDate(cm.responseDate) : '';

                html += '<tr class="msg-row" data-msg-id="' + msg.messageId + '" data-meta-id="' + metaId + '">' +
                    '<td>' + (isFirst ? msg.messageId : '') + '</td>' +
                    '<td>' +
                        (parseInt(metaId, 10) === 0 ? '<i class="bi bi-box-arrow-in-right" style="font-size:11px;color:#888;"></i> ' : '<i class="bi bi-box-arrow-right" style="font-size:11px;color:#888;"></i> ') +
                        esc(connName) +
                    '</td>' +
                    '<td>' + statusBadge(status) + '</td>' +
                    '<td>' + (isFirst ? fmtDate(msg.receivedDate) : '') + '</td>' +
                    '<td>' + respDate + '</td>' +
                    '<td>' + (hasError ? '<i class="bi bi-exclamation-circle stat-error"></i>' : '') + '</td>' +
                '</tr>';
            });

            if (keys.length === 0) {
                html += '<tr class="msg-row" data-msg-id="' + msg.messageId + '">' +
                    '<td>' + msg.messageId + '</td><td>&mdash;</td><td>&mdash;</td>' +
                    '<td>' + fmtDate(msg.receivedDate) + '</td><td></td><td></td></tr>';
            }
        });

        tbody.innerHTML = html;

        // Row click
        tbody.querySelectorAll('.msg-row').forEach(function (row) {
            row.addEventListener('click', function () { onMsgRowClick(this); });
        });
    }

    function onMsgRowClick(row) {
        // Select row
        var prev = document.querySelector('.msg-row.selected');
        if (prev) prev.classList.remove('selected');
        row.classList.add('selected');

        var msgId = row.getAttribute('data-msg-id');
        var metaId = row.getAttribute('data-meta-id');

        // Find message in current results
        var msg = currentMessages.find(function (m) { return String(m.messageId) === msgId; });
        if (!msg) return;
        selectedMessage = msg;

        showMessageDetail(msg, metaId);
    }

    function showMessageDetail(msg, activeMetaId) {
        var pane = document.getElementById('msg-detail-pane');
        pane.style.display = '';

        var conns = msg.connectorMessages || {};
        var keys = Object.keys(conns).sort(function (a, b) { return a - b; });

        // Build connector tabs
        var tabsHtml = '';
        keys.forEach(function (metaId) {
            var cm = conns[metaId];
            var name = cm.connectorName || ('Connector ' + metaId);
            var active = metaId === activeMetaId ? ' active' : '';
            tabsHtml += '<button class="msg-detail-tab' + active + '" data-meta-id="' + metaId + '">' + esc(name) + '</button>';
        });

        // Also add Errors tab
        tabsHtml += '<button class="msg-detail-tab" data-meta-id="__errors">Errors</button>';

        document.getElementById('msg-detail-tabs').innerHTML = tabsHtml;

        // Tab click handlers
        document.querySelectorAll('.msg-detail-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                document.querySelectorAll('.msg-detail-tab').forEach(function (t) { t.classList.remove('active'); });
                this.classList.add('active');
                var mid = this.getAttribute('data-meta-id');
                if (mid === '__errors') {
                    showErrorsContent(msg);
                } else {
                    showConnectorContent(conns[mid]);
                }
            });
        });

        // Show initial content
        if (activeMetaId && conns[activeMetaId]) {
            showConnectorContent(conns[activeMetaId]);
        } else if (keys.length > 0) {
            showConnectorContent(conns[keys[0]]);
        }
    }

    function showConnectorContent(cm) {
        var contentTypes = [
            { key: 'rawData', alt: 'raw', label: 'Raw' },
            { key: 'processedRawData', alt: 'processedRaw', label: 'Processed Raw' },
            { key: 'transformedData', alt: 'transformed', label: 'Transformed' },
            { key: 'encodedData', alt: 'encoded', label: 'Encoded' },
            { key: 'sentData', alt: 'sent', label: 'Sent' },
            { key: 'responseData', alt: 'response', label: 'Response' }
        ];

        // Find available content
        var available = contentTypes.filter(function (ct) { return getContent(cm, ct.key, ct.alt); });

        // Build radio buttons
        var radiosHtml = '';
        available.forEach(function (ct, i) {
            radiosHtml += '<button class="msg-content-radio' + (i === 0 ? ' active' : '') + '" data-content-key="' + ct.key + '" data-content-alt="' + ct.alt + '">' + ct.label + '</button>';
        });

        document.getElementById('msg-content-radios').innerHTML = radiosHtml;

        // Show first content
        if (available.length > 0) {
            renderContent(getContent(cm, available[0].key, available[0].alt));
        } else {
            renderContent(null);
        }

        // Radio click handlers
        document.querySelectorAll('.msg-content-radio').forEach(function (btn) {
            btn.addEventListener('click', function () {
                document.querySelectorAll('.msg-content-radio').forEach(function (b) { b.classList.remove('active'); });
                this.classList.add('active');
                var data = getContent(cm, this.getAttribute('data-content-key'), this.getAttribute('data-content-alt'));
                renderContent(data);
            });
        });
    }

    function showErrorsContent(msg) {
        document.getElementById('msg-content-radios').innerHTML = '';
        var conns = msg.connectorMessages || {};
        var errors = [];

        Object.keys(conns).forEach(function (metaId) {
            var cm = conns[metaId];
            var name = cm.connectorName || ('Connector ' + metaId);
            if (cm.processingError) errors.push('[' + name + '] Processing Error:\n' + cm.processingError);
            if (cm.postProcessorError) errors.push('[' + name + '] Postprocessor Error:\n' + cm.postProcessorError);
            if (cm.responseError) errors.push('[' + name + '] Response Error:\n' + cm.responseError);
        });

        if (errors.length === 0) {
            renderContent('No errors for this message.');
        } else {
            var area = document.getElementById('msg-content-area');
            area.innerHTML = '<pre class="msg-error-text">' + esc(errors.join('\n\n---\n\n')) + '</pre>';
        }
    }

    function getContent(cm, key, alt) {
        var val = cm[key] || cm[alt];
        if (!val) {
            if (cm.messageContent) val = cm.messageContent[key] || cm.messageContent[alt];
        }
        if (!val) return null;
        if (typeof val === 'string') return val;
        if (val.content) return val.content;
        return null;
    }

    function renderContent(data) {
        var area = document.getElementById('msg-content-area');
        if (!data) {
            area.innerHTML = '<pre style="color:#999;font-style:italic;">No content available.</pre>';
        } else {
            area.innerHTML = '<pre>' + esc(data) + '</pre>';
        }
    }

    function statusBadge(status) {
        var cls = 'bullet-gray';
        switch (status) {
            case 'RECEIVED': cls = 'bullet-blue'; break;
            case 'FILTERED': cls = 'bullet-yellow'; break;
            case 'TRANSFORMED': cls = 'bullet-blue'; break;
            case 'SENT': cls = 'bullet-green'; break;
            case 'QUEUED': cls = 'bullet-orange'; break;
            case 'ERROR': cls = 'bullet-red'; break;
        }
        return '<span class="status-bullet ' + cls + '"></span> <span class="state-text">' + esc(status) + '</span>';
    }

    function reprocessSelected() {
        if (!selectedMessage || !currentChannelId) return;
        MirthAPI.reprocessMessage(currentChannelId, selectedMessage.messageId, false)
            .then(function () { App.showToast('Reprocess started for message #' + selectedMessage.messageId, 'success'); })
            .catch(function (err) { App.showToast('Reprocess failed: ' + err.message, 'danger'); });
    }

    function resetFilters() {
        document.getElementById('msg-text-search').value = '';
        document.getElementById('msg-start-date').value = '';
        document.getElementById('msg-end-date').value = '';
        document.getElementById('msg-id-min').value = '';
        document.getElementById('msg-id-max').value = '';
        document.querySelectorAll('.msg-status-cb').forEach(function (cb) { cb.checked = true; });
        currentOffset = 0;
    }

    function prevPage() { currentOffset = Math.max(0, currentOffset - currentLimit); doSearch(); }
    function nextPage() { currentOffset += currentLimit; doSearch(); }

    function fmtDate(d) {
        if (!d) return '';
        try {
            var date;
            if (typeof d === 'string') date = new Date(d);
            else if (d.time) date = new Date(d.time);
            else if (d.timeInMillis) date = new Date(d.timeInMillis);
            else date = new Date(d);
            if (isNaN(date.getTime())) return '';
            return date.toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
        } catch (e) { return ''; }
    }

    function esc(s) { return s ? String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') : ''; }
    function escAttr(s) { return esc(s).replace(/"/g,'&quot;'); }

    function destroy() { selectedMessage = null; currentMessages = []; }

    return { render: render };
})();
