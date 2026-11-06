using System;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal sealed class LoginForm : Form
    {
        private readonly TextBox _url = new TextBox { MaxLength = 2048 };
        private readonly TextBox _user = new TextBox { MaxLength = 128 };
        private readonly TextBox _password = AdminSecurityUi.SecretField();
        private readonly TextBox _paper = AdminSecurityUi.SecretField();
        private readonly TextBox _code = new TextBox { MaxLength = 128 };
        private readonly CheckBox _rememberCredentials = new CheckBox();
        private readonly ComboBox _policy = new ComboBox();
        private readonly Label _status = AdminSecurityUi.Text("", UiTheme.Body, UiTheme.Danger);
        private readonly Label _stepTitle = AdminSecurityUi.Text("Вход администратора", UiTheme.H1, UiTheme.Text);
        private readonly Label _factorHint = AdminSecurityUi.Text("", UiTheme.Body, UiTheme.TextSecondary);
        private readonly Label _policyHint = AdminSecurityUi.Text("", UiTheme.Body, UiTheme.TextSecondary);
        private readonly Label _serverIdentity = AdminSecurityUi.Text("", UiTheme.BodySmall, UiTheme.Info);
        private readonly TextBox _paperDisplay = new TextBox { ReadOnly = true };
        private readonly TextBox _manualSecret = new TextBox { ReadOnly = true };
        private readonly CheckBox _savedPaper = new CheckBox();
        private readonly PictureBox _qr = new PictureBox { SizeMode = PictureBoxSizeMode.Zoom, Height = 180, Width = 180 };
        private readonly TableLayoutPanel _account = AdminSecurityUi.Stack(0);
        private readonly TableLayoutPanel _choice = AdminSecurityUi.Stack(0);
        private readonly TableLayoutPanel _factors = AdminSecurityUi.Stack(0);
        private readonly TableLayoutPanel _newPaper = AdminSecurityUi.Stack(12);
        private readonly TableLayoutPanel _newTotp = AdminSecurityUi.Stack(12);
        private readonly Control _paperField;
        private readonly Control _codeField;
        private readonly Button _loginButton = AdminSecurityUi.Button("Продолжить", UiButtonKind.Primary);
        private readonly Button _backButton = AdminSecurityUi.Button("Назад", UiButtonKind.Secondary);
        private readonly Button _monitorButton = AdminSecurityUi.Button("Мониторинг ПК", UiButtonKind.Ghost);
        private readonly Button _closeButton = AdminSecurityUi.Button("Закрыть", UiButtonKind.Ghost);
        private AdminAuth _pendingAuth;
        private AdminStatus _pendingStatus;
        private string _pendingPassword, _pendingPaper, _selectedPolicy, _prefilledIdentity;
        private bool _loadingCredentials, _busy, _closing;
        private readonly bool _useCredentialStore;
        private int _step;
        public AdminAuth Auth { get; private set; }
        public bool MonitoringOnly { get; private set; }

        public LoginForm() : this(true) { }
        internal LoginForm(bool useCredentialStore)
        {
            _useCredentialStore = useCredentialStore;
            UiTheme.ApplyDialog(this);
            Text = "UNYRI Admin Console — вход"; ShowInTaskbar = true;
            StartPosition = FormStartPosition.CenterScreen; FormBorderStyle = FormBorderStyle.Sizable;
            MinimumSize = new Size(520, 520); ClientSize = new Size(690, 710); Icon = IconFactory.AppIcon();
            var config = useCredentialStore ? AuthConfigStore.Load() : new AuthConfig(); _url.Text = config.BaseUrl; _user.Text = config.Username;
            AdminSecurityUi.Add(_account, AdminSecurityUi.Text("Управление выбранным сервером. Используйте аккаунт владельца или модератора.", UiTheme.Body, UiTheme.TextSecondary));
            AdminSecurityUi.Add(_account, AdminSecurityUi.Field("Адрес сервера", _url));
            AdminSecurityUi.Add(_account, AdminSecurityUi.Field("Логин администратора", _user));
            AdminSecurityUi.Add(_account, AdminSecurityUi.Field("Пароль аккаунта", _password));
            _rememberCredentials.Text = "Запомнить вход на этом компьютере"; _rememberCredentials.AutoSize = true;
            UiTheme.StyleCheckBox(_rememberCredentials, UiTheme.Background);
            AdminSecurityUi.Add(_account, _rememberCredentials);
            AdminSecurityUi.Add(_account, AdminSecurityUi.Text("Только на личном ПК. Windows DPAPI защитит пароль, бумажный ключ и сессию. Одноразовые коды не сохраняются. Без флажка вход не запоминается.", UiTheme.BodySmall, UiTheme.TextMuted));
            _policy.Items.AddRange(AdminSecurityPolicy.Options().ToArray()); _policy.SelectedIndex = 0; UiTheme.StyleInput(_policy);
            AdminSecurityUi.Add(_choice, AdminSecurityUi.Text("Выберите подтверждение входа. Пароль аккаунта нужен при любом варианте.", UiTheme.Body, UiTheme.TextSecondary));
            AdminSecurityUi.Add(_choice, AdminSecurityUi.Field("Способ защиты", _policy)); AdminSecurityUi.Add(_choice, _policyHint);
            _policy.SelectedIndexChanged += (s, e) => UpdatePolicyHint();
            AdminSecurityUi.Add(_factors, _factorHint);
            _paperField = AdminSecurityUi.Field("Сохранённый бумажный ключ — 32 символа", _paper);
            _codeField = AdminSecurityUi.Field("Код аутентификатора или резервный код", _code);
            AdminSecurityUi.Add(_factors, _paperField);
            AdminSecurityUi.Add(_newTotp, AdminSecurityUi.Text("Добавьте UNYRI в приложение-аутентификатор", UiTheme.H3, UiTheme.Text));
            AdminSecurityUi.Add(_newTotp, _qr);
            AdminSecurityUi.Add(_newTotp, AdminSecurityUi.Field("Если QR не сканируется — введите этот секрет вручную", _manualSecret));
            AdminSecurityUi.Add(_newTotp, AdminSecurityUi.Text("Затем введите свежий код ниже. Никому не передавайте этот секрет.", UiTheme.BodySmall, UiTheme.TextSecondary));
            AdminSecurityUi.Add(_newPaper, AdminSecurityUi.Text("Сохраните новый бумажный ключ", UiTheme.H3, UiTheme.Warning));
            AdminSecurityUi.Add(_newPaper, AdminSecurityUi.Field("Храните отдельно от пароля", _paperDisplay));
            _savedPaper.Text = "Я сохранил бумажный ключ в безопасном месте"; _savedPaper.AutoSize = true;
            UiTheme.StyleCheckBox(_savedPaper, UiTheme.Background); AdminSecurityUi.Add(_newPaper, _savedPaper);
            AdminSecurityUi.Add(_factors, _newTotp); AdminSecurityUi.Add(_factors, _newPaper); AdminSecurityUi.Add(_factors, _codeField);
            var body = AdminSecurityUi.Stack(24);
            AdminSecurityUi.Add(body, _stepTitle); AdminSecurityUi.Add(body, _serverIdentity); AdminSecurityUi.Add(body, _account);
            AdminSecurityUi.Add(body, _choice); AdminSecurityUi.Add(body, _factors); AdminSecurityUi.Add(body, _status);
            var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true }; scroll.Controls.Add(body);
            AdminSecurityUi.FitScrollingBody(scroll, body);
            var footer = AdminSecurityUi.Footer(); footer.Controls.AddRange(new Control[] { _loginButton, _backButton, _closeButton, _monitorButton });
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2 };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(scroll, 0, 0); root.Controls.Add(footer, 0, 1); Controls.Add(root);
            _loginButton.Click += async (s, e) => await ContinueAsync(); _backButton.Click += async (s, e) => await BackAsync();
            _closeButton.Click += (s, e) => Close();
            _monitorButton.Click += (s, e) => { MonitoringOnly = true; DialogResult = DialogResult.OK; Close(); };
            _url.TextChanged += (s, e) => ReloadRememberedCredentials(); _user.TextChanged += (s, e) => ReloadRememberedCredentials();
            _rememberCredentials.CheckedChanged += (s, e) => ForgetUncheckedCredentials(); FormClosing += OnClosing;
            AcceptButton = _loginButton; CancelButton = _closeButton; UpdatePolicyHint(); ShowStep(0);
            if (_useCredentialStore) ReloadRememberedCredentials();
        }

        private void ShowStep(int step)
        {
            _step = step; _account.Visible = step == 0; _choice.Visible = step == 1; _factors.Visible = step == 2;
            _backButton.Visible = step != 0; _monitorButton.Visible = step == 0; _serverIdentity.Visible = step != 0;
            _stepTitle.Text = step == 0 ? "Вход администратора" : step == 1 ? "Настройка защиты" : "Подтвердите вход";
            _loginButton.Text = step == 2 ? "Войти в консоль" : "Продолжить"; _status.Text = "";
        }
        private void UpdatePolicyHint() { var option = _policy.SelectedItem as AdminPolicyOption; if (option != null) _policyHint.Text = AdminSecurityPolicy.Description(option.Value); }
        private async Task ContinueAsync()
        {
            if (_busy) return; SetBusy(true);
            try { if (_step == 0) await BeginAccountAsync(); else if (_step == 1) await BeginSetupAsync(); else await UnlockAndCloseAsync(); }
            catch (Exception ex) { _status.Text = AdminSecurityUi.ExplainError(ex, _step == 2); }
            finally { if (!IsDisposed) SetBusy(false); }
        }
        private async Task BeginAccountAsync()
        {
            // Immutable input snapshot; none of the fields can change during the request.
            var baseUrl = _url.Text.Trim(); var username = _user.Text.Trim(); var password = _password.Text;
            if (username.Length == 0 || password.Length == 0) throw new InvalidOperationException("Введите логин и пароль аккаунта.");
            var config = _useCredentialStore ? AuthConfigStore.Load() : new AuthConfig { InstallationId = Guid.NewGuid().ToString() };
            config.BaseUrl = baseUrl; config.Username = username;
            var auth = new AdminAuth(baseUrl);
            Exception failure = null;
            try
            {
                await auth.LoginAsync(username, password, config.InstallationId); var status = await auth.GetAdminStatusAsync();
                if (_useCredentialStore) AuthConfigStore.Save(config);
                _pendingAuth = auth; _pendingStatus = status; _pendingPassword = password;
                _serverIdentity.Text = auth.BaseUrl + "\r\n@" + auth.Username + " · " + (auth.AdminRole == "owner" ? "Владелец" : "Модератор");
                if (status.Configured) ConfigureFactors(status.AuthPolicy, false, null);
                else
                {
                    var partialSetup = status.TotpConfigured || status.PaperKeyConfigured;
                    _policy.Enabled = status.SupportsFactorChoice && !partialSetup;
                    _policy.SelectedIndex = !status.SupportsFactorChoice || status.AuthPolicy == AdminSecurityPolicy.Both ? 2 : status.AuthPolicy == AdminSecurityPolicy.PaperKey ? 1 : 0;
                    if (status.SupportsFactorChoice && !partialSetup) _policy.SelectedIndex = 0;
                    ShowStep(1);
                    if (!status.SupportsFactorChoice) _status.Text = "Сервер поддерживает только оба способа вместе. Для выбора обновите сервер.";
                    else if (partialSetup) _status.Text = "Часть защиты уже настроена. Завершите её прежним способом; после входа сможете изменить выбор в разделе «Защита входа».";
                }
            }
            catch (Exception ex) { failure = ex; }
            if (failure != null)
            {
                if (ReferenceEquals(_pendingAuth, auth)) { _pendingAuth = null; _pendingStatus = null; _pendingPassword = null; }
                try { await auth.LogoutAsync(); } catch { }
                auth.Dispose();
                throw failure;
            }
        }
        private async Task BeginSetupAsync()
        {
            if (_pendingAuth == null || _pendingStatus == null) throw new InvalidOperationException("Войдите в аккаунт заново.");
            var selected = (AdminPolicyOption)_policy.SelectedItem;
            var setup = await _pendingAuth.SetupAsync(_pendingPassword, selected.Value, _pendingStatus.SupportsFactorChoice);
            _pendingStatus = await _pendingAuth.GetAdminStatusAsync(); ConfigureFactors(setup.Policy, true, setup);
        }
        private void ConfigureFactors(string policy, bool enrollment, AdminSetupResult setup)
        {
            _selectedPolicy = AdminSecurityPolicy.RequireKnown(policy);
            var paperRequired = AdminSecurityPolicy.UsesPaperKey(policy); var totpRequired = AdminSecurityPolicy.UsesAuthenticator(policy);
            // A TOTP reset leaves the old signing identity intact. Never replace its paper key.
            _pendingPaper = enrollment && paperRequired && !_pendingStatus.PaperKeyConfigured ? CryptoAdmin.GeneratePaperCode() : null;
            _paperField.Visible = paperRequired && _pendingPaper == null; _codeField.Visible = totpRequired;
            _newPaper.Visible = _pendingPaper != null; _newTotp.Visible = enrollment && totpRequired && setup != null && !string.IsNullOrEmpty(setup.Secret);
            _savedPaper.Checked = false; _paperDisplay.Text = AdminSecurityUi.FormatPaper(_pendingPaper); _manualSecret.Text = setup == null ? "" : setup.Secret;
            var previousQr = _qr.Image; _qr.Image = setup == null ? null : AdminSecurityUi.DecodeQr(setup.QrPng); if (previousQr != null) previousQr.Dispose();
            _code.Clear(); _factorHint.Text = AdminSecurityPolicy.Description(policy);
            if (enrollment && paperRequired && _pendingStatus.PaperKeyConfigured) _factorHint.Text += "\r\nИспользуйте прежний сохранённый бумажный ключ: он уже настроен, новый не создавался.";
            ShowStep(2);
        }
        private async Task UnlockAndCloseAsync()
        {
            if (_pendingAuth == null) throw new InvalidOperationException("Войдите в аккаунт заново.");
            if (_pendingPaper != null && !_savedPaper.Checked) throw new InvalidOperationException("Сначала сохраните бумажный ключ и отметьте это выше.");
            var paper = _pendingPaper ?? _paper.Text; var code = _code.Text.Trim(); var remember = _rememberCredentials.Checked; var auth = _pendingAuth;
            var result = await auth.UnlockAsync(_pendingPassword, paper, code, _selectedPolicy, _pendingStatus);
            try
            {
                if (!_useCredentialStore) { /* Isolated UI harness: never touch real credentials. */ }
                else if (remember) { AdminCredentialStore.Save(new RememberedAdminCredentials { BaseUrl = auth.BaseUrl, Username = auth.Username,
                    Password = _pendingPassword, PaperKey = AdminSecurityPolicy.UsesPaperKey(_selectedPolicy) ? CryptoAdmin.NormalizePaperCode(paper) : "" });
                    AdminCredentialStore.AdminSessionStore.Save(auth.ToPersistedSession()); }
                else { AdminCredentialStore.AdminSessionStore.Delete(); AdminCredentialStore.Delete(); }
            }
            catch (Exception ex) { ConsoleDialog.Show(this, "Вход выполнен, но состояние на ПК не удалось сохранить. При следующем запуске войдите заново.\r\n" + ex.Message, "Хранилище Windows", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
            Auth = auth; _pendingAuth = null; _pendingPassword = null; _pendingPaper = null;
            _password.Clear(); _paper.Clear(); _code.Clear(); _manualSecret.Clear(); _paperDisplay.Clear();
            AdminSecurityUi.ShowRecoveryCodes(this, result.RecoveryCodes); DialogResult = DialogResult.OK; _closing = true; Close();
        }
        private async Task BackAsync() { if (_busy) return; SetBusy(true); try { await ReleasePendingAsync(); ShowStep(0); } finally { if (!IsDisposed) SetBusy(false); } }
        private async Task ReleasePendingAsync()
        {
            var auth = _pendingAuth; _pendingAuth = null; _pendingStatus = null; _pendingPassword = null; _pendingPaper = null;
            _manualSecret.Clear(); _paperDisplay.Clear(); _code.Clear();
            if (_qr.Image != null) { var image = _qr.Image; _qr.Image = null; image.Dispose(); }
            if (auth != null) { try { await auth.LogoutAsync(); } catch { } finally { auth.Dispose(); } }
        }
        private async void OnClosing(object sender, FormClosingEventArgs e)
        {
            if (_closing) return; if (_busy) { e.Cancel = true; return; } if (_pendingAuth == null) return;
            e.Cancel = true; SetBusy(true); try { await ReleasePendingAsync(); } finally { _closing = true; Close(); }
        }
        private void SetBusy(bool busy)
        {
            _busy = busy; _account.Enabled = !busy; _choice.Enabled = !busy; _factors.Enabled = !busy;
            _loginButton.Enabled = !busy; _backButton.Enabled = !busy; _monitorButton.Enabled = !busy; _closeButton.Enabled = !busy;
            Cursor = busy ? Cursors.WaitCursor : Cursors.Default;
        }
        private void ReloadRememberedCredentials()
        {
            if (!_useCredentialStore || _loadingCredentials || _busy || _step != 0) return;
            var identity = _url.Text.Trim() + "|" + _user.Text.Trim(); if (string.Equals(identity, _prefilledIdentity, StringComparison.OrdinalIgnoreCase)) return;
            _loadingCredentials = true;
            try
            {
                if (_prefilledIdentity != null) { _password.Clear(); _paper.Clear(); _prefilledIdentity = null; }
                var saved = AdminCredentialStore.Load(_url.Text.Trim());
                if (saved == null || (!string.IsNullOrWhiteSpace(_user.Text) && !string.Equals(_user.Text.Trim(), saved.Username, StringComparison.OrdinalIgnoreCase))) return;
                _user.Text = saved.Username; _password.Text = saved.Password; _paper.Text = AdminSecurityUi.FormatPaper(saved.PaperKey);
                _prefilledIdentity = _url.Text.Trim() + "|" + _user.Text.Trim(); _rememberCredentials.Checked = true;
            }
            catch (Exception ex) { _status.Text = "Сохранённый вход недоступен: " + ex.Message; }
            finally { _loadingCredentials = false; }
        }
        private void ForgetUncheckedCredentials()
        {
            if (!_useCredentialStore || _loadingCredentials || _rememberCredentials.Checked) return;
            try { AdminCredentialStore.AdminSessionStore.Delete(); AdminCredentialStore.Delete(); _prefilledIdentity = null; _status.Text = "Сохранённый вход удалён с этого компьютера."; }
            catch (Exception ex) { _status.Text = "Не удалось удалить сохранённый вход: " + ex.Message; }
        }
        protected override void Dispose(bool disposing) { if (disposing && _qr.Image != null) { _qr.Image.Dispose(); _qr.Image = null; } base.Dispose(disposing); }
    }
}
