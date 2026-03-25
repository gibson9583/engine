/**
 * Simple hash-based SPA router for the web admin.
 */
var Router = (function () {
    'use strict';

    var routes = {};
    var currentView = null;
    var container = null;

    function register(path, handler) {
        routes[path] = handler;
    }

    function navigate(path) {
        window.location.hash = path;
    }

    function start(containerId) {
        container = document.getElementById(containerId);
        window.addEventListener('hashchange', onHashChange);
    }

    function route() {
        onHashChange();
    }

    function onHashChange() {
        if (!container) return;

        var hash = window.location.hash || '#/dashboard';
        var raw = hash.substring(1);
        var qIdx = raw.indexOf('?');
        var path = qIdx >= 0 ? raw.substring(0, qIdx) : raw;
        var qs = qIdx >= 0 ? raw.substring(qIdx + 1) : '';
        var params = parseQuery(qs);

        var handler = routes[path];
        if (!handler) {
            handler = routes['/dashboard'];
            path = '/dashboard';
        }

        if (currentView && typeof currentView.destroy === 'function') {
            currentView.destroy();
        }

        // Update nav active state
        document.querySelectorAll('[data-nav]').forEach(function (el) {
            el.classList.remove('active');
            if ('/' + el.getAttribute('data-nav') === path) {
                el.classList.add('active');
            }
        });

        // Update content title
        var title = document.getElementById('content-title');
        if (title) {
            var titles = { '/dashboard': 'Dashboard', '/messages': 'Message Browser', '/settings': 'Settings' };
            title.textContent = titles[path] || 'Dashboard';
        }

        currentView = handler(container, params);
    }

    function parseQuery(qs) {
        var params = {};
        if (!qs) return params;
        qs.split('&').forEach(function (pair) {
            var kv = pair.split('=');
            if (kv[0]) params[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || '');
        });
        return params;
    }

    function getCurrentPath() {
        var hash = window.location.hash || '#/dashboard';
        var raw = hash.substring(1);
        var qIdx = raw.indexOf('?');
        return qIdx >= 0 ? raw.substring(0, qIdx) : raw;
    }

    return {
        register: register,
        navigate: navigate,
        start: start,
        route: route,
        getCurrentPath: getCurrentPath
    };
})();
