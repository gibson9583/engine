/**
 * Login View - handles form submission, error display, progress state.
 * The login UI is defined in index.html (not dynamically rendered).
 */
var LoginView = (function () {
    'use strict';

    function init() {
        var form = document.getElementById('login-form');
        form.addEventListener('submit', onSubmit);

        // Auto-populate server URL from current location
        var serverInput = document.getElementById('login-server');
        serverInput.value = window.location.protocol + '//' + window.location.host;

        document.getElementById('login-username').focus();
    }

    function onSubmit(e) {
        e.preventDefault();
        var username = document.getElementById('login-username').value.trim();
        var password = document.getElementById('login-password').value;

        if (!username || !password) {
            showError('Please enter both username and password.');
            return;
        }

        hideError();
        showProgress(true);
        setFormEnabled(false);

        MirthAPI.login(username, password)
            .then(function (result) {
                // Handle various response formats (JSON or parsed XML)
                var status = null;
                if (result) {
                    status = result.status || result.Status || null;
                    // If result is a string, it may be the status itself
                    if (typeof result === 'string') status = result;
                }

                if (status === 'SUCCESS' || status === 'SUCCESS_GRACE_PERIOD') {
                    if (status === 'SUCCESS_GRACE_PERIOD') {
                        App.showToast('Your password will expire soon. Please change it.', 'warning');
                    }
                    App.onLoginSuccess(result.updatedUsername || result.UpdatedUsername || username);
                } else {
                    showError(getErrorMessage(result));
                    showProgress(false);
                    setFormEnabled(true);
                }
            })
            .catch(function (err) {
                showError(err.message || 'Login failed. Please check your credentials.');
                showProgress(false);
                setFormEnabled(true);
            });
    }

    function getErrorMessage(result) {
        if (!result) return 'Login failed. Server returned an unexpected response.';
        switch (result.status) {
            case 'FAIL': return result.message || 'Invalid username or password.';
            case 'FAIL_EXPIRED': return 'Your password has expired. Please contact an administrator.';
            case 'FAIL_LOCKED_OUT': return 'Your account has been locked. Please contact an administrator.';
            case 'FAIL_VERSION_MISMATCH': return 'Client/server version mismatch. Please refresh.';
            default: return result.message || 'Login failed.';
        }
    }

    function showError(msg) {
        var el = document.getElementById('login-alert');
        el.textContent = msg;
        el.className = 'login-alert login-alert-error';
        el.style.display = '';
    }

    function hideError() {
        document.getElementById('login-alert').style.display = 'none';
    }

    function showProgress(show) {
        document.getElementById('login-progress').style.display = show ? '' : 'none';
    }

    function setFormEnabled(enabled) {
        document.getElementById('login-username').disabled = !enabled;
        document.getElementById('login-password').disabled = !enabled;
        document.getElementById('login-btn').disabled = !enabled;
    }

    function reset() {
        document.getElementById('login-password').value = '';
        hideError();
        showProgress(false);
        setFormEnabled(true);
        document.getElementById('login-username').focus();
    }

    return { init: init, reset: reset };
})();
