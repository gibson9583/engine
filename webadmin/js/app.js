/**
 * Mirth Connect Web Admin - Main Application
 */
var App = (function () {
    'use strict';

    var currentUser = null;

    function init() {
        // Register routes
        Router.register('/login', function (container) {
            hideNav();
            return LoginView.render(container);
        });

        Router.register('/dashboard', function (container) {
            if (!currentUser) { Router.navigate('/login'); return { destroy: function () {} }; }
            showNav();
            return DashboardView.render(container);
        });

        Router.register('/messages', function (container, params) {
            if (!currentUser) { Router.navigate('/login'); return { destroy: function () {} }; }
            showNav();
            return MessagesView.render(container, params);
        });

        Router.register('/settings', function (container) {
            if (!currentUser) { Router.navigate('/login'); return { destroy: function () {} }; }
            showNav();
            return SettingsView.render(container);
        });

        // Logout handler
        document.getElementById('btn-logout').addEventListener('click', function (e) {
            e.preventDefault();
            doLogout();
        });

        // Session expired handler
        window.addEventListener('mirth:sessionExpired', function () {
            currentUser = null;
            hideNav();
            Router.navigate('/login');
            showToast('Your session has expired. Please log in again.', 'warning');
        });

        // Start router
        Router.start('app');

        // Try to detect an existing session
        tryResumeSession();
    }

    function tryResumeSession() {
        MirthAPI.getCurrentUser()
            .then(function (user) {
                if (user && (user.username || user.id)) {
                    currentUser = user;
                    document.getElementById('nav-username').textContent = user.username || 'User';
                    showNav();
                    loadServerInfo();
                    if (Router.getCurrentPath() === '/login') {
                        Router.navigate('/dashboard');
                    }
                }
            })
            .catch(function () {
                // No active session - stay on login
                if (Router.getCurrentPath() !== '/login') {
                    Router.navigate('/login');
                }
            });
    }

    function onLoginSuccess(username) {
        currentUser = { username: username };
        document.getElementById('nav-username').textContent = username;
        showNav();
        loadServerInfo();
        Router.navigate('/dashboard');
    }

    function doLogout() {
        MirthAPI.logout()
            .catch(function () { /* ignore logout errors */ })
            .finally(function () {
                currentUser = null;
                hideNav();
                Router.navigate('/login');
                showToast('You have been logged out.', 'info');
            });
    }

    function loadServerInfo() {
        MirthAPI.getServerVersion()
            .then(function (version) {
                var el = document.getElementById('nav-server-info');
                if (el) el.textContent = 'v' + version;
            })
            .catch(function () { /* non-critical */ });
    }

    function showNav() {
        document.getElementById('main-nav').classList.remove('d-none');
    }

    function hideNav() {
        document.getElementById('main-nav').classList.add('d-none');
    }

    function showToast(message, type) {
        type = type || 'info';
        var iconMap = {
            success: 'bi-check-circle-fill',
            danger: 'bi-exclamation-triangle-fill',
            warning: 'bi-exclamation-circle-fill',
            info: 'bi-info-circle-fill'
        };
        var icon = iconMap[type] || iconMap.info;

        var toastId = 'toast-' + Date.now();
        var html =
            '<div id="' + toastId + '" class="toast align-items-center text-bg-' + type + ' border-0" role="alert">' +
                '<div class="d-flex">' +
                    '<div class="toast-body">' +
                        '<i class="bi ' + icon + ' me-2"></i>' + escapeHtml(message) +
                    '</div>' +
                    '<button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>' +
                '</div>' +
            '</div>';

        var container = document.getElementById('toast-container');
        container.insertAdjacentHTML('beforeend', html);

        var toastEl = document.getElementById(toastId);
        var toast = new bootstrap.Toast(toastEl, { delay: 4000 });
        toast.show();

        toastEl.addEventListener('hidden.bs.toast', function () {
            toastEl.remove();
        });
    }

    function escapeHtml(str) {
        if (!str) return '';
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return {
        onLoginSuccess: onLoginSuccess,
        showToast: showToast
    };
})();
