/**
 * Server Settings View - Tabbed settings panel matching admin layout.
 */
var SettingsView = (function () {
    'use strict';

    var originalSettings = null;
    var activeTab = 'server';

    function render(container) {
        container.innerHTML =
            '<div style="display:flex;flex-direction:column;height:100%;">' +
                '<div class="settings-tabs" id="settings-tabs">' +
                    '<button class="settings-tab active" data-tab="server">Server</button>' +
                    '<button class="settings-tab" data-tab="email">Email</button>' +
                    '<button class="settings-tab" data-tab="security">Security</button>' +
                '</div>' +
                '<div class="settings-body" id="settings-body">' +
                    '<div class="loading-indicator"><span class="loading-spinner"></span> Loading settings...</div>' +
                '</div>' +
                '<div class="settings-actions" id="settings-actions" style="display:none;">' +
                    '<button class="msg-btn msg-btn-primary" id="settings-save">Save Changes</button>' +
                    '<button class="msg-btn" id="settings-revert">Revert</button>' +
                '</div>' +
            '</div>';

        // Tab switching
        document.querySelectorAll('.settings-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                document.querySelectorAll('.settings-tab').forEach(function (t) { t.classList.remove('active'); });
                this.classList.add('active');
                activeTab = this.getAttribute('data-tab');
                renderActiveTab();
            });
        });

        // Task pane
        App.setTaskActions('Settings Tasks', [
            { icon: 'bi-arrow-clockwise', label: 'Refresh', shortcut: 'R', action: loadAll },
            { icon: 'bi-check-lg', label: 'Save Changes', shortcut: '', action: saveSettings }
        ]);

        loadAll();
        return { destroy: destroy };
    }

    function loadAll() {
        Promise.all([
            MirthAPI.getServerAbout(),
            MirthAPI.getServerSettings()
        ])
        .then(function (results) {
            renderServerInfo(results[0]);
            originalSettings = JSON.parse(JSON.stringify(results[1]));
            renderActiveTab();
            document.getElementById('settings-actions').style.display = '';
            document.getElementById('settings-save').addEventListener('click', saveSettings);
            document.getElementById('settings-revert').addEventListener('click', function () { renderActiveTab(); });
        })
        .catch(function (err) {
            document.getElementById('settings-body').innerHTML =
                '<div style="padding:20px;color:#c62828;">Failed to load settings: ' + esc(err.message) + '</div>';
        });
    }

    var serverInfo = null;

    function renderServerInfo(about) {
        serverInfo = about;
    }

    function renderActiveTab() {
        var body = document.getElementById('settings-body');
        if (!body) return;

        switch (activeTab) {
            case 'server': renderServerTab(body); break;
            case 'email': renderEmailTab(body); break;
            case 'security': renderSecurityTab(body); break;
        }
    }

    function renderServerTab(body) {
        var s = originalSettings || {};
        var info = serverInfo || {};

        body.innerHTML =
            '<div class="settings-group">' +
                '<div class="settings-group-title">Server Information</div>' +
                '<div class="settings-info-grid">' +
                    infoItem('Version', info.version || info.Version) +
                    infoItem('Server ID', info.serverId || info['Server Id'] || info.id) +
                    infoItem('Java Version', info.javaVersion || info['JVM Version']) +
                    infoItem('OS', (info.osName || info['OS Name'] || '') + ' ' + (info.osArchitecture || info['OS Architecture'] || '')) +
                    infoItem('Database', info.databaseName || info['Database Name']) +
                '</div>' +
            '</div>' +
            '<div class="settings-group">' +
                '<div class="settings-group-title">General Settings</div>' +
                settingsField('Environment Name', 'text', 'settings-env-name', s.environmentName, 'e.g. Production') +
                settingsField('Server Name', 'text', 'settings-server-name', s.serverName, 'e.g. Mirth-01') +
                settingsField('Queue Buffer Size', 'number', 'settings-queue-buffer', s.queueBufferSize || 1000, '') +
                settingsCheckbox('Clear Global Map on Redeploy', 'settings-clear-global', s.clearGlobalMap !== false) +
            '</div>';
    }

    function renderEmailTab(body) {
        var s = originalSettings || {};

        body.innerHTML =
            '<div class="settings-group">' +
                '<div class="settings-group-title">SMTP Configuration</div>' +
                settingsField('SMTP Host', 'text', 'settings-smtp-host', s.smtpHost, 'smtp.example.com') +
                settingsField('SMTP Port', 'text', 'settings-smtp-port', s.smtpPort || '25', '25') +
                settingsField('Timeout (ms)', 'number', 'settings-smtp-timeout', s.smtpTimeout || 5000, '5000') +
                settingsField('From Address', 'text', 'settings-smtp-from', s.smtpFrom, 'mirth@example.com') +
                '<div class="settings-row">' +
                    '<label>Encryption</label>' +
                    '<select id="settings-smtp-secure">' +
                        '<option value="none"' + ((!s.smtpSecure || s.smtpSecure === 'none') ? ' selected' : '') + '>None</option>' +
                        '<option value="tls"' + (s.smtpSecure === 'tls' ? ' selected' : '') + '>TLS</option>' +
                        '<option value="ssl"' + (s.smtpSecure === 'ssl' ? ' selected' : '') + '>SSL</option>' +
                    '</select>' +
                '</div>' +
            '</div>' +
            '<div class="settings-group">' +
                '<div class="settings-group-title">Authentication</div>' +
                settingsCheckbox('Require Authentication', 'settings-smtp-auth', s.smtpAuth === true) +
                '<div id="smtp-auth-fields"' + (s.smtpAuth ? '' : ' style="display:none;"') + '>' +
                    settingsField('Username', 'text', 'settings-smtp-user', s.smtpUsername, '') +
                    settingsField('Password', 'password', 'settings-smtp-pass', s.smtpPassword, '') +
                '</div>' +
            '</div>' +
            '<div class="settings-group">' +
                '<div class="settings-row">' +
                    '<label></label>' +
                    '<button class="msg-btn" id="settings-test-email"><i class="bi bi-envelope"></i> Send Test Email</button>' +
                '</div>' +
            '</div>';

        // Toggle auth fields
        var authCb = document.getElementById('settings-smtp-auth');
        if (authCb) {
            authCb.addEventListener('change', function () {
                document.getElementById('smtp-auth-fields').style.display = this.checked ? '' : 'none';
            });
        }

        var testBtn = document.getElementById('settings-test-email');
        if (testBtn) testBtn.addEventListener('click', onTestEmail);
    }

    function renderSecurityTab(body) {
        var s = originalSettings || {};

        body.innerHTML =
            '<div class="settings-group">' +
                '<div class="settings-group-title">Login Notification</div>' +
                settingsCheckbox('Enable Login Notification', 'settings-login-notif', s.loginNotificationEnabled === true) +
                '<div id="login-notif-fields"' + (s.loginNotificationEnabled ? '' : ' style="display:none;"') + '>' +
                    '<div class="settings-row">' +
                        '<label>Notification Message</label>' +
                        '<textarea id="settings-login-notif-msg" style="width:350px;">' + esc(s.loginNotificationMessage || '') + '</textarea>' +
                    '</div>' +
                '</div>' +
            '</div>' +
            '<div class="settings-group">' +
                '<div class="settings-group-title">Session Management</div>' +
                settingsCheckbox('Enable Auto Logout', 'settings-auto-logout', s.administratorAutoLogoutIntervalEnabled === true) +
                '<div id="auto-logout-fields"' + (s.administratorAutoLogoutIntervalEnabled ? '' : ' style="display:none;"') + '>' +
                    settingsField('Logout Interval (minutes)', 'number', 'settings-auto-logout-mins', s.administratorAutoLogoutIntervalField || 30, '30') +
                '</div>' +
            '</div>';

        var notifCb = document.getElementById('settings-login-notif');
        if (notifCb) notifCb.addEventListener('change', function () {
            document.getElementById('login-notif-fields').style.display = this.checked ? '' : 'none';
        });

        var logoutCb = document.getElementById('settings-auto-logout');
        if (logoutCb) logoutCb.addEventListener('change', function () {
            document.getElementById('auto-logout-fields').style.display = this.checked ? '' : 'none';
        });
    }

    // Helpers to build form rows
    function settingsField(label, type, id, value, placeholder) {
        var val = (value !== null && value !== undefined) ? value : '';
        return '<div class="settings-row">' +
            '<label for="' + id + '">' + esc(label) + '</label>' +
            '<input type="' + type + '" id="' + id + '" value="' + escAttr(String(val)) + '" placeholder="' + escAttr(placeholder || '') + '">' +
        '</div>';
    }

    function settingsCheckbox(label, id, checked) {
        return '<div class="settings-row">' +
            '<label></label>' +
            '<div class="settings-check">' +
                '<input type="checkbox" id="' + id + '"' + (checked ? ' checked' : '') + '>' +
                '<label for="' + id + '" style="min-width:unset;text-align:left;">' + esc(label) + '</label>' +
            '</div>' +
        '</div>';
    }

    function infoItem(label, value) {
        return '<div class="settings-info-item">' +
            '<span class="info-label">' + esc(label) + ':</span>' +
            '<span class="info-value">' + esc(value || 'N/A') + '</span>' +
        '</div>';
    }

    function collectSettings() {
        return {
            environmentName: gv('settings-env-name'),
            serverName: gv('settings-server-name'),
            queueBufferSize: parseInt(gv('settings-queue-buffer'), 10) || 1000,
            clearGlobalMap: gc('settings-clear-global'),
            smtpHost: gv('settings-smtp-host'),
            smtpPort: gv('settings-smtp-port'),
            smtpTimeout: gv('settings-smtp-timeout'),
            smtpFrom: gv('settings-smtp-from'),
            smtpSecure: gv('settings-smtp-secure'),
            smtpAuth: gc('settings-smtp-auth'),
            smtpUsername: gv('settings-smtp-user'),
            smtpPassword: gv('settings-smtp-pass'),
            loginNotificationEnabled: gc('settings-login-notif'),
            loginNotificationMessage: gv('settings-login-notif-msg'),
            administratorAutoLogoutIntervalEnabled: gc('settings-auto-logout'),
            administratorAutoLogoutIntervalField: parseInt(gv('settings-auto-logout-mins'), 10) || 30
        };
    }

    function gv(id) { var el = document.getElementById(id); return el ? el.value : ''; }
    function gc(id) { var el = document.getElementById(id); return el ? el.checked : false; }

    function saveSettings() {
        var settings = collectSettings();
        App.showWorking(true);

        MirthAPI.setServerSettings(settings)
            .then(function () {
                App.showToast('Settings saved successfully.', 'success');
                originalSettings = JSON.parse(JSON.stringify(settings));
            })
            .catch(function (err) {
                App.showToast('Save failed: ' + err.message, 'danger');
            })
            .finally(function () { App.showWorking(false); });
    }

    function onTestEmail() {
        var to = prompt('Enter recipient email address:');
        if (!to) return;

        App.showWorking(true);
        MirthAPI.sendTestEmail({
            host: gv('settings-smtp-host'),
            port: gv('settings-smtp-port'),
            timeout: gv('settings-smtp-timeout'),
            fromAddress: gv('settings-smtp-from'),
            toAddress: to,
            encryption: gv('settings-smtp-secure'),
            authentication: gc('settings-smtp-auth'),
            username: gv('settings-smtp-user'),
            password: gv('settings-smtp-pass')
        })
        .then(function (r) {
            if (r && r.type === 'SUCCESS') App.showToast('Test email sent!', 'success');
            else App.showToast('Test failed: ' + (r.message || 'Unknown error'), 'danger');
        })
        .catch(function (err) { App.showToast('Test failed: ' + err.message, 'danger'); })
        .finally(function () { App.showWorking(false); });
    }

    function esc(s) { return s ? String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') : ''; }
    function escAttr(s) { return esc(s).replace(/"/g,'&quot;'); }

    function destroy() {}

    return { render: render };
})();
