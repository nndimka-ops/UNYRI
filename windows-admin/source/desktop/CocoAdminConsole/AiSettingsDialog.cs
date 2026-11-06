using System;
using System.Drawing;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal sealed class AiSettingsDialog : Form
    {
        private readonly TextBox _key = new TextBox();
        private readonly CheckBox _showKey = new CheckBox();
        private readonly ComboBox _model = new ComboBox();
        private readonly NumericUpDown _interval = new NumericUpDown();
        private readonly Label _status = new Label();
        private readonly CheckBox _scanAllLogs = new CheckBox();
        private readonly CheckBox _includeDatabase = new CheckBox();
        private readonly CheckBox _databaseDump = new CheckBox();
        private readonly CheckBox _sendRaw = new CheckBox();
        private readonly CheckBox _includeAudit = new CheckBox();
        private readonly CheckBox _includeDocker = new CheckBox();
        private readonly CheckBox _includePerformance = new CheckBox();

        public AiSettings Settings { get; private set; }

        public AiSettingsDialog(AiSettings current)
        {
            Settings = current ?? new AiSettings();
            Text = "Настройка DeepSeek";
            ClientSize = new Size(620, 720);
            AutoScroll = true;
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96f, 96f);
            UiTheme.ApplyDialog(this);

            var credentialsCard = new Panel
            {
                Location = new Point(16, 86),
                Size = new Size(588, 204),
                BackColor = UiTheme.Surface
            };
            UiTheme.StyleCard(credentialsCard, 14);
            var accessCard = new Panel
            {
                Location = new Point(16, 302),
                Size = new Size(588, 292),
                BackColor = UiTheme.Surface
            };
            UiTheme.StyleCard(accessCard, 14);
            Controls.Add(credentialsCard);
            Controls.Add(accessCard);

            Controls.Add(new Label
            {
                Text = "AI-наблюдение",
                Font = UiTheme.H1,
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Location = new Point(24, 16)
            });
            Controls.Add(new Label
            {
                Text = "Ключ хранится на этом ПК через DPAPI и не показывается в интерфейсе.",
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Location = new Point(25, 58)
            });

            AddLabel("API-КЛЮЧ DEEPSEEK", 104);
            _key.SetBounds(24, 128, 572, 34);
            _key.UseSystemPasswordChar = true;
            _key.Text = Settings.ApiKey ?? "";
            UiTheme.StyleInput(_key);
            Controls.Add(_key);

            _showKey.Text = "Показать ключ";
            _showKey.ForeColor = UiTheme.TextSecondary;
            _showKey.BackColor = UiTheme.Surface;
            _showKey.AutoSize = true;
            _showKey.Location = new Point(26, 168);
            UiTheme.StyleCheckBox(_showKey, UiTheme.Surface);
            _showKey.CheckedChanged += (s, e) => _key.UseSystemPasswordChar = !_showKey.Checked;
            Controls.Add(_showKey);

            AddLabel("МОДЕЛЬ", 204);
            _model.SetBounds(24, 228, 272, 32);
            _model.Items.AddRange(new object[] { "deepseek-chat", "deepseek-reasoner" });
            _model.SelectedItem = Settings.Model;
            if (_model.SelectedIndex < 0) _model.SelectedIndex = 0;
            UiTheme.StyleInput(_model);
            Controls.Add(_model);

            AddLabel("ИНТЕРВАЛ, МИНУТ", 204, 320);
            _interval.SetBounds(320, 228, 110, 32);
            _interval.Minimum = 1;
            _interval.Maximum = 1440;
            _interval.Value = Math.Max(1, Math.Min(1440, (Settings.IntervalSeconds > 0 ? Settings.IntervalSeconds : 300) / 60));
            UiTheme.StyleInput(_interval);
            Controls.Add(_interval);

            _status.ForeColor = UiTheme.TextMuted;
            _status.Font = UiTheme.BodySmall;
            _status.AutoSize = false;
            _status.SetBounds(24, 262, 572, 24);
            _status.Text = string.IsNullOrWhiteSpace(Settings.ApiKey)
                ? "Ключ пока не задан — работает локальная проверка."
                : "Ключ уже сохранён.";
            Controls.Add(_status);

            AddLabel("ДОСТУП К ДАННЫМ", 320);
            ConfigureAccessCheck(_scanAllLogs, "Все серверные логи целиком (все ротации)", 346,
                Settings.ScanAllLogs);
            ConfigureAccessCheck(_includeDatabase, "Все таблицы БД (read-only, локально)", 374,
                Settings.IncludeDatabase);
            ConfigureAccessCheck(_databaseDump, "Полный дамп БД в runtime-logs (может занять место)", 402,
                Settings.DatabaseDump);
            ConfigureAccessCheck(_sendRaw, "Отправлять образцы строк БД в DeepSeek (без колонок секретов)", 430,
                Settings.SendRawToApi);
            ConfigureAccessCheck(_includeAudit, "Последние записи admin_audit", 458, Settings.IncludeAudit);
            ConfigureAccessCheck(_includeDocker, "Docker-контейнеры и health", 486, Settings.IncludeDocker);
            ConfigureAccessCheck(_includePerformance, "Диагностика телефонов: метрики и события без переписки", 514, Settings.IncludePerformance);
            _databaseDump.Enabled = _includeDatabase.Checked;
            _sendRaw.Enabled = _includeDatabase.Checked;
            _includeDatabase.CheckedChanged += (s, e) =>
            {
                _databaseDump.Enabled = _includeDatabase.Checked;
                _sendRaw.Enabled = _includeDatabase.Checked;
            };
            var warning = new Label
            {
                Text = "Полные логи и дамп БД могут содержать чувствительные данные. Ключи, пароли, TOTP и приватные ключи вырезаются из отчёта DeepSeek.",
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.Warning,
                AutoSize = false,
                Location = new Point(24, 550),
                Size = new Size(572, 38)
            };
            Controls.Add(warning);

            var save = new Button { Text = "Сохранить", Width = 150, Height = 40 };
            UiTheme.StyleButton(save, UiButtonKind.Primary);
            save.SetBounds(272, 648, 148, 40);
            save.Click += (s, e) =>
            {
                Settings = new AiSettings
                {
                    ApiKey = _key.Text.Trim(),
                    Model = Convert.ToString(_model.SelectedItem) ?? "deepseek-chat",
                    BaseUrl = Settings.BaseUrl,
                    IntervalSeconds = (int)_interval.Value * 60,
                    ScanAllLogs = _scanAllLogs.Checked,
                    IncludeDatabase = _includeDatabase.Checked,
                    DatabaseDump = _databaseDump.Checked,
                    SendRawToApi = _sendRaw.Checked,
                    IncludeAudit = _includeAudit.Checked,
                    IncludeDocker = _includeDocker.Checked,
                    IncludePerformance = _includePerformance.Checked
                };
                DialogResult = DialogResult.OK;
                Close();
            };
            Controls.Add(save);

            var remove = new Button { Text = "Удалить ключ", Width = 140, Height = 40 };
            UiTheme.StyleButton(remove, UiButtonKind.Danger);
            remove.SetBounds(432, 648, 164, 40);
            remove.Click += (s, e) =>
            {
                AiSettingsStore.Delete();
                Settings = new AiSettings();
                DialogResult = DialogResult.OK;
                Close();
            };
            Controls.Add(remove);

            var cancel = new Button { Text = "Отмена", Width = 100, Height = 40, DialogResult = DialogResult.Cancel };
            UiTheme.StyleButton(cancel, UiButtonKind.Ghost);
            cancel.SetBounds(160, 648, 100, 40);
            Controls.Add(cancel);
            AcceptButton = save;
            CancelButton = cancel;
            credentialsCard.SendToBack();
            accessCard.SendToBack();
        }

        private void ConfigureAccessCheck(CheckBox box, string text, int top, bool value)
        {
            box.Text = text;
            box.ForeColor = UiTheme.TextSecondary;
            box.BackColor = UiTheme.Surface;
            box.AutoSize = false;
            box.SetBounds(24, top, 572, 24);
            box.Checked = value;
            UiTheme.StyleCheckBox(box, UiTheme.Surface);
            Controls.Add(box);
        }

        private void AddLabel(string text, int top, int left = 24)
        {
            Controls.Add(new Label
            {
                Text = text,
                Font = UiTheme.Semibold,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Location = new Point(left, top)
            });
        }
    }
}
