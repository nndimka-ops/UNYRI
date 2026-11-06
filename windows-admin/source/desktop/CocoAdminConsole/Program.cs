using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Drawing;
using System.Windows.Forms;
using MessageBox = CocoAdmin.ConsoleDialog;

namespace CocoAdmin
{
    internal static class Program
    {
        private static Mutex _singleInstance;

        [STAThread]
        private static void Main()
        {
            bool created;
            _singleInstance = new Mutex(true, "CocoAdminConsole.SingleInstance", out created);
            if (!created)
            {
                MessageBox.Show("UNYRI Admin Console уже запущен.", "UNYRI Admin",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            var auth = RestoreSessionWithProgress();
            var monitoringOnly = false;
            if (auth == null)
            {
                using (var login = new LoginForm())
                {
                    login.Shown += (s, e) => WindowTheme.ApplyDarkTitleBar(login);
                    if (login.ShowDialog() != DialogResult.OK) return;
                    auth = login.Auth;
                    monitoringOnly = login.MonitoringOnly;
                }
            }
            Application.Run(new MainForm(auth, monitoringOnly));
        }

        private static AdminAuth RestoreSessionWithProgress()
        {
            AdminAuth result = null;
            using (var progress = new SessionRestoreForm())
            {
                progress.Shown += async (s, e) =>
                {
                    try { result = await RestoreSessionAsync(); }
                    finally
                    {
                        if (!progress.IsDisposed) progress.Close();
                    }
                };
                progress.ShowDialog();
            }
            return result;
        }

        private static async System.Threading.Tasks.Task<AdminAuth> RestoreSessionAsync()
        {
            PersistedAdminSession saved;
            try { saved = AdminCredentialStore.AdminSessionStore.Load(); }
            catch (Exception ex)
            {
                MessageBox.Show("Не удалось прочитать сохранённую сессию Windows:\n" + ex.Message,
                    "Сессия", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                AdminCredentialStore.AdminSessionStore.Delete();
                return null;
            }
            if (saved == null) return null;

            AdminAuth auth;
            try { auth = AdminAuth.FromPersistedSession(saved); }
            catch
            {
                AdminCredentialStore.AdminSessionStore.Delete();
                return null;
            }
            if (auth == null)
            {
                AdminCredentialStore.AdminSessionStore.Delete();
                return null;
            }
            if (auth.IsAccessTokenExpired)
            {
                AdminCredentialStore.AdminSessionStore.Delete();
                return null;
            }
            try
            {
                // Do not hold the application without a visible window for the
                // normal 20-second API timeout when a remembered server is down.
                var validation = auth.SendAsync(System.Net.Http.HttpMethod.Get, "admin/summary", null, true, true);
                var completed = await Task.WhenAny(validation, Task.Delay(TimeSpan.FromSeconds(8)));
                if (!ReferenceEquals(completed, validation))
                {
                    auth.CancelPendingRequests();
                    throw new TimeoutException("Сервер не ответил за 8 секунд.");
                }
                await validation;
                return auth;
            }
            catch (ApiException ex)
            {
                if (ex.Status == 401 || ex.Status == 403 || ex.Status == 404)
                    AdminCredentialStore.AdminSessionStore.Delete();
                else
                    MessageBox.Show("Сохранённая сессия не прошла проверку:\n" + ex.Message +
                        "\n\nВойди повторно или выбери режим мониторинга.",
                        "Восстановление сессии", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return null;
            }
            catch (Exception ex)
            {
                MessageBox.Show("Не удалось проверить сохранённую сессию:\n" + ex.Message +
                    "\n\nВойди повторно или выбери режим мониторинга.",
                    "Восстановление сессии", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return null;
            }
        }

        private sealed class SessionRestoreForm : Form
        {
            private readonly Panel _indicator = new Panel();
            private readonly Panel _track = new Panel();
            private readonly System.Windows.Forms.Timer _timer = new System.Windows.Forms.Timer { Interval = 24 };
            private int _direction = 1;

            public SessionRestoreForm()
            {
                Text = "UNYRI Admin Console";
                ClientSize = new Size(520, 180);
                ControlBox = false;
                UiTheme.ApplyDialog(this);
                ShowInTaskbar = true;
                StartPosition = FormStartPosition.CenterScreen;

                var title = new Label
                {
                    Text = "Проверяем сохранённую сессию",
                    Font = UiTheme.H3,
                    ForeColor = UiTheme.Text,
                    AutoSize = true,
                    Location = new Point(24, 24)
                };
                var description = new Label
                {
                    Text = "Проверка соединения и административных прав…",
                    Font = UiTheme.Body,
                    ForeColor = UiTheme.TextSecondary,
                    AutoSize = true,
                    Location = new Point(24, 62)
                };
                _track.SetBounds(24, 112, 472, 6);
                _track.BackColor = UiTheme.SurfaceRaised;
                _indicator.SetBounds(0, 0, 112, 6);
                _indicator.BackColor = UiTheme.Accent;
                _track.Controls.Add(_indicator);
                Controls.AddRange(new Control[] { title, description, _track });

                _timer.Tick += (s, e) =>
                {
                    var next = _indicator.Left + 8 * _direction;
                    if (next <= 0) { next = 0; _direction = 1; }
                    if (next + _indicator.Width >= _track.ClientSize.Width)
                    {
                        next = Math.Max(0, _track.ClientSize.Width - _indicator.Width);
                        _direction = -1;
                    }
                    _indicator.Left = next;
                };
                Shown += (s, e) => _timer.Start();
                FormClosed += (s, e) => _timer.Stop();
            }

            protected override void Dispose(bool disposing)
            {
                if (disposing) _timer.Dispose();
                base.Dispose(disposing);
            }
        }
    }

    internal static class WindowTheme
    {
        public static void ApplyDarkTitleBar(Form form)
        {
            var enabled = 1;
            if (DwmSetWindowAttribute(form.Handle, 20, ref enabled, sizeof(int)) != 0)
                DwmSetWindowAttribute(form.Handle, 19, ref enabled, sizeof(int));
        }

        [DllImport("dwmapi.dll", PreserveSig = true)]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int valueSize);
    }
}
