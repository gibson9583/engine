/**
 * Dashboard View - Channel status tree table with statistics.
 * Mimics the classic Mirth administrator dashboard layout.
 */
var DashboardView = (function () {
    'use strict';

    var refreshTimer = null;
    var REFRESH_INTERVAL = 5000;
    var useLifetimeStats = false;
    var selectedChannelId = null;

    function render(container) {
        container.innerHTML =
            '<div class="dash-split">' +
                '<div class="dash-split-top">' +
                    '<!-- Toolbar -->' +
                    '<div class="dash-toolbar">' +
                        '<label for="dash-filter">Filter:</label>' +
                        '<input type="text" id="dash-filter" placeholder="Channel name or tag..." style="width:180px;">' +
                        '<div class="toolbar-sep"></div>' +
                        '<div class="toolbar-radio-group">' +
                            '<button class="toolbar-radio active" id="dash-stats-current">Current Statistics</button>' +
                            '<button class="toolbar-radio" id="dash-stats-lifetime">Lifetime Statistics</button>' +
                        '</div>' +
                        '<div class="toolbar-sep"></div>' +
                        '<label style="min-width:unset;">' +
                            '<input type="checkbox" id="dash-include-undeployed"> Include Undeployed' +
                        '</label>' +
                    '</div>' +
                    '<!-- Table -->' +
                    '<div class="data-table-wrap">' +
                        '<table class="data-table" id="dash-table">' +
                            '<thead>' +
                                '<tr>' +
                                    '<th style="width:28px;"></th>' +
                                    '<th>Name</th>' +
                                    '<th style="width:50px;" class="text-right">Rev \u0394</th>' +
                                    '<th style="width:130px;">Last Deployed</th>' +
                                    '<th style="width:80px;" class="text-right">Received</th>' +
                                    '<th style="width:80px;" class="text-right">Filtered</th>' +
                                    '<th style="width:70px;" class="text-right">Queued</th>' +
                                    '<th style="width:80px;" class="text-right">Sent</th>' +
                                    '<th style="width:80px;" class="text-right">Errored</th>' +
                                '</tr>' +
                            '</thead>' +
                            '<tbody id="dash-body">' +
                                '<tr><td colspan="9" class="loading-indicator">' +
                                    '<span class="loading-spinner"></span> Loading channel statuses...' +
                                '</td></tr>' +
                            '</tbody>' +
                        '</table>' +
                    '</div>' +
                '</div>' +
                '<div class="dash-split-bottom">' +
                    '<div class="tab-bar">' +
                        '<button class="tab-btn active" data-tab="summary">Channel Summary</button>' +
                    '</div>' +
                    '<div class="tab-content" id="dash-tab-content">' +
                        'Select a channel to view details.' +
                    '</div>' +
                '</div>' +
            '</div>';

        // Event listeners
        document.getElementById('dash-filter').addEventListener('input', debounce(onFilterChange, 200));
        document.getElementById('dash-stats-current').addEventListener('click', function () { setStatsMode(false); });
        document.getElementById('dash-stats-lifetime').addEventListener('click', function () { setStatsMode(true); });
        document.getElementById('dash-include-undeployed').addEventListener('change', loadData);

        // Set task pane actions
        App.setTaskActions('Dashboard Tasks', [
            { icon: 'bi-arrow-clockwise', label: 'Refresh', shortcut: 'R', action: loadData },
            { icon: 'bi-play-fill', label: 'Start Channel', shortcut: '', action: function () { channelAction('start'); }, id: 'task-start' },
            { icon: 'bi-pause-fill', label: 'Pause Channel', shortcut: '', action: function () { channelAction('pause'); }, id: 'task-pause' },
            { icon: 'bi-stop-fill', label: 'Stop Channel', shortcut: '', action: function () { channelAction('stop'); }, id: 'task-stop' },
            { icon: 'bi-envelope-open', label: 'View Messages', shortcut: '', action: viewMessages, id: 'task-view-messages' }
        ]);

        loadData();
        startAutoRefresh();

        return { destroy: destroy };
    }

    function loadData() {
        var includeUndeployed = document.getElementById('dash-include-undeployed');
        var undeployed = includeUndeployed ? includeUndeployed.checked : false;

        MirthAPI.getChannelStatuses(null, undeployed)
            .then(function (statuses) {
                if (!document.getElementById('dash-body')) return;
                renderTable(statuses);
            })
            .catch(function (err) {
                if (!document.getElementById('dash-body')) return;
                document.getElementById('dash-body').innerHTML =
                    '<tr class="empty-row"><td colspan="9">Failed to load: ' + esc(err.message) + '</td></tr>';
            });
    }

    function renderTable(statuses) {
        var tbody = document.getElementById('dash-body');
        if (!tbody) return;

        var filter = (document.getElementById('dash-filter').value || '').toLowerCase();

        // Filter to channel-level statuses
        var channels = (statuses || []).filter(function (s) {
            return !s.statusType || s.statusType === 'CHANNEL';
        });

        if (filter) {
            channels = channels.filter(function (ch) {
                return (ch.name || '').toLowerCase().indexOf(filter) !== -1 ||
                       (ch.channelId || '').toLowerCase().indexOf(filter) !== -1;
            });
        }

        if (channels.length === 0) {
            tbody.innerHTML = '<tr class="empty-row"><td colspan="9">No channels found.</td></tr>';
            return;
        }

        var html = '';
        channels.forEach(function (ch) {
            var state = ch.state || ch.deployedState || 'UNKNOWN';
            var stats = useLifetimeStats ? (ch.lifetimeStatistics || ch.statistics || {}) : (ch.statistics || {});
            var revDelta = ch.deployedRevisionDelta || 0;
            var deployed = ch.deployedDate ? formatDate(ch.deployedDate) : '';
            var isSelected = ch.channelId === selectedChannelId;

            html += '<tr class="channel-row' + (isSelected ? ' selected' : '') + '" data-channel-id="' + escAttr(ch.channelId) + '" data-channel-name="' + escAttr(ch.name) + '">' +
                '<td>' + bulletForState(state) + '</td>' +
                '<td>' +
                    '<i class="bi bi-hdd-network" style="font-size:12px;color:#666;margin-right:4px;"></i>' +
                    esc(ch.name || ch.channelId) +
                    (revDelta > 0 ? '<span class="badge-modified">modified</span>' : '') +
                '</td>' +
                '<td class="text-right">' + (revDelta > 0 ? revDelta : '') + '</td>' +
                '<td>' + deployed + '</td>' +
                '<td class="text-right">' + fmtN(stats.RECEIVED) + '</td>' +
                '<td class="text-right">' + fmtN(stats.FILTERED) + '</td>' +
                '<td class="text-right">' + fmtQueued(ch.queued) + '</td>' +
                '<td class="text-right">' + fmtN(stats.SENT) + '</td>' +
                '<td class="text-right">' + fmtError(stats.ERROR) + '</td>' +
            '</tr>';

            // Connector sub-rows
            if (ch.childStatuses) {
                ch.childStatuses.forEach(function (child) {
                    var cState = child.state || child.deployedState || 'UNKNOWN';
                    var cStats = useLifetimeStats ? (child.lifetimeStatistics || child.statistics || {}) : (child.statistics || {});
                    var icon = child.metaDataId === 0 ? 'bi-box-arrow-in-right' : 'bi-box-arrow-right';
                    html += '<tr data-channel-id="' + escAttr(ch.channelId) + '">' +
                        '<td>' + bulletForState(cState) + '</td>' +
                        '<td class="tree-indent">' +
                            '<i class="bi ' + icon + '" style="font-size:11px;color:#888;margin-right:4px;"></i>' +
                            esc(child.name || 'Connector ' + child.metaDataId) +
                        '</td>' +
                        '<td></td><td></td>' +
                        '<td class="text-right">' + fmtN(cStats.RECEIVED) + '</td>' +
                        '<td class="text-right">' + fmtN(cStats.FILTERED) + '</td>' +
                        '<td class="text-right">' + fmtQueued(child.queued) + '</td>' +
                        '<td class="text-right">' + fmtN(cStats.SENT) + '</td>' +
                        '<td class="text-right">' + fmtError(cStats.ERROR) + '</td>' +
                    '</tr>';
                });
            }
        });

        tbody.innerHTML = html;

        // Row click handlers
        tbody.querySelectorAll('.channel-row').forEach(function (row) {
            row.addEventListener('click', function () { onRowClick(this); });
            row.addEventListener('dblclick', function () { viewMessagesForChannel(this.getAttribute('data-channel-id')); });
        });
    }

    function onRowClick(row) {
        // Deselect previous
        var prev = document.querySelector('.channel-row.selected');
        if (prev) prev.classList.remove('selected');
        row.classList.add('selected');
        selectedChannelId = row.getAttribute('data-channel-id');

        // Update bottom panel
        var name = row.getAttribute('data-channel-name');
        var tabContent = document.getElementById('dash-tab-content');
        if (tabContent) {
            tabContent.innerHTML =
                '<strong>' + esc(name) + '</strong> &mdash; Channel ID: <code>' + esc(selectedChannelId) + '</code>';
        }

        // Enable/disable task actions
        updateTaskStates();
    }

    function updateTaskStates() {
        // Tasks depend on selection
        var hasSelection = !!selectedChannelId;
        ['task-start', 'task-pause', 'task-stop', 'task-view-messages'].forEach(function (id) {
            var el = document.getElementById(id);
            if (el) {
                if (hasSelection) el.classList.remove('disabled');
                else el.classList.add('disabled');
            }
        });
    }

    function channelAction(action) {
        if (!selectedChannelId) return;
        App.showWorking(true);

        var apiCall;
        switch (action) {
            case 'start': apiCall = MirthAPI.startChannel(selectedChannelId); break;
            case 'stop': apiCall = MirthAPI.stopChannel(selectedChannelId); break;
            case 'pause': apiCall = MirthAPI.pauseChannel(selectedChannelId); break;
            case 'resume': apiCall = MirthAPI.resumeChannel(selectedChannelId); break;
            case 'halt': apiCall = MirthAPI.haltChannel(selectedChannelId); break;
            default: return;
        }

        apiCall
            .then(function () {
                App.showToast('Channel ' + action + ' command sent.', 'success');
                setTimeout(loadData, 1000);
            })
            .catch(function (err) {
                App.showToast('Failed to ' + action + ': ' + err.message, 'danger');
            })
            .finally(function () { App.showWorking(false); });
    }

    function viewMessages() {
        if (selectedChannelId) viewMessagesForChannel(selectedChannelId);
    }

    function viewMessagesForChannel(channelId) {
        Router.navigate('/messages?channelId=' + encodeURIComponent(channelId));
    }

    function setStatsMode(lifetime) {
        useLifetimeStats = lifetime;
        document.getElementById('dash-stats-current').classList.toggle('active', !lifetime);
        document.getElementById('dash-stats-lifetime').classList.toggle('active', lifetime);
        loadData();
    }

    function onFilterChange() {
        loadData();
    }

    function startAutoRefresh() {
        stopAutoRefresh();
        refreshTimer = setInterval(loadData, REFRESH_INTERVAL);
    }

    function stopAutoRefresh() {
        if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
    }

    // Helpers
    function bulletForState(state) {
        var cls = 'bullet-gray';
        switch (state) {
            case 'STARTED': cls = 'bullet-green'; break;
            case 'STOPPED': cls = 'bullet-red'; break;
            case 'PAUSED': cls = 'bullet-yellow'; break;
            case 'DEPLOYING': cls = 'bullet-blue'; break;
        }
        return '<span class="status-bullet ' + cls + '" title="' + esc(state) + '"></span>';
    }

    function fmtN(n) {
        if (!n) return '0';
        return Number(n).toLocaleString();
    }

    function fmtQueued(n) {
        if (!n || n === 0) return '0';
        return '<span class="stat-queued">' + Number(n).toLocaleString() + '</span>';
    }

    function fmtError(n) {
        if (!n || n === 0) return '0';
        return '<span class="stat-error">' + Number(n).toLocaleString() + '</span>';
    }

    function formatDate(d) {
        if (!d) return '';
        try {
            var date;
            if (typeof d === 'string') date = new Date(d);
            else if (d.time) date = new Date(d.time);
            else if (d.timeInMillis) date = new Date(d.timeInMillis);
            else date = new Date(d);
            if (isNaN(date.getTime())) return '';
            return date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
        } catch (e) { return ''; }
    }

    function esc(s) { return s ? String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') : ''; }
    function escAttr(s) { return esc(s).replace(/"/g,'&quot;'); }

    function debounce(fn, ms) {
        var t; return function () { clearTimeout(t); t = setTimeout(fn, ms); };
    }

    function destroy() {
        stopAutoRefresh();
        selectedChannelId = null;
    }

    return { render: render };
})();
