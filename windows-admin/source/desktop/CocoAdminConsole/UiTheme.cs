using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal enum UiButtonKind
    {
        Primary,
        Secondary,
        Ghost,
        Danger
    }

    /// <summary>
    /// Small shared design system for the native console. Keeps the palette,
    /// spacing and control painting consistent without introducing a UI
    /// framework dependency.
    /// </summary>
    internal static class UiTheme
    {
        public static readonly Color Background = Color.FromArgb(10, 11, 16);
        public static readonly Color Header = Color.FromArgb(12, 13, 20);
        public static readonly Color Surface = Color.FromArgb(18, 20, 29);
        public static readonly Color SurfaceRaised = Color.FromArgb(25, 28, 40);
        public static readonly Color SurfaceHover = Color.FromArgb(35, 39, 56);
        public static readonly Color Border = Color.FromArgb(42, 47, 69);
        public static readonly Color BorderSubtle = Color.FromArgb(30, 34, 49);
        public static readonly Color TableHeader = Color.FromArgb(21, 24, 38);
        public static readonly Color Text = Color.FromArgb(236, 239, 250);
        public static readonly Color TextSecondary = Color.FromArgb(154, 164, 189);
        public static readonly Color TextMuted = Color.FromArgb(124, 135, 163);
        public static readonly Color OnAccent = Color.White;
        public static readonly Color Accent = Color.FromArgb(108, 92, 255);
        public static readonly Color AccentHover = Color.FromArgb(126, 111, 255);
        public static readonly Color AccentPressed = Color.FromArgb(95, 77, 232);
        public static readonly Color AccentSoft = Color.FromArgb(38, 32, 82);
        public static readonly Color Info = Color.FromArgb(109, 141, 255);
        public static readonly Color Success = Color.FromArgb(56, 217, 150);
        public static readonly Color Warning = Color.FromArgb(242, 184, 75);
        public static readonly Color Danger = Color.FromArgb(255, 77, 95);
        public static readonly Color DangerPressed = Color.FromArgb(214, 55, 73);
        public static readonly Color SuccessSoft = Color.FromArgb(18, 50, 39);
        public static readonly Color WarningSoft = Color.FromArgb(58, 44, 18);
        public static readonly Color DangerSoft = Color.FromArgb(58, 23, 32);

        public static readonly Font Body = new Font("Segoe UI", 9.5f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font BodySmall = new Font("Segoe UI", 8.5f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font Semibold = new Font("Segoe UI Semibold", 9.5f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font H1 = new Font("Segoe UI Semibold", 22f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font H2 = new Font("Segoe UI Semibold", 16f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font H3 = new Font("Segoe UI Semibold", 12f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font Kpi = new Font("Segoe UI Semibold", 20f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font Micro = new Font("Segoe UI Semibold", 8f, FontStyle.Regular, GraphicsUnit.Point);
        public static readonly Font Title = H2;
        public static readonly Font Display = H1;
        public static readonly Font Mono = new Font("Consolas", 9.5f, FontStyle.Regular, GraphicsUnit.Point);

        public static void ApplyForm(Form form)
        {
            if (form == null) return;
            form.BackColor = Background;
            form.ForeColor = Text;
            form.Font = Body;
            try { WindowTheme.ApplyDarkTitleBar(form); } catch { }
        }

        public static void ApplyDialog(Form form)
        {
            ApplyForm(form);
            form.FormBorderStyle = FormBorderStyle.FixedDialog;
            form.ShowInTaskbar = false;
            form.StartPosition = FormStartPosition.CenterParent;
            form.MaximizeBox = false;
            form.MinimizeBox = false;
            form.AutoScaleMode = AutoScaleMode.Dpi;
            form.AutoScaleDimensions = new SizeF(96f, 96f);
            // The handle may not exist when ApplyForm is called. Re-apply the
            // DWM title-bar attribute after the native window is created.
            form.Shown += (s, e) =>
            {
                try { WindowTheme.ApplyDarkTitleBar(form); } catch { }
            };
        }

        public static void StylePanel(Control control)
        {
            if (control == null) return;
            control.BackColor = Surface;
            control.ForeColor = Text;
            control.Font = Body;
        }

        public static void StyleToolbar(FlowLayoutPanel toolbar)
        {
            if (toolbar == null) return;
            toolbar.BackColor = Surface;
            toolbar.Padding = new Padding(12, 9, 12, 9);
        }

        public static void StyleInput(Control control)
        {
            if (control == null) return;
            control.BackColor = SurfaceRaised;
            control.ForeColor = Text;
            control.Font = Body;
            ApplyDarkNativeTheme(control);
            var textBox = control as TextBoxBase;
            if (textBox != null)
            {
                textBox.BorderStyle = BorderStyle.FixedSingle;
                return;
            }
            var combo = control as ComboBox;
            if (combo != null)
            {
                combo.FlatStyle = FlatStyle.Flat;
                combo.DropDownStyle = ComboBoxStyle.DropDownList;
                combo.DrawMode = DrawMode.OwnerDrawFixed;
                combo.ItemHeight = Math.Max(combo.ItemHeight, 24);
                combo.DrawItem -= DrawComboItem;
                combo.DrawItem += DrawComboItem;
            }
        }

        private static void DrawComboItem(object sender, DrawItemEventArgs e)
        {
            var combo = sender as ComboBox;
            if (combo == null) return;
            var selected = (e.State & DrawItemState.Selected) == DrawItemState.Selected;
            var background = selected ? AccentSoft : SurfaceRaised;
            var foreground = selected ? Text : TextSecondary;
            using (var brush = new SolidBrush(background)) e.Graphics.FillRectangle(brush, e.Bounds);
            if (e.Index < 0 || e.Index >= combo.Items.Count) return;
            var text = Convert.ToString(combo.Items[e.Index]) ?? "";
            TextRenderer.DrawText(e.Graphics, text, combo.Font,
                new Rectangle(e.Bounds.X + 8, e.Bounds.Y, Math.Max(0, e.Bounds.Width - 12), e.Bounds.Height),
                foreground, TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        }

        public static void StyleButton(Button button, UiButtonKind kind)
        {
            if (button == null) return;
            button.FlatStyle = FlatStyle.Flat;
            button.UseVisualStyleBackColor = false;
            button.FlatAppearance.BorderSize = kind == UiButtonKind.Ghost ? 0 : 1;
            button.Cursor = Cursors.Hand;
            button.Font = Semibold;
            button.ForeColor = kind == UiButtonKind.Ghost ? TextSecondary : Text;
            button.Padding = new Padding(14, 0, 14, 0);
            button.TextImageRelation = TextImageRelation.ImageBeforeText;
            button.ImageAlign = ContentAlignment.MiddleLeft;
            switch (kind)
            {
                case UiButtonKind.Primary:
                    button.BackColor = Accent;
                    button.ForeColor = OnAccent;
                    button.FlatAppearance.BorderColor = Accent;
                    button.FlatAppearance.MouseOverBackColor = AccentHover;
                    button.FlatAppearance.MouseDownBackColor = AccentPressed;
                    break;
                case UiButtonKind.Danger:
                    button.BackColor = Danger;
                    button.ForeColor = OnAccent;
                    button.FlatAppearance.BorderColor = Danger;
                    button.FlatAppearance.MouseOverBackColor = Color.FromArgb(248, 132, 146);
                    button.FlatAppearance.MouseDownBackColor = DangerPressed;
                    break;
                case UiButtonKind.Ghost:
                    button.BackColor = Color.Transparent;
                    button.FlatAppearance.MouseOverBackColor = SurfaceHover;
                    button.FlatAppearance.MouseDownBackColor = SurfaceRaised;
                    button.FlatAppearance.BorderColor = Background;
                    break;
                default:
                    button.BackColor = SurfaceRaised;
                    button.FlatAppearance.BorderColor = Border;
                    button.FlatAppearance.MouseOverBackColor = SurfaceHover;
                    button.FlatAppearance.MouseDownBackColor = AccentSoft;
                    break;
            }
            Round(button, 10);
        }

        public static void StyleGrid(DataGridView grid)
        {
            if (grid == null) return;
            ApplyDarkNativeTheme(grid);
            grid.BackgroundColor = Surface;
            grid.BorderStyle = BorderStyle.None;
            grid.CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal;
            grid.GridColor = BorderSubtle;
            grid.EnableHeadersVisualStyles = false;
            grid.ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.None;
            grid.ColumnHeadersHeight = 44;
            grid.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
            grid.RowTemplate.Height = 44;
            grid.RowHeadersVisible = false;
            grid.AllowUserToAddRows = false;
            grid.AllowUserToDeleteRows = false;
            grid.AllowUserToResizeRows = false;
            grid.ReadOnly = true;
            grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
            grid.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
            grid.Font = Body;
            grid.DefaultCellStyle.BackColor = Surface;
            grid.DefaultCellStyle.ForeColor = Text;
            grid.DefaultCellStyle.SelectionBackColor = AccentSoft;
            grid.DefaultCellStyle.SelectionForeColor = Text;
            grid.DefaultCellStyle.Padding = new Padding(14, 0, 10, 0);
            grid.AlternatingRowsDefaultCellStyle.BackColor = Surface;
            grid.AlternatingRowsDefaultCellStyle.ForeColor = Text;
            grid.ColumnHeadersDefaultCellStyle.BackColor = TableHeader;
            grid.ColumnHeadersDefaultCellStyle.ForeColor = TextSecondary;
            grid.ColumnHeadersDefaultCellStyle.Font = Semibold;
            grid.ColumnHeadersDefaultCellStyle.Padding = new Padding(14, 0, 10, 0);
            grid.ColumnHeadersDefaultCellStyle.SelectionBackColor = TableHeader;
        }

        public static void StyleCard(Control control, int radius)
        {
            if (control == null) return;
            control.BackColor = Surface;
            control.AccessibleDescription = "card:" + radius;
            control.Paint -= PaintCard;
            control.Paint += PaintCard;
            Round(control, radius);
        }

        private static void PaintCard(object sender, PaintEventArgs e)
        {
            var control = sender as Control;
            if (control == null || control.Width < 4 || control.Height < 4) return;
            var radius = 14;
            if (!string.IsNullOrEmpty(control.AccessibleDescription))
            {
                int parsed;
                if (int.TryParse(control.AccessibleDescription.Replace("card:", ""), out parsed) && parsed > 0)
                    radius = parsed;
            }
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (var path = RoundedPath(new Rectangle(0, 0, control.Width - 1, control.Height - 1), radius))
            using (var fill = new SolidBrush(control.BackColor))
            using (var pen = new Pen(Border, 1f))
            {
                e.Graphics.FillPath(fill, path);
                e.Graphics.DrawPath(pen, path);
            }
        }

        public static void Round(Control control, int radius)
        {
            if (control == null) return;
            EventHandler apply = (s, e) => ApplyRegion(control, radius);
            control.HandleCreated += apply;
            control.SizeChanged += apply;
            if (control.IsHandleCreated) ApplyRegion(control, radius);
        }

        private static void ApplyRegion(Control control, int radius)
        {
            if (control == null || control.Width < 2 || control.Height < 2) return;
            try
            {
                using (var path = RoundedPath(new Rectangle(0, 0, control.Width - 1, control.Height - 1), radius))
                    control.Region = new Region(path);
            }
            catch { }
        }

        public static GraphicsPath RoundedPath(Rectangle bounds, int radius)
        {
            var path = new GraphicsPath();
            if (bounds.Width <= 0 || bounds.Height <= 0) return path;
            var diameter = Math.Min(Math.Min(radius * 2, bounds.Width), bounds.Height);
            var arc = new Rectangle(bounds.Left, bounds.Top, diameter, diameter);
            path.AddArc(arc, 180, 90);
            arc.X = bounds.Right - diameter;
            path.AddArc(arc, 270, 90);
            arc.Y = bounds.Bottom - diameter;
            path.AddArc(arc, 0, 90);
            arc.X = bounds.Left;
            path.AddArc(arc, 90, 90);
            path.CloseFigure();
            return path;
        }

        public static Label SectionLabel(string text)
        {
            return new Label
            {
                Text = text,
                AutoSize = false,
                Height = 26,
                Font = Semibold,
                ForeColor = TextSecondary,
                TextAlign = ContentAlignment.MiddleLeft
            };
        }

        public static Label MutedLabel(string text)
        {
            return new Label
            {
                Text = text,
                AutoSize = false,
                ForeColor = TextMuted,
                Font = BodySmall,
                TextAlign = ContentAlignment.MiddleLeft
            };
        }

        public static Label Badge(string text, Color foreground, Color background)
        {
            var label = new Label
            {
                Text = text,
                AutoSize = true,
                Font = Semibold,
                ForeColor = foreground,
                BackColor = background,
                Padding = new Padding(9, 3, 9, 3),
                TextAlign = ContentAlignment.MiddleCenter
            };
            Round(label, 11);
            return label;
        }

        public static void StyleCheckBox(CheckBox box, Color background)
        {
            if (box == null) return;
            box.Font = Body;
            box.ForeColor = TextSecondary;
            box.BackColor = background;
            box.FlatStyle = FlatStyle.Flat;
            box.FlatAppearance.BorderColor = Border;
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, string lParam);

        [DllImport("uxtheme.dll", CharSet = CharSet.Unicode)]
        private static extern int SetWindowTheme(IntPtr hWnd, string pszSubAppName, string pszSubIdList);

        public static void ApplyDarkNativeTheme(Control control)
        {
            if (control == null) return;
            EventHandler apply = (s, e) =>
            {
                try { SetWindowTheme(control.Handle, "DarkMode_Explorer", null); } catch { }
            };
            control.HandleCreated += apply;
            if (control.IsHandleCreated) apply(control, EventArgs.Empty);
        }

        public static void SetPlaceholder(TextBox box, string text)
        {
            if (box == null) return;
            if (box.IsHandleCreated)
            {
                SendMessage(box.Handle, 0x1501, (IntPtr)1, text);
                return;
            }
            box.HandleCreated += (s, e) => SendMessage(box.Handle, 0x1501, (IntPtr)1, text);
        }
    }
}
