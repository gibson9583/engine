/**
 * Dashboard View - Shows channel statuses and statistics.
 */
var DashboardView = (function () {
    'use strict';

    var refreshTimer = null;
    var REFRESH_INTERVAL = 5000;

    function render(container) {
        container.innerHTML =
            '<div class="container-fluid py-4">' +
                '<div class="d-flex justify-content-between align-items-center mb-4">' +
                    '<h4 class="mb-0"><i class="bi bi-speedometer2 me-2"></i>Dashboard</h4>' +
                    '<div class="d-flex align-items-center gap-2">' +
                        '<input type="text" id="dash-filter" class="form-control form-control-sm" ' +
                            'placeholder="Filter channels..." style="width: 200px;">' +
                        '<div class="form-check form-switch ms-2">' +
                            '<input class="form-check-input" type="checkbox" id="dash-auto-refresh" checked>' +
                            '<label class="form-check-label small" for="dash-auto-refresh">Auto-refresh</label>' +
                        '</div>' +
                        '<button class="btn btn-outline-secondary btn-sm" id="dash-refresh-btn" title="Refresh">' +
                            '<i class="bi bi-arrow-clockwise"></i>' +
                        '</button>' +
                    '</div>' +
                '</div>' +
                '<!-- Summary Cards -->' +
                '<div class="row g-3 mb-4" id="dash-summary"></div>' +
                '<!-- Channel Status Table -->' +
                '<div class="card shadow-sm">' +
                    '<div class="card-body p-0">' +
                        '<div class="table-responsive">' +
                            '<table class="table table-hover mb-0" id="dash-table">' +
                                '<thead class="table-light">' +
                                    '<tr>' +
                                        '<th style="width: 30px;"></th>' +
                                        '<th>Channel Name</th>' +
                                        '<th>State</th>' +
                                        '<th class="text-end">Received</th>' +
                                        '<th class="text-end">Filtered</th>' +
                                        '<th class="text-end">Queued</th>' +
                                        '<th class="text-end">Sent</th>' +
                                        '<th class="text-end">Errored</th>' +
                                        '<th style="width: 140px;">Actions</th>' +
                                    '</tr>' +
                                '</thead>' +
                                '<tbody id="dash-body">' +
                                    '<tr><td colspan="9" class="text-center py-4">' +
                                        '<div class="spinner-border text-primary" role="status"></div>' +
                                        '<p class="text-muted mt-2">Loading channels...</p>' +
                                    '</td></tr>' +
                                '</tbody>' +
                            '</table>' +
                        '</div>' +
                    '</div>' +
                '</div>' +
            '</div>';

        document.getElementById('dash-refresh-btn').addEventListener('click', loadData);
        document.getElementById('dash-auto-refresh').addEventListener('change', toggleAutoRefresh);
        document.getElementById('dash-filter').addEventListener('input', onFilterChange);

        loadData();
        startAutoRefresh();

        return { destroy: destroy };
    }

    function loadData() {
        var filterVal = '';
        var filterEl = document.getElementById('dash-filter');
        if (filterEl) filterVal = filterEl.value.trim();

        MirthAPI.getChannelStatuses(null, true)
            .then(function (statuses) {
                if (!document.getElementById('dash-body')) return;
                renderSummary(statuses);
                renderTable(statuses, filterVal);
            })
            .catch(function (err) {
                if (!document.getElementById('dash-body')) return;
                document.getElementById('dash-body').innerHTML =
                    '<tr><td colspan="9" class="text-center text-danger py-4">' +
                    '<i class="bi bi-exclamation-triangle me-2"></i>Failed to load dashboard: ' +
                    escapeHtml(err.message) + '</td></tr>';
            });
    }

    function renderSummary(statuses) {
        var channels = statuses.filter(function (s) { return !s.childStatuses || s.statusType === 'CHANNEL'; });
        var started = 0, stopped = 0, paused = 0, errored = 0;
        var totalReceived = 0, totalSent = 0, totalErrored = 0;

        channels.forEach(function (ch) {
            var state = getState(ch);
            if (state === 'STARTED') started++;
            else if (state === 'STOPPED') stopped++;
            else if (state === 'PAUSED') paused++;

            var stats = ch.statistics || {};
            totalReceived += stats.RECEIVED || 0;
            totalSent += stats.SENT || 0;
            totalErrored += stats.ERROR || 0;
            if ((stats.ERROR || 0) > 0) errored++;
        });

        var summaryEl = document.getElementById('dash-summary');
        if (!summaryEl) return;

        summaryEl.innerHTML =
            summaryCard('bi-check-circle-fill text-success', 'Started', started, 'success') +
            summaryCard('bi-stop-circle-fill text-danger', 'Stopped', stopped, 'danger') +
            summaryCard('bi-pause-circle-fill text-warning', 'Paused', paused, 'warning') +
            summaryCard('bi-arrow-down-circle text-info', 'Total Received', formatNumber(totalReceived), 'info') +
            summaryCard('bi-arrow-up-circle text-primary', 'Total Sent', formatNumber(totalSent), 'primary') +
            summaryCard('bi-exclamation-triangle-fill text-danger', 'With Errors', errored, 'danger');
    }

    function summaryCard(icon, label, value, color) {
        return '<div class="col-xl-2 col-md-4 col-sm-6">' +
            '<div class="card border-' + color + ' border-start border-4 shadow-sm h-100">' +
                '<div class="card-body py-2 px-3">' +
                    '<div class="d-flex justify-content-between align-items-center">' +
                        '<div>' +
                            '<div class="text-muted small">' + label + '</div>' +
                            '<div class="fw-bold fs-5">' + value + '</div>' +
                        '</div>' +
                        '<i class="bi ' + icon + ' fs-3"></i>' +
                    '</div>' +
                '</div>' +
            '</div>' +
        '</div>';
    }

    function renderTable(statuses, filter) {
        var tbody = document.getElementById('dash-body');
        if (!tbody) return;

        // Filter to only channel-level statuses
        var channels = statuses.filter(function (s) {
            return !s.statusType || s.statusType === 'CHANNEL';
        });

        if (filter) {
            var lowerFilter = filter.toLowerCase();
            channels = channels.filter(function (ch) {
                return (ch.name || '').toLowerCase().indexOf(lowerFilter) !== -1 ||
                       (ch.channelId || '').toLowerCase().indexOf(lowerFilter) !== -1;
            });
        }

        if (channels.length === 0) {
            tbody.innerHTML = '<tr><td colspan="9" class="text-center text-muted py-4">' +
                'No channels found.</td></tr>';
            return;
        }

        var html = '';
        channels.forEach(function (ch) {
            var state = getState(ch);
            var stats = ch.statistics || {};
            var received = stats.RECEIVED || 0;
            var filtered = stats.FILTERED || 0;
            var queued = ch.queued || 0;
            var sent = stats.SENT || 0;
            var errored = stats.ERROR || 0;

            html += '<tr class="channel-row" data-channel-id="' + escapeAttr(ch.channelId) + '">' +
                '<td>' + stateIcon(state) + '</td>' +
                '<td>' +
                    '<a href="#/messages?channelId=' + encodeURIComponent(ch.channelId) + '" class="text-decoration-none fw-medium">' +
                        escapeHtml(ch.name || ch.channelId) +
                    '</a>' +
                    (ch.deployedRevisionDelta > 0 ?
                        ' <span class="badge bg-warning text-dark" title="Undeployed changes">modified</span>' : '') +
                '</td>' +
                '<td>' + stateBadge(state) + '</td>' +
                '<td class="text-end">' + formatNumber(received) + '</td>' +
                '<td class="text-end">' + formatNumber(filtered) + '</td>' +
                '<td class="text-end">' + (queued > 0 ? '<span class="text-warning fw-bold">' + formatNumber(queued) + '</span>' : '0') + '</td>' +
                '<td class="text-end">' + formatNumber(sent) + '</td>' +
                '<td class="text-end">' + (errored > 0 ? '<span class="text-danger fw-bold">' + formatNumber(errored) + '</span>' : '0') + '</td>' +
                '<td>' + channelActions(ch.channelId, state) + '</td>' +
            '</tr>';

            // Render child connector statuses
            if (ch.childStatuses && ch.childStatuses.length > 0) {
                ch.childStatuses.forEach(function (child) {
                    var childState = getState(child);
                    var childStats = child.statistics || {};
                    html += '<tr class="connector-row">' +
                        '<td></td>' +
                        '<td class="ps-4 text-muted small">' +
                            '<i class="bi bi-arrow-return-right me-1"></i>' +
                            escapeHtml(child.name || 'Connector ' + child.metaDataId) +
                        '</td>' +
                        '<td>' + stateBadge(childState) + '</td>' +
                        '<td class="text-end small">' + formatNumber(childStats.RECEIVED || 0) + '</td>' +
                        '<td class="text-end small">' + formatNumber(childStats.FILTERED || 0) + '</td>' +
                        '<td class="text-end small">' + (child.queued > 0 ? '<span class="text-warning">' + formatNumber(child.queued) + '</span>' : '0') + '</td>' +
                        '<td class="text-end small">' + formatNumber(childStats.SENT || 0) + '</td>' +
                        '<td class="text-end small">' + (childStats.ERROR > 0 ? '<span class="text-danger">' + formatNumber(childStats.ERROR) + '</span>' : '0') + '</td>' +
                        '<td></td>' +
                    '</tr>';
                });
            }
        });

        tbody.innerHTML = html;

        // Attach action listeners
        tbody.querySelectorAll('[data-action]').forEach(function (btn) {
            btn.addEventListener('click', onChannelAction);
        });
    }

    function onChannelAction(e) {
        var btn = e.currentTarget;
        var action = btn.getAttribute('data-action');
        var channelId = btn.getAttribute('data-channel-id');

        btn.disabled = true;
        var originalHtml = btn.innerHTML;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>';

        var apiCall;
        switch (action) {
            case 'start': apiCall = MirthAPI.startChannel(channelId); break;
            case 'stop': apiCall = MirthAPI.stopChannel(channelId); break;
            case 'pause': apiCall = MirthAPI.pauseChannel(channelId); break;
            case 'resume': apiCall = MirthAPI.resumeChannel(channelId); break;
            case 'halt': apiCall = MirthAPI.haltChannel(channelId); break;
            default: return;
        }

        apiCall
            .then(function () {
                App.showToast('Channel ' + action + ' command sent.', 'success');
                setTimeout(loadData, 1000);
            })
            .catch(function (err) {
                App.showToast('Failed to ' + action + ' channel: ' + err.message, 'danger');
            })
            .finally(function () {
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            });
    }

    function channelActions(channelId, state) {
        var actions = '';
        var cid = escapeAttr(channelId);

        if (state === 'STOPPED' || state === 'UNDEPLOYED') {
            actions += '<button class="btn btn-sm btn-outline-success me-1" data-action="start" data-channel-id="' + cid + '" title="Start">' +
                '<i class="bi bi-play-fill"></i></button>';
        }
        if (state === 'STARTED') {
            actions += '<button class="btn btn-sm btn-outline-warning me-1" data-action="pause" data-channel-id="' + cid + '" title="Pause">' +
                '<i class="bi bi-pause-fill"></i></button>';
            actions += '<button class="btn btn-sm btn-outline-danger me-1" data-action="stop" data-channel-id="' + cid + '" title="Stop">' +
                '<i class="bi bi-stop-fill"></i></button>';
        }
        if (state === 'PAUSED') {
            actions += '<button class="btn btn-sm btn-outline-success me-1" data-action="resume" data-channel-id="' + cid + '" title="Resume">' +
                '<i class="bi bi-play-fill"></i></button>';
            actions += '<button class="btn btn-sm btn-outline-danger me-1" data-action="stop" data-channel-id="' + cid + '" title="Stop">' +
                '<i class="bi bi-stop-fill"></i></button>';
        }

        return '<div class="btn-group btn-group-sm">' + actions + '</div>';
    }

    function getState(status) {
        if (status.state) return status.state;
        if (status.deployedState) return status.deployedState;
        return 'UNKNOWN';
    }

    function stateIcon(state) {
        switch (state) {
            case 'STARTED': return '<i class="bi bi-circle-fill text-success" title="Started"></i>';
            case 'STOPPED': return '<i class="bi bi-circle-fill text-danger" title="Stopped"></i>';
            case 'PAUSED': return '<i class="bi bi-circle-fill text-warning" title="Paused"></i>';
            case 'DEPLOYING': return '<i class="bi bi-circle-fill text-info" title="Deploying"></i>';
            case 'UNDEPLOYED': return '<i class="bi bi-circle text-secondary" title="Undeployed"></i>';
            default: return '<i class="bi bi-circle text-muted" title="' + escapeAttr(state) + '"></i>';
        }
    }

    function stateBadge(state) {
        var cls = 'secondary';
        switch (state) {
            case 'STARTED': cls = 'success'; break;
            case 'STOPPED': cls = 'danger'; break;
            case 'PAUSED': cls = 'warning'; break;
            case 'DEPLOYING': cls = 'info'; break;
        }
        return '<span class="badge bg-' + cls + '">' + escapeHtml(state) + '</span>';
    }

    function onFilterChange() {
        var filter = document.getElementById('dash-filter').value.trim();
        MirthAPI.getChannelStatuses(null, true)
            .then(function (statuses) {
                renderTable(statuses, filter);
            });
    }

    function toggleAutoRefresh() {
        var checked = document.getElementById('dash-auto-refresh').checked;
        if (checked) {
            startAutoRefresh();
        } else {
            stopAutoRefresh();
        }
    }

    function startAutoRefresh() {
        stopAutoRefresh();
        refreshTimer = setInterval(loadData, REFRESH_INTERVAL);
    }

    function stopAutoRefresh() {
        if (refreshTimer) {
            clearInterval(refreshTimer);
            refreshTimer = null;
        }
    }

    function formatNumber(n) {
        if (n === undefined || n === null) return '0';
        return n.toLocaleString();
    }

    function escapeHtml(str) {
        if (!str) return '';
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return escapeHtml(str).replace(/"/g, '&quot;');
    }

    function destroy() {
        stopAutoRefresh();
    }

    return { render: render };
})();
