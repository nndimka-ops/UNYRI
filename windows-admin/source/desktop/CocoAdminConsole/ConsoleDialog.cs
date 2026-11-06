using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal static class ConsoleDialog
    {
        public static DialogResult Show(string text, string caption, MessageBoxButtons buttons, MessageBoxIcon icon)
        {
            return Show(null, text, caption, buttons, icon);
        }

        public static DialogResult Show(string text, string caption)
        {
            return Show(null, text, caption, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public static DialogResult Show(string text)
        {
            return Show(null, text, "UNYRI Admin Console", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public static DialogResult Show(IWin32Window owner, string text)
        {
            return Show(owner, text, "UNYRI Admin Console", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public static DialogResult Show(IWin32Window owner, string text, string caption)
        {
            return Show(owner, text, caption, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public static DialogResult Show(IWin32Window owner, string text, string caption, MessageBoxButtons buttons)
        {
            return Show(owner, text, caption, buttons, MessageBoxIcon.Information);
        }

        public static DialogResult Show(
            IWin32Window owner,
            string text,
            string caption,
            MessageBoxButtons buttons,
            MessageBoxIcon icon)
        {
            using (var dialog = new DialogForm(caption, text, buttons, icon))
                return dialog.ShowDialog(owner);
        }

        private sealed class DialogForm : Form
        {
            private readonly MessageBoxButtons _buttons;
            private readonly MessageBoxIcon _icon;

            public DialogForm(string caption, string message, MessageBoxButtons buttons, MessageBoxIcon icon)
            {
                _buttons = buttons;
                _icon = icon;
                Text = string.IsNullOrWhiteSpace(caption) ? "UNYRI Admin Console" : caption;
                StartPosition = FormStartPosition.CenterParent;
                FormBorderStyle = FormBorderStyle.FixedDialog;
                ShowInTaskbar = false;
                MaximizeBox = false;
                MinimizeBox = false;
                BackColor = UiTheme.Background;
                ForeColor = UiTheme.Text;
                Font = new Font("Segoe UI", 9.5f);
                ClientSize = new Size(620, MessageHeight(message) + 150);

                var header = new Panel
                {
                    Dock = DockStyle.Top,
                    Height = 68,
                    Padding = new Padding(20, 0, 20, 0),
                    BackColor = UiTheme.Header
                };
                var accent = new Panel
                {
                    Dock = DockStyle.Left,
                    Width = 4,
                    BackColor = AccentColor(icon)
                };
                var title = new Label
                {
                    Text = Text,
                    Dock = DockStyle.Fill,
                    Padding = new Padding(14, 0, 0, 0),
                    TextAlign = ContentAlignment.MiddleLeft,
                    Font = new Font("Segoe UI Semibold", 11.5f),
                    ForeColor = UiTheme.Text,
                    AutoEllipsis = true
                };
                header.Controls.Add(title);
                header.Controls.Add(accent);

                var body = new Panel
                {
                    Dock = DockStyle.Fill,
                    Padding = new Padding(24, 22, 24, 18),
                    BackColor = UiTheme.Background
                };
                var symbol = new Label
                {
                    Text = IconText(icon),
                    Dock = DockStyle.Left,
                    Width = 48,
                    Font = new Font("Segoe UI Semibold", 22f),
                    ForeColor = AccentColor(icon),
                    TextAlign = ContentAlignment.TopLeft
                };
                var details = new RichTextBox
                {
                    Text = message ?? "",
                    Dock = DockStyle.Fill,
                    ReadOnly = true,
                    BorderStyle = BorderStyle.None,
                    BackColor = UiTheme.Background,
                    ForeColor = UiTheme.TextSecondary,
                    Font = new Font("Segoe UI", 9.5f),
                    WordWrap = true,
                    DetectUrls = true,
                    ScrollBars = RichTextBoxScrollBars.Vertical,
                    HideSelection = false
                };
                body.Controls.Add(details);
                body.Controls.Add(symbol);

                var footer = new FlowLayoutPanel
                {
                    Dock = DockStyle.Bottom,
                    Height = 58,
                    FlowDirection = FlowDirection.RightToLeft,
                    WrapContents = false,
                    Padding = new Padding(14, 9, 18, 9),
                    BackColor = UiTheme.Header
                };
                Button primary = null;
                Button cancel = null;
                foreach (var choice in ButtonChoices(buttons))
                {
                    var button = CreateButton(choice.Item1, choice.Item2, choice.Item2 != DialogResult.Cancel);
                    footer.Controls.Add(button);
                    if (choice.Item2 == DialogResult.Cancel || choice.Item2 == DialogResult.No)
                        cancel = button;
                    else if (primary == null)
                        primary = button;
                }
                Controls.Add(body);
                Controls.Add(footer);
                Controls.Add(header);
                // A bare Enter must not confirm a destructive action. For
                // Yes/No prompts the safe default is always No.
                AcceptButton = buttons == MessageBoxButtons.YesNo || buttons == MessageBoxButtons.YesNoCancel
                    ? cancel
                    : primary;
                CancelButton = cancel;
                UiTheme.ApplyDialog(this);
                Shown += (s, e) => WindowTheme.ApplyDarkTitleBar(this);
            }

            protected override void OnShown(EventArgs e)
            {
                base.OnShown(e);
                if (AcceptButton is Button) ((Button)AcceptButton).Select();
            }

            private Button CreateButton(string text, DialogResult result, bool primary)
            {
                var button = new Button
                {
                    Text = text,
                    DialogResult = result,
                    Width = primary ? 164 : 120,
                    Height = 40,
                    Margin = new Padding(6, 0, 0, 0),
                    FlatStyle = FlatStyle.Flat,
                    BackColor = primary ? UiTheme.Accent : UiTheme.SurfaceRaised,
                    ForeColor = UiTheme.Text,
                    UseVisualStyleBackColor = false,
                    Cursor = Cursors.Hand
                };
                button.FlatAppearance.BorderSize = 0;
                UiTheme.StyleButton(button, primary ? UiButtonKind.Primary : UiButtonKind.Secondary);
                return button;
            }

            private static IEnumerable<Tuple<string, DialogResult>> ButtonChoices(MessageBoxButtons buttons)
            {
                if (buttons == MessageBoxButtons.YesNo)
                    return new[] { Tuple.Create("Да, продолжить", DialogResult.Yes), Tuple.Create("Отмена", DialogResult.No) };
                if (buttons == MessageBoxButtons.YesNoCancel)
                    return new[] { Tuple.Create("Да, продолжить", DialogResult.Yes), Tuple.Create("Нет", DialogResult.No), Tuple.Create("Отмена", DialogResult.Cancel) };
                if (buttons == MessageBoxButtons.OKCancel)
                    return new[] { Tuple.Create("Продолжить", DialogResult.OK), Tuple.Create("Отмена", DialogResult.Cancel) };
                if (buttons == MessageBoxButtons.RetryCancel)
                    return new[] { Tuple.Create("Повторить", DialogResult.Retry), Tuple.Create("Отмена", DialogResult.Cancel) };
                if (buttons == MessageBoxButtons.AbortRetryIgnore)
                    return new[] { Tuple.Create("Прервать", DialogResult.Abort), Tuple.Create("Повторить", DialogResult.Retry), Tuple.Create("Игнорировать", DialogResult.Ignore) };
                return new[] { Tuple.Create("Понятно", DialogResult.OK) };
            }

            private static int MessageHeight(string message)
            {
                var text = message ?? "";
            var measured = TextRenderer.MeasureText(text.Length == 0 ? " " : text, UiTheme.Body,
                new Size(500, 10000), TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl).Height;
            return Math.Min(390, Math.Max(120, measured + 48));
            }

            private static string IconText(MessageBoxIcon icon)
            {
                if (icon == MessageBoxIcon.Error || icon == MessageBoxIcon.Stop || icon == MessageBoxIcon.Hand) return "×";
                if (icon == MessageBoxIcon.Warning || icon == MessageBoxIcon.Exclamation) return "!";
                if (icon == MessageBoxIcon.Question) return "?";
                return "i";
            }

            private static Color AccentColor(MessageBoxIcon icon)
            {
                if (icon == MessageBoxIcon.Error || icon == MessageBoxIcon.Stop || icon == MessageBoxIcon.Hand)
                    return UiTheme.Danger;
                if (icon == MessageBoxIcon.Warning || icon == MessageBoxIcon.Exclamation)
                    return UiTheme.Warning;
                if (icon == MessageBoxIcon.Information || icon == MessageBoxIcon.Asterisk)
                    return UiTheme.Info;
                return UiTheme.Accent;
            }
        }
    }
}
