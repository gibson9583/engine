/**
 * Mirth Connect REST API Client
 * Wraps the Mirth Connect REST API for use by the web admin interface.
 */
var MirthAPI = (function () {
    'use strict';

    var baseUrl = '../api';

    function getHeaders(contentType) {
        var headers = {
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        };
        if (contentType) {
            headers['Content-Type'] = contentType;
        }
        return headers;
    }

    function handleResponse(response) {
        if (response.status === 401 || response.status === 403) {
            window.dispatchEvent(new CustomEvent('mirth:sessionExpired'));
            return Promise.reject({ status: response.status, message: 'Session expired' });
        }
        if (!response.ok) {
            return response.text().then(function (text) {
                var message = text;
                try {
                    var json = JSON.parse(text);
                    message = json.message || json.error || text;
                } catch (e) { /* use raw text */ }
                return Promise.reject({ status: response.status, message: message });
            });
        }
        var contentType = response.headers.get('Content-Type') || '';
        if (contentType.indexOf('application/json') !== -1) {
            return response.json();
        }
        return response.text();
    }

    function get(path, params) {
        var url = baseUrl + path;
        if (params) {
            var qs = [];
            Object.keys(params).forEach(function (key) {
                var val = params[key];
                if (Array.isArray(val)) {
                    val.forEach(function (v) {
                        qs.push(encodeURIComponent(key) + '=' + encodeURIComponent(v));
                    });
                } else if (val !== undefined && val !== null) {
                    qs.push(encodeURIComponent(key) + '=' + encodeURIComponent(val));
                }
            });
            if (qs.length > 0) {
                url += '?' + qs.join('&');
            }
        }
        return fetch(url, {
            method: 'GET',
            headers: getHeaders(),
            credentials: 'same-origin'
        }).then(handleResponse);
    }

    function post(path, body, contentType) {
        return fetch(baseUrl + path, {
            method: 'POST',
            headers: getHeaders(contentType || 'application/json'),
            credentials: 'same-origin',
            body: body
        }).then(handleResponse);
    }

    function put(path, body) {
        return fetch(baseUrl + path, {
            method: 'PUT',
            headers: getHeaders('application/json'),
            credentials: 'same-origin',
            body: typeof body === 'string' ? body : JSON.stringify(body)
        }).then(handleResponse);
    }

    function del(path) {
        return fetch(baseUrl + path, {
            method: 'DELETE',
            headers: getHeaders(),
            credentials: 'same-origin'
        }).then(handleResponse);
    }

    // ----- Authentication -----

    function login(username, password) {
        var formData = new URLSearchParams();
        formData.append('username', username);
        formData.append('password', password);
        return post('/users/_login', formData.toString(), 'application/x-www-form-urlencoded');
    }

    function logout() {
        return post('/users/_logout', null, 'application/json');
    }

    function getCurrentUser() {
        return get('/users/current');
    }

    // ----- Dashboard / Channel Status -----

    function getDashboardChannelInfo(fetchSize, filter) {
        var params = { fetchSize: fetchSize || 100 };
        if (filter) params.filter = filter;
        return get('/channels/statuses/initial', params);
    }

    function getChannelStatuses(channelIds, includeUndeployed) {
        var params = {};
        if (channelIds && channelIds.length > 0) params.channelId = channelIds;
        if (includeUndeployed) params.includeUndeployed = true;
        return get('/channels/statuses', params);
    }

    function getChannelStatistics(channelIds, includeUndeployed) {
        var params = {};
        if (channelIds && channelIds.length > 0) params.channelId = channelIds;
        if (includeUndeployed) params.includeUndeployed = true;
        return get('/channels/statistics', params);
    }

    // ----- Channel Control -----

    function startChannel(channelId) {
        return post('/channels/' + encodeURIComponent(channelId) + '/_start', null, 'application/json');
    }

    function stopChannel(channelId) {
        return post('/channels/' + encodeURIComponent(channelId) + '/_stop', null, 'application/json');
    }

    function pauseChannel(channelId) {
        return post('/channels/' + encodeURIComponent(channelId) + '/_pause', null, 'application/json');
    }

    function resumeChannel(channelId) {
        return post('/channels/' + encodeURIComponent(channelId) + '/_resume', null, 'application/json');
    }

    function haltChannel(channelId) {
        return post('/channels/' + encodeURIComponent(channelId) + '/_halt', null, 'application/json');
    }

    // ----- Channels -----

    function getChannels(channelIds) {
        var params = {};
        if (channelIds && channelIds.length > 0) params.channelId = channelIds;
        return get('/channels', params);
    }

    function getChannelIdsAndNames() {
        return get('/channels/idsAndNames');
    }

    function getConnectorNames(channelId) {
        return get('/channels/' + encodeURIComponent(channelId) + '/connectorNames');
    }

    function getMetaDataColumns(channelId) {
        return get('/channels/' + encodeURIComponent(channelId) + '/metaDataColumns');
    }

    // ----- Messages -----

    function getMessages(channelId, params) {
        return get('/channels/' + encodeURIComponent(channelId) + '/messages', params);
    }

    function getMessageCount(channelId, params) {
        return get('/channels/' + encodeURIComponent(channelId) + '/messages/count', params);
    }

    function getMessage(channelId, messageId) {
        return get('/channels/' + encodeURIComponent(channelId) + '/messages/' + encodeURIComponent(messageId));
    }

    function reprocessMessage(channelId, messageId, replace) {
        var params = {};
        if (replace) params.replace = true;
        var qs = replace ? '?replace=true' : '';
        return post('/channels/' + encodeURIComponent(channelId) + '/messages/' + encodeURIComponent(messageId) + '/_reprocess' + qs, null, 'application/json');
    }

    function removeMessage(channelId, messageId) {
        return del('/channels/' + encodeURIComponent(channelId) + '/messages/' + encodeURIComponent(messageId));
    }

    // ----- Server Configuration -----

    function getServerSettings() {
        return get('/server/settings');
    }

    function setServerSettings(settings) {
        return put('/server/settings', settings);
    }

    function getServerAbout() {
        return get('/server/about');
    }

    function getServerVersion() {
        return get('/server/version');
    }

    function getServerStatus() {
        return get('/server/status');
    }

    function getServerTime() {
        return get('/server/time');
    }

    function getPublicServerSettings() {
        return get('/server/publicSettings');
    }

    function getUpdateSettings() {
        return get('/server/updateSettings');
    }

    function setUpdateSettings(settings) {
        return put('/server/updateSettings', settings);
    }

    function getGlobalScripts() {
        return get('/server/globalScripts');
    }

    function getConfigurationMap() {
        return get('/server/configurationMap');
    }

    function getResources() {
        return get('/server/resources');
    }

    function getDatabaseDrivers() {
        return get('/server/databaseDrivers');
    }

    function getPasswordRequirements() {
        return get('/server/passwordRequirements');
    }

    function getChannelTags() {
        return get('/server/channelTags');
    }

    function sendTestEmail(properties) {
        return post('/server/_testEmail', JSON.stringify(properties), 'application/json');
    }

    // ----- System -----

    function getSystemInfo() {
        return get('/system/info');
    }

    function getSystemStats() {
        return get('/system/stats');
    }

    return {
        login: login,
        logout: logout,
        getCurrentUser: getCurrentUser,
        getDashboardChannelInfo: getDashboardChannelInfo,
        getChannelStatuses: getChannelStatuses,
        getChannelStatistics: getChannelStatistics,
        startChannel: startChannel,
        stopChannel: stopChannel,
        pauseChannel: pauseChannel,
        resumeChannel: resumeChannel,
        haltChannel: haltChannel,
        getChannels: getChannels,
        getChannelIdsAndNames: getChannelIdsAndNames,
        getConnectorNames: getConnectorNames,
        getMetaDataColumns: getMetaDataColumns,
        getMessages: getMessages,
        getMessageCount: getMessageCount,
        getMessage: getMessage,
        reprocessMessage: reprocessMessage,
        removeMessage: removeMessage,
        getServerSettings: getServerSettings,
        setServerSettings: setServerSettings,
        getServerAbout: getServerAbout,
        getServerVersion: getServerVersion,
        getServerStatus: getServerStatus,
        getServerTime: getServerTime,
        getPublicServerSettings: getPublicServerSettings,
        getUpdateSettings: getUpdateSettings,
        setUpdateSettings: setUpdateSettings,
        getGlobalScripts: getGlobalScripts,
        getConfigurationMap: getConfigurationMap,
        getResources: getResources,
        getDatabaseDrivers: getDatabaseDrivers,
        getPasswordRequirements: getPasswordRequirements,
        getChannelTags: getChannelTags,
        sendTestEmail: sendTestEmail,
        getSystemInfo: getSystemInfo,
        getSystemStats: getSystemStats
    };
})();
