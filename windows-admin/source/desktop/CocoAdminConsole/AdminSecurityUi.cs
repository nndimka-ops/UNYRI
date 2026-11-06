using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal static class AdminSecurityUi
    {
        public static string ExplainError(Exception error, bool confirming)
        {
            var api = error as ApiException;
            if (api != null)
            {
                if (api.Code == "invalid_credentials") return confirming
                    ? "Подтверждение не принято. Проверьте нужный ключ и код. Если этот код уже использовали при входе, дождитесь следующего или введите неиспользованный запасной код."
                    : "Логин или пароль не подошёл. Проверьте данные аккаунта и выбранный сервер.";
                if (api.Status == 429) return "Слишком много попыток. Подождите несколько минут и повторите. Защита входа не изменена.";
                if (api.Status == 401) return "Сессия завершилась. Вернитесь к первому шагу и войдите заново.";
                if (api.Status == 0) return "Нет соединения с выбранным сервером. Проверьте сеть и повторите — настройки защиты не изменены.";
            }
            return error == null || string.IsNullOrWhiteSpace(error.Message)
                ? "Действие не выполнено. Проверьте соединение и повторите." : error.Message;
        }

        public static TableLayoutPanel Stack(int padding)
        {
            var stack = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
                ColumnCount = 1, Padding = new Padding(padding), BackColor = UiTheme.Background, Margin = new Padding(0) };
            stack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            return stack;
        }

        public static void Add(TableLayoutPanel stack, Control control)
        {
            var row = stack.RowCount++;
            stack.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            control.Dock = DockStyle.Top;
            if (control.Margin == new Padding(0)) control.Margin = new Padding(0, 0, 0, 12);
            stack.Controls.Add(control, 0, row);
        }

        public static Label Text(string text, Font font, Color color)
        {
            var label = new Label { Text = text, Font = font, ForeColor = color, AutoSize = true,
                BackColor = Color.Transparent, Margin = new Padding(0, 0, 0, 12), MaximumSize = new Size(560, 0) };
            return label;
        }

        public static void FitScrollingBody(Panel viewport, Control body)
        {
            // The viewport determines wrapping. Following the size of an
            // auto-sized label's parent creates a shrink/layout feedback loop.
            Action fit = () => FitText(body, Math.Max(240, viewport.ClientSize.Width - SystemInformation.VerticalScrollBarWidth - 4));
            viewport.ClientSizeChanged += (s, e) => fit();
            fit();
        }

        private static void FitText(Control control, int available)
        {
            var label = control as Label;
            if (label != null && label.AutoSize) label.MaximumSize = new Size(Math.Max(120, available - label.Margin.Horizontal), 0);
            var next = Math.Max(120, available - control.Padding.Horizontal - control.Margin.Horizontal);
            foreach (Control child in control.Controls) FitText(child, next);
        }

        public static Control Field(string title, Control input)
        {
            var block = Stack(0);
            block.BackColor = UiTheme.Background;
            Add(block, Text(title, UiTheme.Semibold, UiTheme.TextSecondary));
            input.Margin = new Padding(0);
            input.MinimumSize = new Size(0, 28);
            UiTheme.StyleInput(input);
            Add(block, input);
            return block;
        }

        public static TextBox SecretField()
        {
            var field = new TextBox { UseSystemPasswordChar = true, MaxLength = 512 };
            UiTheme.StyleInput(field);
            return field;
        }

        public static Button Button(string text, UiButtonKind kind)
        {
            var button = new Button { Text = text, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
                MinimumSize = new Size(110, 42), Padding = new Padding(12, 6, 12, 6), Margin = new Padding(6, 6, 0, 6) };
            UiTheme.StyleButton(button, kind);
            return button;
        }

        public static FlowLayoutPanel Footer()
        {
            return new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
                FlowDirection = FlowDirection.RightToLeft, WrapContents = true, Padding = new Padding(16, 6, 16, 6), BackColor = UiTheme.Surface };
        }

        public static Image DecodeQr(string base64)
        {
            if (string.IsNullOrWhiteSpace(base64)) return null;
            if (base64.StartsWith("data:", StringComparison.Ordinal))
            {
                var comma = base64.IndexOf(',');
                if (comma >= 0) base64 = base64.Substring(comma + 1);
            }
            using (var stream = new MemoryStream(Convert.FromBase64String(base64)))
            using (var source = Image.FromStream(stream)) return new Bitmap(source);
        }

        public static string FormatPaper(string code)
        {
            code = CryptoAdmin.NormalizePaperCode(code);
            var groups = new List<string>();
            for (var start = 0; start < code.Length; start += 8) groups.Add(code.Substring(start, Math.Min(8, code.Length - start)));
            return string.Join("-", groups);
        }

        public static void ShowRecoveryCodes(IWin32Window owner, IList<string> codes)
        {
            if (codes == null || codes.Count == 0) return;
            using (var dialog = new Form())
            {
                UiTheme.ApplyDialog(dialog);
                dialog.Text = "Сохраните резервные коды";
                dialog.ClientSize = new Size(580, 510);
                var layout = Stack(22);
                Add(layout, Text("Если аутентификатор недоступен", UiTheme.H2, UiTheme.Text));
                Add(layout, Text("Каждый код заменяет одно подтверждение аутентификатором и работает один раз. Если выбран бумажный ключ вместе с аутентификатором, бумажный ключ всё равно понадобится. Коды больше не будут показаны.", UiTheme.Body, UiTheme.TextSecondary));
                var list = new TextBox { Text = string.Join(Environment.NewLine, codes), Multiline = true, ReadOnly = true,
                    ScrollBars = ScrollBars.Vertical, Height = 200, Font = UiTheme.Mono };
                UiTheme.StyleInput(list);
                Add(layout, list);
                var save = Button("Сохранить в файл…", UiButtonKind.Secondary);
                save.Click += (s, e) =>
                {
                    using (var picker = new SaveFileDialog { Title = "Сохраните коды в безопасном месте", Filter = "Текст (*.txt)|*.txt", FileName = "UNYRI-admin-recovery-codes.txt", AddExtension = true, OverwritePrompt = true })
                    {
                        if (picker.ShowDialog(dialog) != DialogResult.OK) return;
                        try { File.WriteAllText(picker.FileName, "Резервные коды администратора UNYRI. Храните отдельно от пароля.\r\n\r\n" + list.Text, new System.Text.UTF8Encoding(true)); }
                        catch (Exception ex) { ConsoleDialog.Show(dialog, "Не удалось сохранить коды: " + ex.Message, "Резервные коды", MessageBoxButtons.OK, MessageBoxIcon.Error); }
                    }
                };
                Add(layout, save);
                var close = Button("Коды сохранены", UiButtonKind.Primary);
                close.Click += (s, e) => dialog.Close();
                var footer = Footer(); footer.Controls.Add(close);
                var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true }; scroll.Controls.Add(layout);
                FitScrollingBody(scroll, layout);
                var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2 };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                root.Controls.Add(scroll, 0, 0); root.Controls.Add(footer, 0, 1);
                dialog.Controls.Add(root); dialog.AcceptButton = close; dialog.CancelButton = close;
                dialog.ShowDialog(owner);
            }
        }
    }
}
