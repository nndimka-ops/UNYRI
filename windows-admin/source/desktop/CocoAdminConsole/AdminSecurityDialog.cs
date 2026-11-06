using System;
using System.Collections.Generic;
using System.Drawing;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal sealed class AdminSecurityDialog : Form
    {
        private readonly AdminAuth _auth;
        private readonly AdminStatus _current;
        private readonly bool _useCredentialStore;
        private readonly TextBox _password = AdminSecurityUi.SecretField();
        private readonly TextBox _paper = AdminSecurityUi.SecretField();
        private readonly TextBox _code = new TextBox { MaxLength = 128 };
        private readonly TextBox _newCode = new TextBox { MaxLength = 6 };
        private readonly TextBox _newPaperDisplay = new TextBox { ReadOnly = true };
        private readonly TextBox _secret = new TextBox { ReadOnly = true };
        private readonly CheckBox _savedPaper = new CheckBox();
        private readonly ComboBox _policy = new ComboBox();
        private readonly Label _description = AdminSecurityUi.Text("", UiTheme.Body, UiTheme.TextSecondary);
        private readonly Label _status = AdminSecurityUi.Text("", UiTheme.Body, UiTheme.Danger);
        private readonly TableLayoutPanel _proofs = AdminSecurityUi.Stack(0);
        private readonly TableLayoutPanel _newTotp = AdminSecurityUi.Stack(12);
        private readonly TableLayoutPanel _newPaper = AdminSecurityUi.Stack(12);
        private readonly PictureBox _qr = new PictureBox { SizeMode = PictureBoxSizeMode.Zoom, Height = 180 };
        private readonly Button _apply = AdminSecurityUi.Button("Продолжить", UiButtonKind.Primary);
        private readonly Button _back = AdminSecurityUi.Button("Изменить выбор", UiButtonKind.Secondary);
        private readonly Button _cancel = AdminSecurityUi.Button("Отмена", UiButtonKind.Ghost);
        private string _targetPolicy, _preparedPassword, _pendingPaper;
        private bool _prepared, _requiresNewTotp, _busy, _changed;

        /// <returns>True only after the server changed policy and revoked admin sessions.</returns>
        public static async Task<bool> ShowForAsync(IWin32Window owner, AdminAuth auth)
        {
            if (auth == null || !auth.IsAdminUnlocked) throw new InvalidOperationException("Сначала войдите как администратор.");
            var data = await auth.SendAsync(HttpMethod.Get, "admin/security", null, true, true);
            var current = AdminAuth.ParseAdminStatus(data);
            if (!current.SupportsFactorChoice) throw new InvalidOperationException("Для выбора защиты обновите сервер UNYRI.");
            using (var dialog = new AdminSecurityDialog(auth, current))
            {
                dialog.ShowDialog(owner);
                return dialog._changed;
            }
        }

        internal AdminSecurityDialog(AdminAuth auth, AdminStatus current) : this(auth, current, true) { }
        internal AdminSecurityDialog(AdminAuth auth, AdminStatus current, bool useCredentialStore)
        {
            _auth = auth; _current = current; _useCredentialStore = useCredentialStore;
            UiTheme.ApplyDialog(this); Text = "Защита административного входа";
            FormBorderStyle = FormBorderStyle.Sizable; MinimumSize = new Size(540, 530); ClientSize = new Size(720, 760);
            var body = AdminSecurityUi.Stack(24);
            AdminSecurityUi.Add(body, AdminSecurityUi.Text("Ваш способ подтверждения", UiTheme.H1, UiTheme.Text));
            AdminSecurityUi.Add(body, AdminSecurityUi.Text(auth.BaseUrl + "\r\n@" + auth.Username + " · Сейчас: " + AdminSecurityPolicy.Label(current.AuthPolicy), UiTheme.BodySmall, UiTheme.Info));
            AdminSecurityUi.Add(body, AdminSecurityUi.Text("Меняется защита только вашего административного входа. Пароль аккаунта остаётся обязательным. После сохранения все ваши административные сессии завершатся: нужно будет войти выбранным способом.", UiTheme.Body, UiTheme.TextSecondary));
            _policy.Items.AddRange(AdminSecurityPolicy.Options().ToArray()); _policy.SelectedIndex = current.AuthPolicy == AdminSecurityPolicy.Authenticator ? 0 : current.AuthPolicy == AdminSecurityPolicy.PaperKey ? 1 : 2;
            UiTheme.StyleInput(_policy); AdminSecurityUi.Add(body, AdminSecurityUi.Field("Новый способ защиты", _policy));
            AdminSecurityUi.Add(body, _description);
            _policy.SelectedIndexChanged += (s, e) => UpdateDescription(); UpdateDescription();
            AdminSecurityUi.Add(body, AdminSecurityUi.Field("Введите пароль аккаунта заново", _password));

            AdminSecurityUi.Add(_proofs, AdminSecurityUi.Text("Подтвердите прежний способ защиты", UiTheme.H3, UiTheme.Text));
            AdminSecurityUi.Add(_proofs, AdminSecurityUi.Text("Введите свежие данные. Подготовка ещё ничего не меняет; старая защита действует до успешного сохранения.", UiTheme.Body, UiTheme.TextSecondary));
            var currentPaperField = AdminSecurityUi.Field("Текущий бумажный ключ", _paper);
            currentPaperField.Visible = AdminSecurityPolicy.UsesPaperKey(current.AuthPolicy); AdminSecurityUi.Add(_proofs, currentPaperField);
            var currentCodeField = AdminSecurityUi.Field("Текущий код аутентификатора или резервный код", _code);
            currentCodeField.Visible = AdminSecurityPolicy.UsesAuthenticator(current.AuthPolicy); AdminSecurityUi.Add(_proofs, currentCodeField);

            AdminSecurityUi.Add(_newTotp, AdminSecurityUi.Text("Настройте новый аутентификатор", UiTheme.H3, UiTheme.Text));
            AdminSecurityUi.Add(_newTotp, _qr); AdminSecurityUi.Add(_newTotp, AdminSecurityUi.Field("Секрет для ручного добавления", _secret));
            AdminSecurityUi.Add(_newTotp, AdminSecurityUi.Field("Код из только что добавленного аутентификатора", _newCode));
            AdminSecurityUi.Add(_newPaper, AdminSecurityUi.Text("Сохраните новый бумажный ключ", UiTheme.H3, UiTheme.Warning));
            AdminSecurityUi.Add(_newPaper, AdminSecurityUi.Field("Ключ больше не будет показан", _newPaperDisplay));
            _savedPaper.Text = "Я сохранил этот ключ отдельно от пароля"; _savedPaper.AutoSize = true;
            UiTheme.StyleCheckBox(_savedPaper, UiTheme.Background); AdminSecurityUi.Add(_newPaper, _savedPaper);
            AdminSecurityUi.Add(_proofs, _newTotp); AdminSecurityUi.Add(_proofs, _newPaper); AdminSecurityUi.Add(body, _proofs); AdminSecurityUi.Add(body, _status);
            var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true }; scroll.Controls.Add(body);
            AdminSecurityUi.FitScrollingBody(scroll, body);
            var footer = AdminSecurityUi.Footer(); footer.Controls.AddRange(new Control[] { _apply, _back, _cancel });
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2 };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(scroll, 0, 0); root.Controls.Add(footer, 0, 1); Controls.Add(root);
            _apply.Click += async (s, e) => await ContinueAsync(); _back.Click += (s, e) => ResetPreparation(); _cancel.Click += (s, e) => Close();
            FormClosing += (s, e) => { if (_busy && !_changed) e.Cancel = true; };
            AcceptButton = _apply; CancelButton = _cancel; ResetPreparation();
        }

        private void UpdateDescription()
        {
            var item = _policy.SelectedItem as AdminPolicyOption;
            if (item != null) _description.Text = AdminSecurityPolicy.Description(item.Value);
        }

        private void ResetPreparation()
        {
            _prepared = false; _preparedPassword = null; _pendingPaper = null; _targetPolicy = null; _requiresNewTotp = false;
            _policy.Enabled = true; _password.Enabled = true; _proofs.Visible = false; _back.Visible = false;
            _newPaperDisplay.Clear(); _secret.Clear(); _newCode.Clear(); _code.Clear(); _paper.Clear(); _savedPaper.Checked = false;
            if (_qr.Image != null) { var image = _qr.Image; _qr.Image = null; image.Dispose(); }
            _apply.Text = "Продолжить"; _status.Text = "";
        }

        private async Task ContinueAsync()
        {
            if (_busy) return; SetBusy(true);
            try { if (_prepared) await CommitAsync(); else await PrepareAsync(); }
            catch (Exception ex) { _status.Text = AdminSecurityUi.ExplainError(ex, _prepared); }
            finally { if (!IsDisposed) SetBusy(false); }
        }

        private async Task PrepareAsync()
        {
            var target = ((AdminPolicyOption)_policy.SelectedItem).Value; var password = _password.Text;
            if (target == _current.AuthPolicy) throw new InvalidOperationException("Этот способ уже используется. Выберите другой или нажмите «Отмена».");
            if (string.IsNullOrWhiteSpace(password)) throw new InvalidOperationException("Введите пароль аккаунта заново.");
            var data = await _auth.SendAsync(HttpMethod.Post, "admin/security/prepare", new Dictionary<string, object> { { "password", password }, { "auth_policy", target } }, true, true);
            if (AdminAuth.Str(data, "auth_policy") != target) throw new InvalidOperationException("Сервер вернул другой способ защиты. Изменения не сохранены.");
            _requiresNewTotp = AdminAuth.Bool(data, "requires_new_totp");
            var newPaperRequired = AdminAuth.Bool(data, "requires_new_paper_key");
            // Never regenerate a new key on a retry: proofs must refer to the displayed key.
            _pendingPaper = newPaperRequired ? CryptoAdmin.GeneratePaperCode() : null;
            _newPaperDisplay.Text = AdminSecurityUi.FormatPaper(_pendingPaper); _savedPaper.Checked = false;
            _newPaper.Visible = newPaperRequired; _newTotp.Visible = _requiresNewTotp;
            _secret.Text = AdminAuth.Str(data, "secret");
            var previousQr = _qr.Image; _qr.Image = AdminSecurityUi.DecodeQr(AdminAuth.Str(data, "qr_png")); if (previousQr != null) previousQr.Dispose();
            if (_requiresNewTotp && string.IsNullOrWhiteSpace(_secret.Text)) throw new InvalidOperationException("Сервер не подготовил новый аутентификатор. Повторите подготовку.");
            _targetPolicy = target; _preparedPassword = password; _prepared = true;
            _policy.Enabled = false; _password.Enabled = false; _proofs.Visible = true; _back.Visible = true;
            _apply.Text = "Сохранить защиту"; _status.Text = "Подготовлено на 10 минут. Нужен новый код, который ещё не использовали при входе. После ошибки дождитесь следующего кода и повторите.";
        }

        private async Task CommitAsync()
        {
            if (_pendingPaper != null && !_savedPaper.Checked) throw new InvalidOperationException("Сохраните новый бумажный ключ и отметьте это выше.");
            var currentPaper = _paper.Text; var currentCode = _code.Text.Trim(); var newCode = _newCode.Text.Trim();
            var body = new Dictionary<string, object> { { "password", _preparedPassword }, { "auth_policy", _targetPolicy } };
            if (AdminSecurityPolicy.UsesAuthenticator(_current.AuthPolicy))
            {
                if (currentCode.Length == 0) throw new InvalidOperationException("Введите код текущего аутентификатора или резервный код.");
                body["code"] = currentCode;
            }
            if (_requiresNewTotp)
            {
                if (newCode.Length != 6) throw new InvalidOperationException("Введите шестизначный код нового аутентификатора.");
                body["new_code"] = newCode;
            }
            if (AdminSecurityPolicy.UsesPaperKey(_current.AuthPolicy)) body["key_proof"] = await _auth.CreatePaperProofAsync(currentPaper, "authorize", _current.SigningPublicKey);
            if (_pendingPaper != null) body["new_key_proof"] = await _auth.CreatePaperProofAsync(_pendingPaper, "enroll", null);
            var data = await _auth.SendAsync(HttpMethod.Post, "admin/security", body, true, true);
            if (!AdminAuth.Bool(data, "ok") || !AdminAuth.Bool(data, "reauthenticate")) throw new InvalidOperationException("Сервер не подтвердил смену защиты. Обновите состояние перед повторной попыткой.");
            _changed = true;
            try { if (_useCredentialStore) { AdminCredentialStore.AdminSessionStore.Delete(); AdminCredentialStore.Delete(); } }
            catch (Exception ex) { ConsoleDialog.Show(this, "Защита изменена; старые административные сессии отозваны. Сохранённый вход на ПК удалить не удалось: " + ex.Message, "Хранилище Windows", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
            var codes = new List<string>(); object values;
            if (data.TryGetValue("recovery_codes", out values) && values is System.Collections.IEnumerable && !(values is string))
                foreach (var value in (System.Collections.IEnumerable)values) if (value != null) codes.Add(Convert.ToString(value));
            AdminSecurityUi.ShowRecoveryCodes(this, codes);
            _preparedPassword = null; _pendingPaper = null; _password.Clear(); _paper.Clear(); _code.Clear(); _newCode.Clear(); _newPaperDisplay.Clear(); _secret.Clear();
            DialogResult = DialogResult.OK; Close();
        }

        private void SetBusy(bool busy)
        {
            _busy = busy; _apply.Enabled = !busy; _back.Enabled = !busy; _cancel.Enabled = !busy;
            _policy.Enabled = !busy && !_prepared; _password.Enabled = !busy && !_prepared; _proofs.Enabled = !busy;
            Cursor = busy ? Cursors.WaitCursor : Cursors.Default;
        }
        protected override void Dispose(bool disposing) { if (disposing && _qr.Image != null) { _qr.Image.Dispose(); _qr.Image = null; } base.Dispose(disposing); }
    }
}
