/**
 * Simple hash-based SPA router for Mirth Web Admin.
 */
var Router = (function () {
    'use strict';

    var routes = {};
    var currentView = null;
    var appContainer = null;

    function register(path, viewFactory) {
        routes[path] = viewFactory;
    }

    function navigate(path) {
        window.location.hash = path;
    }

    function start(containerId) {
        appContainer = document.getElementById(containerId);
        window.addEventListener('hashchange', onHashChange);
        onHashChange();
    }

    function onHashChange() {
        var hash = window.location.hash || '#/login';
        var path = hash.substring(1); // remove '#'

        // Extract base path and query params
        var parts = path.split('?');
        var basePath = parts[0];
        var queryString = parts[1] || '';
        var params = parseQuery(queryString);

        var viewFactory = routes[basePath];
        if (!viewFactory) {
            // Try matching parameterized routes
            viewFactory = routes['/login'];
            basePath = '/login';
        }

        if (currentView && typeof currentView.destroy === 'function') {
            currentView.destroy();
        }

        // Update nav active state
        document.querySelectorAll('[data-nav]').forEach(function (el) {
            el.classList.remove('active');
            if (basePath.indexOf('/' + el.getAttribute('data-nav')) === 0) {
                el.classList.add('active');
            }
        });

        currentView = viewFactory(appContainer, params);
    }

    function parseQuery(qs) {
        var params = {};
        if (!qs) return params;
        qs.split('&').forEach(function (pair) {
            var kv = pair.split('=');
            if (kv[0]) {
                params[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || '');
            }
        });
        return params;
    }

    function getCurrentPath() {
        var hash = window.location.hash || '#/login';
        return hash.substring(1).split('?')[0];
    }

    return {
        register: register,
        navigate: navigate,
        start: start,
        getCurrentPath: getCurrentPath
    };
})();
