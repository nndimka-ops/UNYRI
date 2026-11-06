using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace CocoAdmin
{
    internal static class IconFactory
    {
        private const string GlyphFont = "Segoe MDL2 Assets";

        public const string Admin = "\uE7EF";
        public const string Overview = "\uE80F";
        public const string Users = "\uE716";
        public const string Reports = "\uE7BA";
        public const string Feedback = "\uE8BD";
        public const string Dependencies = "\uE90F";
        public const string Logs = "\uE7C3";
        public const string Refresh = "\uE72C";
        public const string Start = "\uE768";
        public const string Check = "\uE73E";
        public const string Login = "\uE77B";
        public const string Open = "\uE8A7";
        public const string Folder = "\uE8B7";
        public const string Install = "\uE896";
        public const string Settings = "\uE713";

        public static Image Glyph(string glyph, int size, Color color)
        {
            var bitmap = new Bitmap(size, size);
            using (var graphics = Graphics.FromImage(bitmap))
            {
                graphics.SmoothingMode = SmoothingMode.AntiAlias;
                graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
                graphics.Clear(Color.Transparent);
                using (var font = new Font(GlyphFont, size * 0.62f, FontStyle.Regular, GraphicsUnit.Pixel))
                using (var brush = new SolidBrush(color))
                using (var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
                {
                    graphics.DrawString(glyph, font, brush, new RectangleF(0, 0, size, size), format);
                }
            }
            return bitmap;
        }

        public static ImageList TabImages()
        {
            var list = new ImageList { ImageSize = new Size(22, 22), ColorDepth = ColorDepth.Depth32Bit, TransparentColor = Color.Transparent };
            list.Images.Add("admin", Glyph(Admin, 22, Color.FromArgb(185, 170, 255)));
            list.Images.Add("overview", Glyph(Overview, 22, Color.FromArgb(120, 230, 165)));
            list.Images.Add("users", Glyph(Users, 22, Color.FromArgb(120, 200, 245)));
            list.Images.Add("reports", Glyph(Reports, 22, Color.FromArgb(245, 150, 160)));
            list.Images.Add("feedback", Glyph(Feedback, 22, Color.FromArgb(240, 205, 120)));
            list.Images.Add("deps", Glyph(Dependencies, 22, Color.FromArgb(190, 170, 250)));
            list.Images.Add("logs", Glyph(Logs, 22, Color.FromArgb(160, 180, 205)));
            return list;
        }

        public static string GlyphForText(string text)
        {
            if (text == null) return null;
            var value = text.ToLowerInvariant();
            if (value.Contains("админ") || value.Contains("консол")) return Admin;
            if (value.Contains("обзор")) return Overview;
            if (value.Contains("пользоват")) return Users;
            if (value.Contains("жалоб")) return Reports;
            if (value.Contains("обращ")) return Feedback;
            if (value.Contains("зависим")) return Dependencies;
            if (value.Contains("лог")) return Logs;
            if (value.Contains("обнов")) return Refresh;
            if (value.Contains("запуск") || value.Contains("перезапуск")) return Start;
            if (value.Contains("провер")) return Check;
            if (value.Contains("войти")) return Login;
            if (value.Contains("открыть")) return Open;
            if (value.Contains("папк")) return Folder;
            if (value.Contains("установ")) return Install;
            return null;
        }

        public static Image BrandImage(int size)
        {
            using (var stream = new System.IO.MemoryStream(Convert.FromBase64String(BrandIconData.PngBase64)))
            using (var source = Image.FromStream(stream))
                return new Bitmap(source, new Size(size, size));
        }

        public static Icon AppIcon()
        {
            using (var bitmap = (Bitmap)BrandImage(48))
            {
                var handle = bitmap.GetHicon();
                try
                {
                    using (var icon = Icon.FromHandle(handle))
                        return (Icon)icon.Clone();
                }
                finally { DestroyIcon(handle); }
            }
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool DestroyIcon(IntPtr hIcon);

        private static GraphicsPath RoundedRect(Rectangle bounds, int radius)
        {
            var path = new GraphicsPath();
            var diameter = radius * 2;
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
            path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }
    }
}
