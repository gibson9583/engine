/**
 * Login View - Handles user authentication.
 */
var LoginView = (function () {
    'use strict';

    function render(container) {
        container.innerHTML =
            '<div class="login-wrapper">' +
                '<div class="login-card card shadow">' +
                    '<div class="card-body p-5">' +
                        '<div class="text-center mb-4">' +
                            '<i class="bi bi-heart-pulse display-3 text-primary"></i>' +
                            '<h2 class="mt-3">Mirth Connect</h2>' +
                            '<p class="text-muted">Web Administrator</p>' +
                        '</div>' +
                        '<div id="login-alert" class="alert d-none"></div>' +
                        '<form id="login-form">' +
                            '<div class="mb-3">' +
                                '<label for="login-username" class="form-label">Username</label>' +
                                '<div class="input-group">' +
                                    '<span class="input-group-text"><i class="bi bi-person"></i></span>' +
                                    '<input type="text" class="form-control" id="login-username" ' +
                                        'placeholder="Enter username" autocomplete="username" required>' +
                                '</div>' +
                            '</div>' +
                            '<div class="mb-4">' +
                                '<label for="login-password" class="form-label">Password</label>' +
                                '<div class="input-group">' +
                                    '<span class="input-group-text"><i class="bi bi-lock"></i></span>' +
                                    '<input type="password" class="form-control" id="login-password" ' +
                                        'placeholder="Enter password" autocomplete="current-password" required>' +
                                '</div>' +
                            '</div>' +
                            '<button type="submit" class="btn btn-primary w-100 py-2" id="login-btn">' +
                                '<span id="login-btn-text">Sign In</span>' +
                                '<span id="login-spinner" class="spinner-border spinner-border-sm d-none ms-2"></span>' +
                            '</button>' +
                        '</form>' +
                    '</div>' +
                '</div>' +
            '</div>';

        var form = document.getElementById('login-form');
        form.addEventListener('submit', onSubmit);

        // Focus username field
        document.getElementById('login-username').focus();

        return { destroy: destroy };
    }

    function onSubmit(e) {
        e.preventDefault();
        var username = document.getElementById('login-username').value.trim();
        var password = document.getElementById('login-password').value;
        var alertEl = document.getElementById('login-alert');
        var btnText = document.getElementById('login-btn-text');
        var spinner = document.getElementById('login-spinner');
        var btn = document.getElementById('login-btn');

        if (!username || !password) {
            showAlert(alertEl, 'Please enter both username and password.', 'warning');
            return;
        }

        btn.disabled = true;
        btnText.textContent = 'Signing in...';
        spinner.classList.remove('d-none');
        alertEl.classList.add('d-none');

        MirthAPI.login(username, password)
            .then(function (result) {
                if (result && (result.status === 'SUCCESS' || result.status === 'SUCCESS_GRACE_PERIOD')) {
                    if (result.status === 'SUCCESS_GRACE_PERIOD') {
                        App.showToast('Your password will expire soon. Please change it.', 'warning');
                    }
                    var displayName = result.updatedUsername || username;
                    App.onLoginSuccess(displayName);
                } else {
                    var msg = getLoginErrorMessage(result);
                    showAlert(alertEl, msg, 'danger');
                    resetButton(btn, btnText, spinner);
                }
            })
            .catch(function (err) {
                var msg = err.message || 'Login failed. Please check your credentials and try again.';
                showAlert(alertEl, msg, 'danger');
                resetButton(btn, btnText, spinner);
            });
    }

    function getLoginErrorMessage(result) {
        if (!result) return 'Login failed. Server returned an unexpected response.';
        switch (result.status) {
            case 'FAIL':
                return result.message || 'Invalid username or password.';
            case 'FAIL_EXPIRED':
                return 'Your password has expired. Please contact an administrator.';
            case 'FAIL_LOCKED_OUT':
                return 'Your account has been locked. Please contact an administrator.';
            case 'FAIL_VERSION_MISMATCH':
                return 'Client/server version mismatch. Please refresh the page.';
            default:
                return result.message || 'Login failed.';
        }
    }

    function showAlert(el, message, type) {
        el.className = 'alert alert-' + type;
        el.textContent = message;
        el.classList.remove('d-none');
    }

    function resetButton(btn, btnText, spinner) {
        btn.disabled = false;
        btnText.textContent = 'Sign In';
        spinner.classList.add('d-none');
    }

    function destroy() {
        var form = document.getElementById('login-form');
        if (form) form.removeEventListener('submit', onSubmit);
    }

    return { render: render };
})();
