using System;
using System.Drawing;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal sealed class OperationResultDialog : Form
    {
        private readonly Panel _body = new Panel();
        private readonly Label _summary = new Label();
        private readonly RichTextBox _details = new RichTextBox();
        private readonly Button _detailsButton = new Button();
        private bool _expanded;

        private OperationResultDialog(string title, string summary, string details, bool success)
        {
            Text = title;
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            BackColor = UiTheme.Background;
            ForeColor = UiTheme.Text;
            Font = new Font("Segoe UI", 9f);
            ClientSize = new Size(720, 270);
            MinimumSize = new Size(620, 270);

            var header = new Panel
            {
                Dock = DockStyle.Top,
                Height = 68,
                BackColor = UiTheme.Header,
                Padding = new Padding(20, 0, 16, 0)
            };
            var accent = new Panel
            {
                Dock = DockStyle.Left,
                Width = 4,
                BackColor = success ? UiTheme.Success : UiTheme.Danger
            };
            var heading = new Label
            {
                Text = success ? "Операция завершена" : "Не удалось выполнить операцию",
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleLeft,
                Font = new Font("Segoe UI Semibold", 12f),
                ForeColor = UiTheme.Text,
                Padding = new Padding(14, 0, 0, 0)
            };
            header.Controls.Add(heading);
            header.Controls.Add(accent);

            _body.Dock = DockStyle.Fill;
            _body.Padding = new Padding(22, 18, 22, 12);
            _summary.Dock = DockStyle.Fill;
            _summary.Text = string.IsNullOrWhiteSpace(summary)
                ? (success ? "Операция завершена." : "Сервер не вернул описание ошибки. Откройте подробности и проверьте версию backend.")
                : summary.Trim();
            _summary.ForeColor = UiTheme.TextSecondary;
            _summary.Font = new Font("Segoe UI", 10f);
            _summary.TextAlign = ContentAlignment.MiddleLeft;
            _summary.AutoEllipsis = true;
            _details.Dock = DockStyle.Fill;
            _details.Visible = false;
            _details.ReadOnly = true;
            _details.BorderStyle = BorderStyle.None;
            _details.BackColor = Color.FromArgb(7, 8, 13);
            _details.ForeColor = UiTheme.TextSecondary;
            _details.Font = new Font("Consolas", 8.5f);
            _details.WordWrap = true;
            _details.Text = LimitDetails(details);
            _body.Controls.Add(_details);
            _body.Controls.Add(_summary);

            var footer = new FlowLayoutPanel
            {
                Dock = DockStyle.Bottom,
                Height = 54,
                FlowDirection = FlowDirection.RightToLeft,
                WrapContents = false,
                Padding = new Padding(12, 8, 16, 8),
                BackColor = UiTheme.Header
            };
            var close = new Button
            {
                Text = "Закрыть",
                Width = 116,
                Height = 38,
                FlatStyle = FlatStyle.Flat,
                BackColor = UiTheme.Accent,
                ForeColor = UiTheme.Text,
                Cursor = Cursors.Hand,
                UseVisualStyleBackColor = false
            };
            close.FlatAppearance.BorderSize = 0;
            UiTheme.StyleButton(close, UiButtonKind.Primary);
            close.Click += (s, e) => Close();
            _detailsButton.Text = "Подробности";
            _detailsButton.AutoSize = true;
            _detailsButton.Height = 38;
            _detailsButton.FlatStyle = FlatStyle.Flat;
            _detailsButton.BackColor = UiTheme.SurfaceRaised;
            _detailsButton.ForeColor = UiTheme.TextSecondary;
            _detailsButton.UseVisualStyleBackColor = false;
            _detailsButton.FlatAppearance.BorderSize = 0;
            UiTheme.StyleButton(_detailsButton, UiButtonKind.Secondary);
            _detailsButton.Enabled = !success || !string.IsNullOrWhiteSpace(details);
            _detailsButton.Click += (s, e) => SetExpanded(!_expanded);
            footer.Controls.Add(close);
            footer.Controls.Add(_detailsButton);
            Controls.Add(_body);
            Controls.Add(footer);
            Controls.Add(header);
            AcceptButton = close;
            UiTheme.ApplyDialog(this);
        }

        public static void Show(IWin32Window owner, string title, string summary, string details, bool success)
        {
            using (var dialog = new OperationResultDialog(title, summary, details, success))
                dialog.ShowDialog(owner);
        }

        public static string DescribeFailure(CommandResult result, string operation)
        {
            var details = result == null ? "" : result.Combined;
            const string portMarker = "Port 8080 is occupied by an unverified process";
            if (details.IndexOf(portMarker, StringComparison.OrdinalIgnoreCase) >= 0)
            {
                var process = ExtractProcessId(details);
                return "Не удалось запустить сервер: порт 8080 занят процессом " + process + ". " +
                    "Проверка не подтвердила готовность UNYRI. Приложение не остановило неизвестный процесс. " +
                    "Для диагностики откройте «Зависимости» и «Логи». Для перезапуска запустите консоль с теми же правами Windows, что и сервер.";
            }
            if (result != null && result.TimedOut)
                return operation + " не завершилась за отведённое время. Сначала проверьте состояние сервера в разделах «Обзор» и «Логи», прежде чем повторять команду.";
            return operation + " не выполнена. Откройте технические подробности, чтобы посмотреть причину.";
        }

        private static string ExtractProcessId(string details)
        {
            var marker = "PID ";
            var start = details.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
            if (start < 0) return "с неизвестным PID";
            start += marker.Length;
            var end = start;
            while (end < details.Length && char.IsDigit(details[end])) end++;
            return end > start ? "PID " + details.Substring(start, end - start) : "с неизвестным PID";
        }

        private static string LimitDetails(string details)
        {
            if (string.IsNullOrWhiteSpace(details)) return "Команда не вернула технических подробностей.";
            const int maximumLength = 12000;
            return details.Length > maximumLength
                ? "... начало вывода скрыто; показан последний фрагмент ...\r\n" + details.Substring(details.Length - maximumLength)
                : details;
        }

        private void SetExpanded(bool expanded)
        {
            _expanded = expanded;
            _details.Visible = expanded;
            _summary.Visible = !expanded;
            _detailsButton.Text = expanded ? "Скрыть подробности" : "Подробности";
            ClientSize = new Size(720, expanded ? 500 : 270);
        }
    }
}
