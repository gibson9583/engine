/**
 * Main Application - manages login/logout, layout, routing, status bar.
 */
var App = (function () {
    'use strict';

    var currentUser = null;
    var clockTimer = null;

    function init() {
        // Register routes (all render into content-body)
        Router.register('/dashboard', function (container, params) {
            return DashboardView.render(container, params);
        });
        Router.register('/messages', function (container, params) {
            return MessagesView.render(container, params);
        });
        Router.register('/settings', function (container, params) {
            return SettingsView.render(container, params);
        });

        Router.start('content-body');

        // Login form
        LoginView.init();

        // Logout
        document.getElementById('task-logout').addEventListener('click', function (e) {
            e.preventDefault();
            doLogout();
        });

        // Session expired
        window.addEventListener('mirth:sessionExpired', function () {
            currentUser = null;
            showLogin();
            showToast('Session expired. Please log in again.', 'warning');
        });

        // Task section collapse
        document.querySelectorAll('[data-toggle-section]').forEach(function (header) {
            header.addEventListener('click', function () {
                this.closest('.task-section').classList.toggle('collapsed');
            });
        });

        // Try resuming existing session
        tryResumeSession();
    }

    function tryResumeSession() {
        MirthAPI.getCurrentUser()
            .then(function (user) {
                if (user && (user.username || user.id)) {
                    currentUser = user;
                    showApp(user.username || 'User');
                }
            })
            .catch(function () {
                showLogin();
            });
    }

    function onLoginSuccess(username) {
        currentUser = { username: username };
        showApp(username);
    }

    function showLogin() {
        document.getElementById('login-screen').style.display = '';
        document.getElementById('main-app').style.display = 'none';
        stopClock();
        LoginView.reset();
    }

    function showApp(username) {
        document.getElementById('login-screen').style.display = 'none';
        document.getElementById('main-app').style.display = '';

        document.getElementById('status-user').textContent = username;
        loadServerInfo();
        startClock();

        // Route to current hash or default to dashboard
        Router.route();
    }

    function doLogout() {
        MirthAPI.logout()
            .catch(function () {})
            .finally(function () {
                currentUser = null;
                showLogin();
                showToast('Logged out.', 'info');
            });
    }

    function loadServerInfo() {
        MirthAPI.getServerVersion()
            .then(function (version) {
                document.getElementById('status-server-info').textContent = 'Connected | v' + version;
                document.getElementById('login-version').textContent = 'v' + version;
            })
            .catch(function () {});
    }

    // --- Task Pane Actions ---

    function setTaskActions(title, actions) {
        var titleEl = document.getElementById('task-actions-title');
        var body = document.getElementById('task-actions-body');
        if (titleEl) titleEl.textContent = title;
        if (!body) return;

        var html = '';
        actions.forEach(function (a) {
            var disabled = a.disabled ? ' disabled' : '';
            var id = a.id ? ' id="' + a.id + '"' : '';
            html += '<a href="#" class="task-item' + disabled + '"' + id + ' data-task-action="true">' +
                '<i class="bi ' + a.icon + '"></i> ' + escHtml(a.label) +
                (a.shortcut ? '<span class="task-shortcut">' + a.shortcut + '</span>' : '') +
            '</a>';
        });
        body.innerHTML = html;

        // Bind actions
        var items = body.querySelectorAll('[data-task-action]');
        items.forEach(function (item, i) {
            if (actions[i] && actions[i].action) {
                item.addEventListener('click', function (e) {
                    e.preventDefault();
                    if (!this.classList.contains('disabled')) {
                        actions[i].action();
                    }
                });
            }
        });
    }

    // --- Status Bar ---

    function showWorking(show) {
        var el = document.getElementById('status-working');
        if (el) el.style.display = show ? '' : 'none';
    }

    function startClock() {
        updateClock();
        clockTimer = setInterval(updateClock, 1000);
    }

    function stopClock() {
        if (clockTimer) { clearInterval(clockTimer); clockTimer = null; }
    }

    function updateClock() {
        var el = document.getElementById('status-time');
        if (el) {
            var now = new Date();
            el.textContent = now.toLocaleTimeString() + ' ' + Intl.DateTimeFormat().resolvedOptions().timeZone;
        }
    }

    // --- Toasts ---

    function showToast(message, type) {
        type = type || 'info';
        var icons = { success: 'bi-check-circle-fill', danger: 'bi-exclamation-triangle-fill', warning: 'bi-exclamation-circle-fill', info: 'bi-info-circle-fill' };
        var id = 'toast-' + Date.now();
        var html =
            '<div id="' + id + '" class="toast align-items-center text-bg-' + type + ' border-0" role="alert">' +
                '<div class="d-flex">' +
                    '<div class="toast-body"><i class="bi ' + (icons[type] || icons.info) + ' me-2"></i>' + escHtml(message) + '</div>' +
                    '<button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>' +
                '</div>' +
            '</div>';

        var container = document.getElementById('toast-container');
        container.insertAdjacentHTML('beforeend', html);
        var el = document.getElementById(id);
        var toast = new bootstrap.Toast(el, { delay: 4000 });
        toast.show();
        el.addEventListener('hidden.bs.toast', function () { el.remove(); });
    }

    function escHtml(s) {
        if (!s) return '';
        var d = document.createElement('div');
        d.appendChild(document.createTextNode(s));
        return d.innerHTML;
    }

    // Init on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return {
        onLoginSuccess: onLoginSuccess,
        showToast: showToast,
        showWorking: showWorking,
        setTaskActions: setTaskActions
    };
})();
