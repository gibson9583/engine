/**
 * Server Settings View - View and edit server configuration.
 */
var SettingsView = (function () {
    'use strict';

    var originalSettings = null;

    function render(container) {
        container.innerHTML =
            '<div class="container-fluid py-4">' +
                '<div class="d-flex justify-content-between align-items-center mb-4">' +
                    '<h4 class="mb-0"><i class="bi bi-gear me-2"></i>Server Settings</h4>' +
                    '<button class="btn btn-outline-secondary btn-sm" id="settings-refresh-btn">' +
                        '<i class="bi bi-arrow-clockwise me-1"></i>Refresh' +
                    '</button>' +
                '</div>' +
                '<!-- Server Info -->' +
                '<div class="card shadow-sm mb-4">' +
                    '<div class="card-header"><h6 class="mb-0">Server Information</h6></div>' +
                    '<div class="card-body" id="settings-info">' +
                        '<div class="text-center py-3"><div class="spinner-border text-primary"></div></div>' +
                    '</div>' +
                '</div>' +
                '<!-- Settings Tabs -->' +
                '<div class="card shadow-sm">' +
                    '<div class="card-header">' +
                        '<ul class="nav nav-tabs card-header-tabs" id="settings-tabs">' +
                            '<li class="nav-item">' +
                                '<button class="nav-link active" data-bs-toggle="tab" data-bs-target="#tab-general">General</button>' +
                            '</li>' +
                            '<li class="nav-item">' +
                                '<button class="nav-link" data-bs-toggle="tab" data-bs-target="#tab-email">Email (SMTP)</button>' +
                            '</li>' +
                            '<li class="nav-item">' +
                                '<button class="nav-link" data-bs-toggle="tab" data-bs-target="#tab-login">Login &amp; Security</button>' +
                            '</li>' +
                        '</ul>' +
                    '</div>' +
                    '<div class="card-body">' +
                        '<div class="tab-content" id="settings-content">' +
                            '<div class="text-center py-3"><div class="spinner-border text-primary"></div></div>' +
                        '</div>' +
                        '<div class="mt-4 border-top pt-3" id="settings-actions" style="display: none;">' +
                            '<button class="btn btn-primary" id="settings-save-btn">' +
                                '<i class="bi bi-check-lg me-1"></i>Save Settings' +
                            '</button>' +
                            '<button class="btn btn-outline-secondary ms-2" id="settings-reset-btn">' +
                                '<i class="bi bi-arrow-counterclockwise me-1"></i>Reset' +
                            '</button>' +
                        '</div>' +
                    '</div>' +
                '</div>' +
            '</div>';

        document.getElementById('settings-refresh-btn').addEventListener('click', loadAll);
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
            renderSettings(results[1]);
        })
        .catch(function (err) {
            var infoEl = document.getElementById('settings-info');
            if (infoEl) {
                infoEl.innerHTML = '<div class="alert alert-danger mb-0">' +
                    'Failed to load settings: ' + escapeHtml(err.message) + '</div>';
            }
        });
    }

    function renderServerInfo(about) {
        var infoEl = document.getElementById('settings-info');
        if (!infoEl) return;

        // about is a map of server info
        var version = about.version || about.Version || 'Unknown';
        var serverId = about.serverId || about['Server Id'] || about.id || 'Unknown';
        var javaVersion = about.javaVersion || about['JVM Version'] || 'Unknown';
        var osName = about.osName || about['OS Name'] || '';
        var osArch = about.osArchitecture || about['OS Architecture'] || '';
        var dbName = about.databaseName || about['Database Name'] || '';

        infoEl.innerHTML =
            '<div class="row g-3">' +
                infoItem('Server Version', version) +
                infoItem('Server ID', serverId) +
                infoItem('Java Version', javaVersion) +
                infoItem('Operating System', osName + (osArch ? ' (' + osArch + ')' : '')) +
                infoItem('Database', dbName) +
            '</div>';
    }

    function infoItem(label, value) {
        return '<div class="col-md-4 col-lg-3">' +
            '<div class="text-muted small">' + escapeHtml(label) + '</div>' +
            '<div class="fw-medium">' + escapeHtml(value || 'N/A') + '</div>' +
        '</div>';
    }

    function renderSettings(settings) {
        originalSettings = JSON.parse(JSON.stringify(settings));

        var contentEl = document.getElementById('settings-content');
        var actionsEl = document.getElementById('settings-actions');
        if (!contentEl) return;

        contentEl.innerHTML =
            '<!-- General Tab -->' +
            '<div class="tab-pane fade show active" id="tab-general">' +
                '<div class="row g-3">' +
                    formGroup('Environment Name', 'settings-env-name', 'text', settings.environmentName, 'e.g., Production, Development') +
                    formGroup('Server Name', 'settings-server-name', 'text', settings.serverName, 'e.g., Mirth-Server-01') +
                    formGroup('Queue Buffer Size', 'settings-queue-buffer', 'number', settings.queueBufferSize || 1000, 'Buffer size for destination queues') +
                    formCheckbox('Clear Global Map on Redeploy', 'settings-clear-global-map', settings.clearGlobalMap !== false) +
                '</div>' +
            '</div>' +
            '<!-- Email Tab -->' +
            '<div class="tab-pane fade" id="tab-email">' +
                '<div class="row g-3">' +
                    formGroup('SMTP Host', 'settings-smtp-host', 'text', settings.smtpHost, 'e.g., smtp.example.com') +
                    formGroup('SMTP Port', 'settings-smtp-port', 'text', settings.smtpPort || '25', 'e.g., 25, 465, 587') +
                    formGroup('SMTP Timeout (ms)', 'settings-smtp-timeout', 'number', settings.smtpTimeout || 5000, 'Timeout in milliseconds') +
                    formGroup('Default From Address', 'settings-smtp-from', 'email', settings.smtpFrom, 'e.g., mirth@example.com') +
                    '<div class="col-md-6">' +
                        '<label class="form-label">Encryption</label>' +
                        '<select class="form-select" id="settings-smtp-secure">' +
                            '<option value="none"' + (settings.smtpSecure === 'none' || !settings.smtpSecure ? ' selected' : '') + '>None</option>' +
                            '<option value="tls"' + (settings.smtpSecure === 'tls' ? ' selected' : '') + '>TLS</option>' +
                            '<option value="ssl"' + (settings.smtpSecure === 'ssl' ? ' selected' : '') + '>SSL</option>' +
                        '</select>' +
                    '</div>' +
                    formCheckbox('SMTP Authentication', 'settings-smtp-auth', settings.smtpAuth === true) +
                    '<div id="smtp-auth-fields"' + (settings.smtpAuth ? '' : ' style="display: none;"') + '>' +
                        '<div class="row g-3">' +
                            formGroup('SMTP Username', 'settings-smtp-username', 'text', settings.smtpUsername, 'SMTP username') +
                            formGroup('SMTP Password', 'settings-smtp-password', 'password', settings.smtpPassword, 'SMTP password') +
                        '</div>' +
                    '</div>' +
                    '<div class="col-12 mt-3">' +
                        '<button class="btn btn-outline-primary btn-sm" id="settings-test-email-btn">' +
                            '<i class="bi bi-envelope me-1"></i>Send Test Email' +
                        '</button>' +
                    '</div>' +
                '</div>' +
            '</div>' +
            '<!-- Login Tab -->' +
            '<div class="tab-pane fade" id="tab-login">' +
                '<div class="row g-3">' +
                    formCheckbox('Enable Login Notification', 'settings-login-notification', settings.loginNotificationEnabled === true) +
                    '<div class="col-12" id="login-notification-fields"' + (settings.loginNotificationEnabled ? '' : ' style="display: none;"') + '>' +
                        '<label class="form-label">Login Notification Message</label>' +
                        '<textarea class="form-control" id="settings-login-notification-msg" rows="3">' +
                            escapeHtml(settings.loginNotificationMessage || '') +
                        '</textarea>' +
                    '</div>' +
                    formCheckbox('Enable Auto Logout', 'settings-auto-logout', settings.administratorAutoLogoutIntervalEnabled === true) +
                    '<div class="col-md-6" id="auto-logout-fields"' + (settings.administratorAutoLogoutIntervalEnabled ? '' : ' style="display: none;"') + '>' +
                        '<label class="form-label">Auto Logout Interval (minutes)</label>' +
                        '<input type="number" class="form-control" id="settings-auto-logout-interval" ' +
                            'value="' + (settings.administratorAutoLogoutIntervalField || 30) + '" min="1">' +
                    '</div>' +
                '</div>' +
            '</div>';

        actionsEl.style.display = '';

        // Attach event listeners
        document.getElementById('settings-save-btn').addEventListener('click', saveSettings);
        document.getElementById('settings-reset-btn').addEventListener('click', function () { renderSettings(originalSettings); });

        var smtpAuthCheckbox = document.getElementById('settings-smtp-auth');
        if (smtpAuthCheckbox) {
            smtpAuthCheckbox.addEventListener('change', function () {
                document.getElementById('smtp-auth-fields').style.display = this.checked ? '' : 'none';
            });
        }

        var loginNotifCheckbox = document.getElementById('settings-login-notification');
        if (loginNotifCheckbox) {
            loginNotifCheckbox.addEventListener('change', function () {
                document.getElementById('login-notification-fields').style.display = this.checked ? '' : 'none';
            });
        }

        var autoLogoutCheckbox = document.getElementById('settings-auto-logout');
        if (autoLogoutCheckbox) {
            autoLogoutCheckbox.addEventListener('change', function () {
                document.getElementById('auto-logout-fields').style.display = this.checked ? '' : 'none';
            });
        }

        var testEmailBtn = document.getElementById('settings-test-email-btn');
        if (testEmailBtn) {
            testEmailBtn.addEventListener('click', onTestEmail);
        }
    }

    function formGroup(label, id, type, value, placeholder) {
        var val = (value !== null && value !== undefined) ? value : '';
        return '<div class="col-md-6">' +
            '<label for="' + id + '" class="form-label">' + escapeHtml(label) + '</label>' +
            '<input type="' + type + '" class="form-control" id="' + id + '" ' +
                'value="' + escapeAttr(String(val)) + '" ' +
                'placeholder="' + escapeAttr(placeholder || '') + '">' +
        '</div>';
    }

    function formCheckbox(label, id, checked) {
        return '<div class="col-12">' +
            '<div class="form-check form-switch">' +
                '<input class="form-check-input" type="checkbox" id="' + id + '"' + (checked ? ' checked' : '') + '>' +
                '<label class="form-check-label" for="' + id + '">' + escapeHtml(label) + '</label>' +
            '</div>' +
        '</div>';
    }

    function collectSettings() {
        return {
            environmentName: getVal('settings-env-name'),
            serverName: getVal('settings-server-name'),
            queueBufferSize: parseInt(getVal('settings-queue-buffer'), 10) || 1000,
            clearGlobalMap: isChecked('settings-clear-global-map'),
            smtpHost: getVal('settings-smtp-host'),
            smtpPort: getVal('settings-smtp-port'),
            smtpTimeout: getVal('settings-smtp-timeout'),
            smtpFrom: getVal('settings-smtp-from'),
            smtpSecure: getVal('settings-smtp-secure'),
            smtpAuth: isChecked('settings-smtp-auth'),
            smtpUsername: getVal('settings-smtp-username'),
            smtpPassword: getVal('settings-smtp-password'),
            loginNotificationEnabled: isChecked('settings-login-notification'),
            loginNotificationMessage: getVal('settings-login-notification-msg'),
            administratorAutoLogoutIntervalEnabled: isChecked('settings-auto-logout'),
            administratorAutoLogoutIntervalField: parseInt(getVal('settings-auto-logout-interval'), 10) || 30
        };
    }

    function getVal(id) {
        var el = document.getElementById(id);
        return el ? el.value : '';
    }

    function isChecked(id) {
        var el = document.getElementById(id);
        return el ? el.checked : false;
    }

    function saveSettings() {
        var settings = collectSettings();
        var btn = document.getElementById('settings-save-btn');
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving...';

        MirthAPI.setServerSettings(settings)
            .then(function () {
                App.showToast('Server settings saved successfully.', 'success');
                originalSettings = JSON.parse(JSON.stringify(settings));
            })
            .catch(function (err) {
                App.showToast('Failed to save settings: ' + err.message, 'danger');
            })
            .finally(function () {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-check-lg me-1"></i>Save Settings';
            });
    }

    function onTestEmail() {
        var toAddress = prompt('Enter the email address to send a test email to:');
        if (!toAddress) return;

        var btn = document.getElementById('settings-test-email-btn');
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Sending...';

        var props = {
            host: getVal('settings-smtp-host'),
            port: getVal('settings-smtp-port'),
            timeout: getVal('settings-smtp-timeout'),
            fromAddress: getVal('settings-smtp-from'),
            toAddress: toAddress,
            encryption: getVal('settings-smtp-secure'),
            authentication: isChecked('settings-smtp-auth'),
            username: getVal('settings-smtp-username'),
            password: getVal('settings-smtp-password')
        };

        MirthAPI.sendTestEmail(props)
            .then(function (result) {
                if (result && result.type === 'SUCCESS') {
                    App.showToast('Test email sent successfully!', 'success');
                } else {
                    App.showToast('Test email failed: ' + (result.message || 'Unknown error'), 'danger');
                }
            })
            .catch(function (err) {
                App.showToast('Failed to send test email: ' + err.message, 'danger');
            })
            .finally(function () {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-envelope me-1"></i>Send Test Email';
            });
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
