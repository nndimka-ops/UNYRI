using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using MessageBox = CocoAdmin.ConsoleDialog;

namespace CocoAdmin
{
    internal sealed class MainForm : Form
    {
        private readonly Label _backend = new Label();
        private readonly Label _docker = new Label();
        private readonly Label _usersOnline = new Label();
        private readonly Label _usersTotal = new Label();
        private readonly Label _reports = new Label();
        private readonly Label _feedback = new Label();
        private readonly Label _diagnostics = new Label();
        private readonly Label _message = new Label();
        private readonly Label _userCount = new Label();
        private readonly Label _reportCount = new Label();
        private readonly Label _feedbackCount = new Label();
        private readonly Label _usersFooter = PageFooter();
        private readonly Label _reportsFooter = PageFooter();
        private readonly Label _feedbackFooter = PageFooter();
        private readonly Label _depsFooter = PageFooter();
        private readonly Label _logsFooter = PageFooter();
        private readonly ToolTip _metricToolTip = new ToolTip();

        private readonly DataGridView _usersGrid = Grid();
        private readonly DataGridView _reportsGrid = Grid();
        private readonly DataGridView _feedbackGrid = Grid();
        private readonly DataGridView _depsGrid = Grid();
        private readonly DataGridView _aiGrid = Grid();
        private readonly Label _aiStatus = new Label();
        private readonly RichTextBox _aiDetail = new RichTextBox();
        private readonly ComboBox _aiView = new ComboBox();
        private readonly ComboBox _aiSeverity = new ComboBox();
        private readonly ComboBox _aiCategory = new ComboBox();
        private readonly TextBox _aiSearch = new TextBox();
        private readonly List<Dictionary<string, object>> _aiAllRows = new List<Dictionary<string, object>>();
        private Label _aiModeChip;
        private Label _aiHealthChip;
        private Label _aiFreshChip;
        private Label _aiLastChip;
        private Panel _aiEmptyPanel;
        private bool _aiLoading;
        private Process _aiWatcherLoop;
        private static readonly Regex AiAnsi = new Regex("\\x1b\\[[0-9;?]*[A-Za-z]", RegexOptions.Compiled);
        private static readonly Regex AiLogTime = new Regex("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})", RegexOptions.Compiled);
        private static readonly Regex AiModule = new Regex("((?:messenger_server|sqlx_core|tower_http|hyper)::[A-Za-z0-9_:]+)", RegexOptions.Compiled);
        private readonly TextBox _search = new TextBox();
        private readonly RichTextBox _log = new RichTextBox();
        private readonly Timer _timer = new Timer();
        private readonly PageHost _tabs = new PageHost();
        private readonly List<Button> _navigationButtons = new List<Button>();
        private readonly List<Panel> _metricCards = new List<Panel>();
        private readonly Panel _adminTab;
        private Panel _header;
        private FlowLayoutPanel _headerActions;
        private readonly Panel _adminLoginPanel = new Panel();
        private readonly Label _adminLoginLabel = new Label();
        private readonly Button _adminLoginButton;
        private readonly NativeAdminPanel _adminPanel = new NativeAdminPanel();
        private Button _logoutButton;
        private Label _targetSubtitle;
        private Label _workspaceChip;
        private AdminAuth _auth;
        private bool _busy;
        private bool _refreshPending;
        private bool _healthBusy;
        private bool _operationBusy;
        private bool _logErrors;
        private readonly List<string> _refreshErrors = new List<string>();
        private int _overviewRequestVersion;
        private int _usersRequestVersion;
        private int _reportsRequestVersion;
        private int _feedbackRequestVersion;
        private int _dependenciesRequestVersion;
        private int _logsRequestVersion;

        public MainForm(AdminAuth auth = null, bool monitoringOnly = false)
        {
            _auth = auth;
            _adminTab = new Panel
            {
                BackColor = UiTheme.Surface,
                ForeColor = UiTheme.Text,
                Padding = new Padding(0)
            };
            _adminLoginButton = Button("Войти как администратор", async () => await LoginForAdministrationAsync(), 240);
            _adminPanel.LogoutRequested += LogoutAdminAsync;
            _adminPanel.LocalOperationStateChanged += busy => _operationBusy = busy;
            _adminPanel.LocalSessionInvalidated += ClearInvalidatedAdminSession;
            Text = "UNYRI Admin Console";
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(1024, 640);
            Size = new Size(1280, 780);
            WindowState = FormWindowState.Maximized;
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96f, 96f);
            BackColor = UiTheme.Background;
            ForeColor = UiTheme.Text;
            Font = new Font("Segoe UI", 9f);
            Icon = IconFactory.AppIcon();

            var header = BuildHeader();
            _tabs.Dock = DockStyle.Fill;

            BuildAdminTab();
            BuildOverviewTab();
            BuildUsersTab();
            BuildReportsTab();
            BuildFeedbackTab();
            BuildDependenciesTab();
            BuildLogsTab();
            BuildAiTab();
            _metricToolTip.SetToolTip(_diagnostics,
                "События диагностики хранятся в Redis. Числовой счётчик не подключён к сводке.");
            _tabs.SelectedIndex = monitoringOnly ? 1 : 0;

            var content = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background };
            content.Controls.Add(_tabs);
            var shell = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background };
            shell.Controls.Add(content);
            shell.Controls.Add(BuildNavigation());
            Controls.Add(shell);
            Controls.Add(header);
            _tabs.SelectedIndexChanged += async (s, e) =>
            {
                UpdateNavigationSelection();
                if (Visible) await RefreshAllAsync();
            };
            UpdateNavigationSelection();

            _timer.Interval = 15000;
            _timer.Tick += async (s, e) => { if (!_busy && !_operationBusy && WindowState != FormWindowState.Minimized) await RunAsync(RefreshHealthOnlyAsync); };
            _timer.Start();
            Shown += async (s, e) =>
            {
                WindowTheme.ApplyDarkTitleBar(this);
                await RefreshAllAsync();
                if (Environment.GetEnvironmentVariable("COCO_RESUME_AI_WATCHER") == "1") StartAiWatcherLoop();
            };
            FormClosing += (s, e) => {
                if (_operationBusy) { e.Cancel = true; MessageBox.Show(this, "Дождитесь завершения команды сервера."); return; }
                if (_aiWatcherLoop != null && !_aiWatcherLoop.HasExited)
                {
                    try { _aiWatcherLoop.Kill(); } catch { }
                }
                if (_aiWatcherLoop != null) _aiWatcherLoop.Dispose();
                _timer.Stop(); _timer.Dispose(); _metricToolTip.Dispose();
            };
            UiTheme.ApplyForm(this);
            UiTheme.StyleGrid(_usersGrid);
            UiTheme.StyleGrid(_reportsGrid);
            UiTheme.StyleGrid(_feedbackGrid);
            UiTheme.StyleGrid(_depsGrid);
        }

        private Control BuildHeader()
        {
            var panel = new Panel
            {
                Dock = DockStyle.Top,
                Height = 78,
                BackColor = UiTheme.Header,
                Padding = new Padding(22, 8, 18, 8)
            };
            _header = panel;
            var mark = new PictureBox
            {
                Image = IconFactory.BrandImage(32),
                Size = new Size(32, 32),
                Location = new Point(22, 13),
                SizeMode = PictureBoxSizeMode.Zoom,
                BackColor = Color.Transparent
            };
            var title = new Label
            {
                Text = "UNYRI  ·  ADMIN",
                Font = new Font("Segoe UI Semibold", 13f),
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Location = new Point(64, 10)
            };
            var subtitle = new Label
            {
                Text = "Сервер не выбран",
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                AutoSize = false,
                AutoEllipsis = true,
                Size = new Size(235, 22),
                Location = new Point(65, 39)
            };
            _targetSubtitle = subtitle;
            _message.Text = "Проверка состояния...";
            _message.ForeColor = UiTheme.TextSecondary;
            _message.BackColor = UiTheme.SurfaceRaised;
            _message.Font = UiTheme.BodySmall;
            _message.AutoSize = false;
            _message.AutoEllipsis = true;
            _message.TextAlign = ContentAlignment.MiddleLeft;
            _message.Padding = new Padding(12, 0, 12, 0);
            _message.Height = 28;
            UiTheme.Round(_message, 14);
            _message.TextChanged += (s, e) => _metricToolTip.SetToolTip(_message, _message.Text);

            var actions = new FlowLayoutPanel
            {
                Dock = DockStyle.Right,
                Width = 500,
                FlowDirection = FlowDirection.RightToLeft,
                WrapContents = false,
                BackColor = Color.Transparent,
                Padding = new Padding(0, 8, 0, 0)
            };
            _headerActions = actions;
            actions.Controls.Add(Button("Обновить", async () => await RefreshAllAsync(), 112));
            actions.Controls.Add(Button("Админ", () => _tabs.SelectedTab = _adminTab, 92));
            actions.Controls.Add(Button("Этот ПК", () => _tabs.SelectedIndex = 1, 86));
            _logoutButton = Button("Выйти", async () => await LogoutAdminAsync(), 92);
            _logoutButton.Visible = _auth != null;
            actions.Controls.Add(_logoutButton);

            panel.Controls.AddRange(new Control[] { mark, title, subtitle, _message, actions });
            panel.SizeChanged += (s, e) => UpdateHeaderLayout();
            UpdateHeaderLayout();
            return panel;
        }

        private void UpdateHeaderLayout()
        {
            if (_header == null || _headerActions == null) return;
            var compact = _header.ClientSize.Width < 1120;
            foreach (Control control in _headerActions.Controls)
                if (control.Text == "Админ" || control.Text == "Этот ПК")
                    control.Visible = !compact;
            _headerActions.Width = compact ? (_logoutButton.Visible ? 235 : 126) : 500;
            _message.Visible = _header.ClientSize.Width >= 900;
            var messageLeft = 310;
            var messageWidth = Math.Max(140, _header.ClientSize.Width - _headerActions.Width - messageLeft - 18);
            _message.SetBounds(messageLeft, 24, messageWidth, 28);
        }

        private Control BuildNavigation()
        {
            var sidebar = new Panel
            {
                Dock = DockStyle.Left,
                Width = 218,
                BackColor = UiTheme.Header,
                Padding = new Padding(12, 18, 12, 14)
            };
            var section = new Label
            {
                Text = "РАБОЧАЯ ОБЛАСТЬ",
                ForeColor = UiTheme.TextMuted,
                Font = UiTheme.Micro,
                TextAlign = ContentAlignment.MiddleLeft,
                Padding = new Padding(10, 0, 0, 0)
            };
            var nav = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                BackColor = Color.Transparent,
                Margin = new Padding(0),
                Padding = new Padding(0, 4, 0, 0),
                AutoScroll = true
            };
            var labels = new[] { "Управление", "Обзор", "Пользователи", "Жалобы", "Обращения", "Зависимости", "Логи", "AI-наблюдение" };
            for (var i = 0; i < labels.Length; i++)
            {
                if (i == 1)
                    nav.Controls.Add(new Label { Text = "ЭТОТ КОМПЬЮТЕР", Font = UiTheme.Micro,
                        ForeColor = UiTheme.TextMuted, Width = 194, Height = 35,
                        Padding = new Padding(10, 14, 0, 0), Margin = new Padding(0, 8, 0, 0) });
                var button = NavigationButton(labels[i], i);
                _navigationButtons.Add(button);
                nav.Controls.Add(button);
            }
            var local = new Label
            {
                Text = "●  ЛОКАЛЬНЫЙ СЕРВЕР",
                ForeColor = UiTheme.Success,
                BackColor = UiTheme.Surface,
                Font = UiTheme.Micro,
                TextAlign = ContentAlignment.MiddleLeft,
                Padding = new Padding(12, 0, 0, 0)
            };
            UiTheme.Round(local, 10);
            _workspaceChip = local;
            sidebar.Controls.Add(nav);
            sidebar.Controls.Add(local);
            sidebar.Controls.Add(section);
            var arrangingSidebar = false;
            Action arrange = () =>
            {
                if (arrangingSidebar) return;
                arrangingSidebar = true;
                try
                {
                var requiredButton = _navigationButtons.Max(button => TextRenderer.MeasureText(button.Text, button.Font).Width +
                    button.Padding.Horizontal + (button.Image == null ? 0 : button.Image.Width + 10) + 12);
                var requiredWidth = requiredButton + sidebar.Padding.Horizontal + SystemInformation.VerticalScrollBarWidth;
                sidebar.Width = Math.Max((int)Math.Round(218 * DeviceDpi / 96.0), requiredWidth);
                var width = Math.Max(0, sidebar.ClientSize.Width - sidebar.Padding.Horizontal);
                var height = Math.Max(0, sidebar.ClientSize.Height - sidebar.Padding.Vertical);
                section.SetBounds(sidebar.Padding.Left, sidebar.Padding.Top, width, 32);
                local.SetBounds(sidebar.Padding.Left, sidebar.ClientSize.Height - sidebar.Padding.Bottom - 44, width, 44);
                nav.SetBounds(sidebar.Padding.Left, sidebar.Padding.Top + 36, width, Math.Max(0, height - 88));
                foreach (var button in _navigationButtons) button.Width = Math.Max(80, width - SystemInformation.VerticalScrollBarWidth);
                }
                finally { arrangingSidebar = false; }
            };
            sidebar.SizeChanged += (s, e) => arrange();
            foreach (var button in _navigationButtons) button.FontChanged += (s, e) => arrange();
            DpiChanged += (s, e) => arrange();
            arrange();
            return sidebar;
        }

        private Button NavigationButton(string text, int tabIndex)
        {
            var button = new Button
            {
                Text = text,
                Tag = tabIndex,
                Width = 194,
                Height = 44,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.Transparent,
                ForeColor = UiTheme.TextSecondary,
                Font = new Font("Segoe UI Semibold", 9.5f),
                TextAlign = ContentAlignment.MiddleLeft,
                Padding = new Padding(16, 0, 8, 0),
                Margin = new Padding(0, 2, 0, 2),
                Cursor = Cursors.Hand,
                UseVisualStyleBackColor = false
            };
            button.FlatAppearance.BorderSize = 0;
            button.FlatAppearance.MouseOverBackColor = UiTheme.SurfaceHover;
            button.FlatAppearance.MouseDownBackColor = UiTheme.AccentSoft;
            var glyph = tabIndex == 0 ? IconFactory.Admin : IconFactory.GlyphForText(text);
            if (glyph != null)
            {
                button.Image = IconFactory.Glyph(glyph, 17, Color.FromArgb(157, 175, 207));
                button.ImageAlign = ContentAlignment.MiddleLeft;
                button.TextImageRelation = TextImageRelation.ImageBeforeText;
                button.Padding = new Padding(12, 0, 6, 0);
            }
            UiTheme.Round(button, 10);
            button.Paint += (s, e) =>
            {
                if (button.BackColor != UiTheme.AccentSoft) return;
                using (var brush = new SolidBrush(UiTheme.Accent))
                    e.Graphics.FillRectangle(brush, 0, 8, 3, button.Height - 16);
            };
            button.Click += (s, e) => _tabs.SelectedIndex = tabIndex;
            return button;
        }

        private void UpdateNavigationSelection()
        {
            for (var i = 0; i < _navigationButtons.Count; i++)
            {
                var selected = i == _tabs.SelectedIndex;
                var button = _navigationButtons[i];
                button.BackColor = selected ? UiTheme.AccentSoft : Color.Transparent;
                button.ForeColor = selected ? UiTheme.Text : UiTheme.TextSecondary;
                button.FlatAppearance.BorderSize = 0;
                button.Invalidate();
            }
            UpdateTargetContext();
        }

        private void UpdateTargetContext()
        {
            var serverPage = _tabs.SelectedIndex == 0;
            var target = _auth == null ? "Сервер не выбран" : AdminServerTarget.Display(_auth.BaseUrl);
            if (_targetSubtitle != null)
            {
                _targetSubtitle.Text = serverPage ? target : "Локальные инструменты · этот Windows-ПК";
                _metricToolTip.SetToolTip(_targetSubtitle, serverPage ? target :
                    "Эти разделы работают только с Docker и файлами этого ПК. Облачный сервер они не изменяют.");
            }
            if (_workspaceChip != null)
            {
                _workspaceChip.Text = serverPage ? (_auth == null ? "ВХОД НЕ ВЫПОЛНЕН" : "●  ВЫБРАННЫЙ СЕРВЕР") : "●  ТОЛЬКО ЭТОТ ПК";
                _workspaceChip.ForeColor = serverPage ? UiTheme.Info : UiTheme.Warning;
            }
        }

        private void BuildAdminTab()
        {
            _adminLoginPanel.Dock = DockStyle.Fill;
            _adminLoginPanel.BackColor = UiTheme.Background;
            var card = AdminSecurityUi.Stack(24);
            card.Dock = DockStyle.None;
            card.BackColor = UiTheme.Surface;
            UiTheme.StyleCard(card, 16);
            var icon = new PictureBox
            {
                Image = IconFactory.Glyph(IconFactory.Admin, 32, UiTheme.Accent),
                SizeMode = PictureBoxSizeMode.Normal,
                BackColor = Color.Transparent,
                Height = 36,
                Margin = new Padding(0, 0, 0, 14)
            };
            var title = new Label
            {
                Text = "Требуется вход",
                Font = UiTheme.H2,
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Margin = new Padding(0, 0, 0, 12)
            };
            _adminLoginLabel.Text = "Войдите с паролем и выбранным способом защиты:\nаутентификатор, бумажный ключ или оба.";
            _adminLoginLabel.ForeColor = UiTheme.TextSecondary;
            _adminLoginLabel.Font = UiTheme.Body;
            _adminLoginLabel.AutoSize = true;
            _adminLoginLabel.Margin = new Padding(0, 0, 0, 24);
            AdminSecurityUi.Add(card, icon);
            AdminSecurityUi.Add(card, title);
            AdminSecurityUi.Add(card, _adminLoginLabel);
            AdminSecurityUi.Add(card, _adminLoginButton);
            _adminLoginPanel.Controls.Add(card);
            var centeringCard = false;
            Action centerCard = () =>
            {
                if (centeringCard) return;
                centeringCard = true;
                try
                {
                var preferred = (int)Math.Round(460 * DeviceDpi / 96.0);
                var width = Math.Max(240, Math.Min(preferred, _adminLoginPanel.ClientSize.Width - 48));
                card.MinimumSize = new Size(width, 0);
                card.MaximumSize = new Size(width, 0);
                _adminLoginLabel.MaximumSize = new Size(Math.Max(180, width - card.Padding.Horizontal), 0);
                card.Location = new Point(Math.Max(18, (_adminLoginPanel.ClientSize.Width - card.Width) / 2),
                    Math.Max(18, (_adminLoginPanel.ClientSize.Height - card.Height) / 2));
                }
                finally { centeringCard = false; }
            };
            _adminLoginPanel.SizeChanged += (s, e) => centerCard();
            card.SizeChanged += (s, e) => centerCard();
            centerCard();
            _adminPanel.Dock = DockStyle.Fill;
            _adminTab.Controls.Add(_adminPanel);
            _adminTab.Controls.Add(_adminLoginPanel);
            _adminLoginPanel.Visible = _auth == null;
            _adminPanel.Auth = _auth;
            _adminPanel.Enabled = _auth != null;
            if (_adminLoginPanel.Visible) _adminLoginPanel.BringToFront();
            _tabs.AddPage(_adminTab);
        }

        private async Task LoginForAdministrationAsync()
        {
            using (var login = new LoginForm())
            {
                if (login.ShowDialog(this) != DialogResult.OK || login.Auth == null) return;
                _auth = login.Auth;
                _adminPanel.Auth = _auth;
                _adminPanel.Enabled = true;
                _adminLoginPanel.Visible = false;
                if (_logoutButton != null) _logoutButton.Visible = true;
                UpdateHeaderLayout();
                UpdateTargetContext();
                await LoadAdminConsoleAsync();
            }
        }

        private async Task LoadAdminConsoleAsync()
        {
            if (_auth == null) return;
            _adminPanel.Auth = _auth;
            await _adminPanel.RefreshAllAsync();
        }

        private async Task LogoutAdminAsync()
        {
            if (_auth == null) return;
            if (_operationBusy) { MessageBox.Show(this, "Дождитесь завершения локальной команды сервера, затем выйдите из консоли."); return; }
            if (MessageBox.Show(this, "Завершить административную сессию на сервере?",
                "Выход", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            var auth = _auth;
            _auth = null;
            Exception logoutError = null;
            try { AdminCredentialStore.AdminSessionStore.Delete(); }
            catch (Exception ex) { logoutError = ex; }
            _adminPanel.Auth = null;
            _adminPanel.Enabled = false;
            _adminLoginLabel.Text = "Сессия завершена.\n\nДля доступа к управлению войди снова.";
            _adminLoginPanel.Visible = true;
            _adminLoginPanel.BringToFront();
            if (_logoutButton != null) _logoutButton.Visible = false;
            UpdateHeaderLayout();
            UpdateTargetContext();
            try
            {
                await auth.LogoutAsync();
            }
            catch (Exception ex)
            {
                if (logoutError == null) logoutError = ex;
                else logoutError = new AggregateException(logoutError, ex);
            }
            if (logoutError != null)
                MessageBox.Show(this, "Локальный экран заблокирован, но выход не завершился полностью:\n" + logoutError.Message,
                    "Выход", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            auth.Dispose();
        }

        private void ClearInvalidatedAdminSession()
        {
            var expiredAuth = _auth;
            try { AdminCredentialStore.AdminSessionStore.Delete(); }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Не удалось удалить локальную сессию. Она больше не действительна на сервере:\n" + ex.Message,
                    "Сессия", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            _auth = null;
            _adminPanel.Auth = null;
            _adminPanel.Enabled = false;
            _adminLoginLabel.Text = "Административная сессия завершена.\n\nВойдите заново для управления выбранным сервером.";
            _adminLoginPanel.Visible = true;
            _adminLoginPanel.BringToFront();
            if (_logoutButton != null) _logoutButton.Visible = false;
            UpdateHeaderLayout();
            UpdateTargetContext();
            if (expiredAuth != null) expiredAuth.Dispose();
        }

        private void BuildOverviewTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var titleBar = PageHeader(
                "Обзор системы",
                "Сервисы, активность и быстрые действия · автообновление каждые 15 секунд",
                null);
            titleBar.Dock = DockStyle.None;
            titleBar.AutoSize = false;

            var actionCard = new Panel { BackColor = UiTheme.Surface, Padding = new Padding(18, 12, 14, 12) };
            UiTheme.StyleCard(actionCard, 14);
            var actionTitle = new Label
            {
                Text = "Управление сервером",
                Font = UiTheme.H3,
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Location = new Point(18, 14)
            };
            var actionHint = new Label
            {
                Text = "Запуск локального стека и проверка рабочего окружения",
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Location = new Point(18, 42)
            };

            var metrics = new Panel
            {
                Dock = DockStyle.None,
                AutoScroll = true,
                BackColor = UiTheme.Background,
                Padding = new Padding(0, 8, 0, 8),
                Margin = new Padding(0)
            };
            AddMetricCard(metrics, "Backend", _backend);
            AddMetricCard(metrics, "Docker Engine", _docker);
            AddMetricCard(metrics, "Пользователей", _usersTotal);
            AddMetricCard(metrics, "Активны за 2 минуты", _usersOnline);
            AddMetricCard(metrics, "Открытых жалоб", _reports);
            AddMetricCard(metrics, "Обращений", _feedback);
            AddMetricCard(metrics, "Хранилище диагностики", _diagnostics);
            metrics.SizeChanged += (s, e) => ArrangeMetricCards(metrics);

            var actions = new FlowLayoutPanel
            {
                Dock = DockStyle.Right,
                Width = 705,
                FlowDirection = FlowDirection.RightToLeft,
                WrapContents = false,
                AutoScroll = true,
                Padding = new Padding(0, 9, 0, 0),
                BackColor = UiTheme.Surface
            };
            actions.Controls.Add(Button("Запустить сервер", async () => await StartStackAsync(false, false), 168));
            var stopServer = Button("Остановить полностью", async () => await StopStackAsync(), 220);
            UiTheme.StyleButton(stopServer, UiButtonKind.Danger);
            actions.Controls.Add(stopServer);
            actions.Controls.Add(Button("Пересобрать", async () => await StartStackAsync(true, true), 152));
            actions.Controls.Add(Button("Зависимости", async () => await RefreshDependenciesAsync(), 150));
            actionCard.Controls.Add(actions);
            actionCard.Controls.Add(actionTitle);
            actionCard.Controls.Add(actionHint);

            var hint = new Label
            {
                Text = "Сводка работает в режиме чтения. Действия с пользователями и обращениями доступны в разделе «Админ».",
                ForeColor = UiTheme.TextMuted,
                TextAlign = ContentAlignment.MiddleLeft,
                Font = UiTheme.BodySmall,
                Padding = new Padding(4, 0, 0, 0)
            };
            tab.Controls.Add(metrics);
            tab.Controls.Add(hint);
            tab.Controls.Add(actionCard);
            tab.Controls.Add(titleBar);
            _tabs.AddPage(tab);
            Action arrangeSections = () =>
            {
                var width = Math.Max(0, tab.ClientSize.Width - tab.Padding.Horizontal);
                var height = Math.Max(0, tab.ClientSize.Height - tab.Padding.Vertical);
                var titleHeight = Math.Max(76, titleBar.GetPreferredSize(new Size(width, 0)).Height);
                titleBar.SetBounds(tab.Padding.Left, tab.Padding.Top, width, titleHeight);
                var actionTop = tab.Padding.Top + titleHeight + 8;
                actionCard.SetBounds(tab.Padding.Left, actionTop, width, 78);
                actions.Width = width < 940 ? Math.Max(310, width - 250) : 705;
                actionTitle.Visible = width >= 760;
                actionHint.Visible = width >= 900;
                var metricsTop = actionTop + 90;
                metrics.SetBounds(tab.Padding.Left, metricsTop, width,
                    Math.Max(0, tab.Padding.Top + height - metricsTop - 32));
                hint.SetBounds(tab.Padding.Left + 4, tab.Padding.Top + height - 28, Math.Max(0, width - 8), 28);
                ArrangeMetricCards(metrics);
                metrics.SendToBack();
                hint.BringToFront();
                actionCard.BringToFront();
                titleBar.BringToFront();
            };
            tab.SizeChanged += (s, e) => arrangeSections();
            arrangeSections();
        }

        private void AddMetricCard(Panel layout, string title, Label value)
        {
            var card = StatCard(title, value);
            card.Size = new Size(300, 102);
            _metricCards.Add(card);
            layout.Controls.Add(card);
        }

        private void ArrangeMetricCards(Panel metrics)
        {
            if (metrics.ClientSize.Width <= 0) return;
            const int gap = 12;
            const int cardHeight = 102;
            var availableWidth = metrics.ClientSize.Width - metrics.Padding.Horizontal;
            var columns = availableWidth >= 1320 ? 4 : availableWidth >= 900 ? 3 : availableWidth >= 580 ? 2 : 1;
            var cardWidth = Math.Max(180, (availableWidth - gap * (columns + 1)) / columns);
            for (var i = 0; i < _metricCards.Count; i++)
            {
                var column = i % columns;
                var row = i / columns;
                _metricCards[i].SetBounds(
                    metrics.Padding.Left + gap + column * (cardWidth + gap),
                    metrics.Padding.Top + row * (cardHeight + gap),
                    cardWidth,
                    cardHeight);
            }
            var rows = (_metricCards.Count + columns - 1) / columns;
            metrics.AutoScrollMinSize = new Size(0, metrics.Padding.Vertical + gap + rows * (cardHeight + gap));
        }

        private static Panel PageHeader(string title, string subtitle, Label counter)
        {
            var header = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                MinimumSize = new Size(0, 76),
                ColumnCount = counter == null ? 1 : 2,
                RowCount = 2,
                Margin = new Padding(0),
                Padding = new Padding(2, 0, 0, 10),
                BackColor = UiTheme.Background
            };
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            if (counter != null) header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 280));
            header.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            header.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            header.Controls.Add(new Label
            {
                Text = title,
                Font = UiTheme.H1,
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(0)
            }, 0, 0);
            header.Controls.Add(new Label
            {
                Text = subtitle,
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(1, 4, 0, 0)
            }, 0, 1);
            if (counter != null)
            {
                counter.Font = UiTheme.BodySmall;
                counter.ForeColor = UiTheme.TextMuted;
                counter.TextAlign = ContentAlignment.MiddleRight;
                counter.Dock = DockStyle.Fill;
                counter.AutoEllipsis = true;
                counter.Margin = new Padding(12, 0, 0, 10);
                header.Controls.Add(counter, 1, 0);
                header.SetRowSpan(counter, 2);
            }
            return header;
        }

        private static FlowLayoutPanel PageToolbar()
        {
            return new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                Height = 56,
                WrapContents = false,
                AutoScroll = true,
                Padding = new Padding(12, 10, 12, 10),
                BackColor = UiTheme.Surface
            };
        }

        private static Panel ToolbarCard(Control toolbar)
        {
            var card = new Panel { Dock = DockStyle.Top, Height = 62, BackColor = UiTheme.Surface, Padding = new Padding(1) };
            UiTheme.StyleCard(card, 12);
            toolbar.Dock = DockStyle.Fill;
            card.Controls.Add(toolbar);
            return card;
        }

        private static Label PageFooter()
        {
            return new Label
            {
                Dock = DockStyle.Bottom,
                Height = 32,
                Padding = new Padding(4, 0, 4, 0),
                ForeColor = UiTheme.TextMuted,
                Font = UiTheme.BodySmall,
                TextAlign = ContentAlignment.MiddleLeft,
                Text = "Загрузка данных…"
            };
        }

        private static Panel GridCard(DataGridView grid)
        {
            var card = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Surface, Padding = new Padding(1) };
            UiTheme.StyleCard(card, 12);
            grid.Dock = DockStyle.Fill;
            card.Controls.Add(grid);
            return card;
        }

        private void BuildUsersTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var header = PageHeader("Пользователи", "Локальный просмотр аккаунтов и переход к модерации", _userCount);
            var toolbar = PageToolbar();
            _search.Width = 300;
            _search.BackColor = UiTheme.SurfaceRaised;
            _search.ForeColor = UiTheme.Text;
            UiTheme.StyleInput(_search);
            _search.BorderStyle = BorderStyle.None;
            _search.Height = 30;
            UiTheme.SetPlaceholder(_search, "Поиск по имени…");
            _search.Text = "";
            var searchTip = new ToolTip();
            searchTip.SetToolTip(_search, "Поиск по username или display name");
            toolbar.Controls.Add(FilterLabel("Поиск"));
            toolbar.Controls.Add(_search);
            toolbar.Controls.Add(Button("Найти", async () => await RefreshUsersAsync(), 110));
            toolbar.Controls.Add(Button("Сбросить", async () => { _search.Text = ""; await RefreshUsersAsync(); }, 110));
            toolbar.Controls.Add(Button("Админ-консоль", () => _tabs.SelectedTab = _adminTab, 150));

            ConfigureGrid(_usersGrid, new[] { "пользователь", "отображаемое имя", "последняя активность", "статус" });
            tab.Controls.Add(GridCard(_usersGrid));
            tab.Controls.Add(_usersFooter);
            tab.Controls.Add(ToolbarCard(toolbar));
            tab.Controls.Add(header);
            _tabs.AddPage(tab);
        }

        private void BuildReportsTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var header = PageHeader("Жалобы", "Жалобы пользователей и переход к рассмотрению", _reportCount);
            var toolbar = PageToolbar();
            toolbar.Controls.Add(Button("Обновить", async () => await RefreshReportsAsync(), 120));
            toolbar.Controls.Add(Button("Перейти к управлению", () => _tabs.SelectedTab = _adminTab, 180));
            ConfigureGrid(_reportsGrid, new[] { "создано", "причина", "статус", "описание" });
            tab.Controls.Add(GridCard(_reportsGrid));
            tab.Controls.Add(_reportsFooter);
            tab.Controls.Add(ToolbarCard(toolbar));
            tab.Controls.Add(header);
            _tabs.AddPage(tab);
        }

        private void BuildFeedbackTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var header = PageHeader("Обращения", "Технические отчёты и обратная связь", _feedbackCount);
            var toolbar = PageToolbar();
            toolbar.Controls.Add(Button("Обновить", async () => await RefreshFeedbackAsync(), 120));
            toolbar.Controls.Add(Button("Перейти к управлению", () => _tabs.SelectedTab = _adminTab, 180));
            ConfigureGrid(_feedbackGrid, new[] { "время", "платформа", "версия", "статус", "описание" });
            tab.Controls.Add(GridCard(_feedbackGrid));
            tab.Controls.Add(_feedbackFooter);
            tab.Controls.Add(ToolbarCard(toolbar));
            tab.Controls.Add(header);
            _tabs.AddPage(tab);
        }

        private void BuildDependenciesTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var header = PageHeader("Зависимости", "Проверка инструментов, контейнеров и локального стека", null);
            var toolbar = PageToolbar();
            toolbar.Controls.Add(Button("Проверить всё", async () => await RefreshDependenciesAsync(), 140));
            toolbar.Controls.Add(Button("Проверить обновления", async () => await CheckUpdatesAsync(), 180));
            toolbar.Controls.Add(Button("Обновить инструменты", async () => await UpdateToolsAsync(), 180));
            toolbar.Controls.Add(Button("Установить/исправить выбранное", async () => await FixSelectedDependencyAsync(), 240));
            toolbar.Controls.Add(Button("Открыть проект", () => ServerControl.OpenWorkspace(), 130));
            ConfigureGrid(_depsGrid, new[] { "компонент", "статус", "детали", "действие" });
            tab.Controls.Add(GridCard(_depsGrid));
            tab.Controls.Add(_depsFooter);
            tab.Controls.Add(ToolbarCard(toolbar));
            tab.Controls.Add(header);
            _tabs.AddPage(tab);
        }

        private void BuildLogsTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var header = PageHeader("Логи", "Свежий вывод backend и ошибки локального сервера", null);
            var toolbar = PageToolbar();
            toolbar.Controls.Add(Button("Обновить", async () => await RefreshLogsAsync(), 120));
            toolbar.Controls.Add(Button("Ошибки", async () => { _logErrors = true; await RefreshLogsAsync(); }, 100));
            toolbar.Controls.Add(Button("События", async () => { _logErrors = false; await RefreshLogsAsync(); }, 100));
            toolbar.Controls.Add(Button("Открыть папку логов", () => ServerControl.OpenLogs(), 160));
            toolbar.Controls.Add(Button("Перейти к управлению", () => _tabs.SelectedTab = _adminTab, 180));
            _log.Dock = DockStyle.Fill;
            _log.ReadOnly = true;
            _log.BackColor = Color.FromArgb(8, 11, 17);
            _log.ForeColor = UiTheme.TextSecondary;
            _log.Font = UiTheme.Mono;
            _log.BorderStyle = BorderStyle.None;
            _log.WordWrap = false;
            _log.ScrollBars = RichTextBoxScrollBars.Both;
            _log.DetectUrls = false;
            UiTheme.ApplyDarkNativeTheme(_log);
            var card = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Surface, Padding = new Padding(12) };
            UiTheme.StyleCard(card, 12);
            card.Controls.Add(_log);
            tab.Controls.Add(card);
            tab.Controls.Add(_logsFooter);
            tab.Controls.Add(ToolbarCard(toolbar));
            tab.Controls.Add(header);
            _tabs.AddPage(tab);
        }

        private void BuildAiTab()
        {
            var tab = new Panel { BackColor = UiTheme.Background, ForeColor = UiTheme.Text, Padding = new Padding(24) };
            var header = PageHeader(
                "AI-наблюдение",
                "Выбери срез, уточни фильтры и открой строку — справа будет разбор.",
                null);

            var chips = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 108,
                WrapContents = false,
                AutoScroll = true,
                Padding = new Padding(0, 3, 0, 8),
                BackColor = UiTheme.Background
            };
            _aiModeChip = AiMetricValue();
            _aiHealthChip = AiMetricValue();
            _aiFreshChip = AiMetricValue();
            _aiLastChip = AiMetricValue();
            chips.Controls.Add(AiMetricCard("DEEPSEEK", _aiModeChip, Color.FromArgb(143, 166, 255)));
            chips.Controls.Add(AiMetricCard("СОСТОЯНИЕ СЕРВЕРА", _aiHealthChip, UiTheme.Success));
            chips.Controls.Add(AiMetricCard("СВЕЖИЕ ОШИБКИ", _aiFreshChip, UiTheme.Warning));
            chips.Controls.Add(AiMetricCard("ПОСЛЕДНЯЯ ПРОВЕРКА", _aiLastChip, UiTheme.TextSecondary));
            chips.SizeChanged += (s, e) =>
            {
                var width = Math.Max(160, (chips.ClientSize.Width - 30) / 4);
                foreach (Control control in chips.Controls) control.Width = width;
            };

            var actions = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 56,
                MinimumSize = new Size(0, 56),
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                WrapContents = true,
                AutoScroll = true,
                Padding = new Padding(0, 6, 0, 6),
                BackColor = UiTheme.Background
            };
            actions.Controls.Add(Button("Проверить сейчас", async () => await RunAiWatcherAsync(), 148));
            actions.Controls.Add(Button("Настроить DeepSeek", () => ConfigureAi(), 168));
            actions.Controls.Add(Button("Автопроверка", () => StartAiWatcherLoop(), 132));
            actions.Controls.Add(Button("Остановить", () => StopAiWatcherLoop(), 116));
            actions.Controls.Add(Button("Что видит AI", async () => await PreviewAiEvidenceAsync(), 132));
            actions.Controls.Add(Button("Очистить историю", () => ClearAiHistory(), 142));
            actions.Controls.Add(Button("Обновить", () => RefreshAiFindings(), 102));
            _aiStatus.AutoSize = true;
            _aiStatus.ForeColor = UiTheme.TextMuted;
            _aiStatus.Font = UiTheme.BodySmall;
            _aiStatus.Margin = new Padding(14, 12, 0, 0);
            actions.Controls.Add(_aiStatus);

            var filters = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 56,
                MinimumSize = new Size(0, 56),
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                WrapContents = true,
                AutoScroll = true,
                Padding = new Padding(0, 4, 0, 4),
                BackColor = UiTheme.Background
            };
            filters.Controls.Add(FilterLabel("Срез"));
            _aiView.DropDownStyle = ComboBoxStyle.DropDownList;
            _aiView.Width = 170;
            _aiView.Items.AddRange(new object[] { "Отчёты AI", "Свежие ошибки", "История ошибок", "Журнал проверок" });
            _aiView.SelectedIndex = 0;
            UiTheme.StyleInput(_aiView);
            _aiView.SelectedIndexChanged += (s, e) => { if (!_aiLoading) { LoadAiView(); } };
            filters.Controls.Add(_aiView);
            filters.Controls.Add(FilterLabel("Уровень"));
            _aiSeverity.DropDownStyle = ComboBoxStyle.DropDownList;
            _aiSeverity.Width = 125;
            _aiSeverity.Items.Add("Все уровни");
            _aiSeverity.SelectedIndex = 0;
            UiTheme.StyleInput(_aiSeverity);
            _aiSeverity.SelectedIndexChanged += (s, e) => { if (!_aiLoading) ApplyAiFilters(); };
            filters.Controls.Add(_aiSeverity);
            filters.Controls.Add(FilterLabel("Категория"));
            _aiCategory.DropDownStyle = ComboBoxStyle.DropDownList;
            _aiCategory.Width = 190;
            _aiCategory.SelectedIndexChanged += (s, e) => { if (!_aiLoading) ApplyAiFilters(); };
            UiTheme.StyleInput(_aiCategory);
            filters.Controls.Add(_aiCategory);
            filters.Controls.Add(FilterLabel("Поиск"));
            _aiSearch.Width = 210;
            _aiSearch.Text = "";
            UiTheme.StyleInput(_aiSearch);
            UiTheme.SetPlaceholder(_aiSearch, "Поиск по тексту…");
            _aiSearch.TextChanged += (s, e) => { if (!_aiLoading) ApplyAiFilters(); };
            filters.Controls.Add(_aiSearch);
            filters.Controls.Add(Button("Сбросить", () => ResetAiFilters(), 105));

            ConfigureGrid(_aiGrid, new[] { "время", "уровень", "категория", "что обнаружено" });
            _aiGrid.MultiSelect = false;
            _aiGrid.Dock = DockStyle.Fill;
            _aiGrid.SelectionChanged += (s, e) =>
            {
                if (_aiGrid.SelectedRows.Count == 0) return;
                ShowAiDetails(_aiGrid.SelectedRows[0].Tag as Dictionary<string, object>);
            };
            _aiGrid.CellDoubleClick += (s, e) =>
            {
                if (e.RowIndex < 0) return;
                ShowAiDetails(_aiGrid.Rows[e.RowIndex].Tag as Dictionary<string, object>);
            };
            var leftHost = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Surface, Padding = new Padding(1) };
            leftHost.Controls.Add(_aiGrid);
            _aiEmptyPanel = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Surface, Visible = false };
            var emptyStack = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                BackColor = UiTheme.Surface,
                Margin = new Padding(0),
                Padding = new Padding(0)
            };
            var emptyIcon = new Label
            {
                Text = "◎",
                Font = new Font("Segoe UI Semibold", 28f),
                ForeColor = UiTheme.Accent,
                AutoSize = true,
                TextAlign = ContentAlignment.MiddleCenter,
                Margin = new Padding(0, 0, 0, 8)
            };
            var emptyTitle = new Label
            {
                Text = "Пока здесь тихо",
                Font = new Font("Segoe UI Semibold", 16f),
                ForeColor = UiTheme.Text,
                AutoSize = true,
                TextAlign = ContentAlignment.MiddleCenter,
                Margin = new Padding(0, 0, 0, 6)
            };
            var emptyText = new Label
            {
                Text = "Нет записей для выбранного среза и фильтров.\r\nЗапусти проверку или измени фильтры.",
                Font = UiTheme.Body,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                MaximumSize = new Size(520, 0),
                TextAlign = ContentAlignment.MiddleCenter,
                Margin = new Padding(0, 0, 0, 14)
            };
            var emptyButton = Button("Проверить сейчас", async () => await RunAiWatcherAsync(), 180);
            emptyButton.Margin = new Padding(0, 0, 0, 0);
            emptyStack.Controls.Add(emptyIcon);
            emptyStack.Controls.Add(emptyTitle);
            emptyStack.Controls.Add(emptyText);
            emptyStack.Controls.Add(emptyButton);
            _aiEmptyPanel.Controls.Add(emptyStack);
            _aiEmptyPanel.Resize += (s, e) =>
            {
                emptyStack.Location = new Point(
                    Math.Max(12, (_aiEmptyPanel.ClientSize.Width - emptyStack.Width) / 2),
                    Math.Max(12, (_aiEmptyPanel.ClientSize.Height - emptyStack.Height) / 2));
            };
            leftHost.Controls.Add(_aiEmptyPanel);
            _aiEmptyPanel.BringToFront();

            _aiDetail.Dock = DockStyle.Fill;
            _aiDetail.ReadOnly = true;
            _aiDetail.BorderStyle = BorderStyle.None;
            _aiDetail.BackColor = UiTheme.Surface;
            _aiDetail.ForeColor = UiTheme.Text;
            _aiDetail.Font = UiTheme.Body;
            _aiDetail.Text = "Здесь появится подробный разбор выбранной записи.";
            var detailPanel = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Surface, Padding = new Padding(18, 14, 18, 18) };
            UiTheme.StyleCard(detailPanel, 12);
            detailPanel.Controls.Add(_aiDetail);
            var detailTitle = new Label
            {
                Text = "Подробности",
                Dock = DockStyle.Top,
                Height = 30,
                Font = UiTheme.Semibold,
                ForeColor = UiTheme.TextSecondary,
                BackColor = UiTheme.Surface
            };
            detailPanel.Controls.Add(detailTitle);
            detailPanel.Controls.SetChildIndex(_aiDetail, 0);
            var content = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                RowCount = 1,
                BackColor = UiTheme.Background,
                Padding = new Padding(0)
            };
            content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 62));
            content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 38));
            content.Controls.Add(leftHost, 0, 0);
            content.Controls.Add(detailPanel, 1, 0);
            tab.Controls.Add(content);
            tab.Controls.Add(filters);
            tab.Controls.Add(actions);
            tab.Controls.Add(chips);
            tab.Controls.Add(header);
            _tabs.AddPage(tab);
            RefreshAiFindings();
        }

        private static Label FilterLabel(string text)
        {
            return new Label
            {
                Text = text,
                AutoSize = true,
                ForeColor = UiTheme.TextMuted,
                Font = UiTheme.BodySmall,
                Margin = new Padding(0, 12, 6, 0)
            };
        }

        private static Label AiMetricValue()
        {
            return new Label
            {
                Text = "—",
                Font = UiTheme.H3,
                AutoSize = false,
                Height = 32,
                Width = 150,
                ForeColor = UiTheme.Text,
                TextAlign = ContentAlignment.MiddleLeft
            };
        }

        private static Panel AiMetricCard(string title, Label value, Color accent)
        {
            var card = new Panel
            {
                Width = 222,
                Height = 94,
                Margin = new Padding(0, 0, 10, 0),
                BackColor = UiTheme.Surface,
                Padding = new Padding(16, 10, 12, 8)
            };
            UiTheme.StyleCard(card, 12);
            var dot = new Panel
            {
                Width = 8,
                Height = 8,
                BackColor = accent,
                Left = 16,
                Top = 16
            };
            UiTheme.Round(dot, 4);
            var caption = new Label
            {
                Text = title,
                Left = 30,
                Top = 10,
                Width = 174,
                Height = 24,
                ForeColor = UiTheme.TextMuted,
                Font = UiTheme.Micro,
                TextAlign = ContentAlignment.MiddleLeft
            };
            value.Left = 30;
            value.Top = 40;
            value.Height = 38;
            value.Width = 174;
            value.ForeColor = accent;
            card.Controls.Add(dot);
            card.Controls.Add(caption);
            card.Controls.Add(value);
            return card;
        }

        private void ShowAiDetails(Dictionary<string, object> item)
        {
            if (item == null) return;
            var serializer = new JavaScriptSerializer();
            var kind = ValueOf(item, "kind");
            var builder = new StringBuilder();
            if (kind == "finding")
            {
                builder.AppendLine(ValueOf(item, "title_text"));
                builder.AppendLine();
                builder.AppendLine("Уровень: " + ValueOf(item, "level"));
                builder.AppendLine("Категория: " + ValueOf(item, "display_category"));
                builder.AppendLine("Источник: " + ValueOf(item, "source_label"));
                builder.AppendLine("Время: " + ValueOf(item, "display_time"));
                if (!string.IsNullOrWhiteSpace(ValueOf(item, "issue_key")))
                {
                    builder.AppendLine("Проблема: " + ValueOf(item, "issue_key"));
                    builder.AppendLine("Впервые: " + ValueOf(item, "first_seen") + " · последнее наблюдение: " + ValueOf(item, "last_seen"));
                    builder.AppendLine("Прогонов с новыми данными: " + ValueOf(item, "observations") + " · уверенность: " + ValueOf(item, "confidence"));
                    builder.AppendLine("Уверенность в предполагаемой причине: " + ValueOf(item, "cause_confidence"));
                    if (ValueOf(item, "not_observed_in_latest_analysis").Equals("true", StringComparison.OrdinalIgnoreCase))
                        builder.AppendLine("В последнем разборе проблема не отмечена. Это не доказывает исправление; запись сохранена для проверки.");
                    if (ValueOf(item, "analysis_stale").Equals("true", StringComparison.OrdinalIgnoreCase))
                        builder.AppendLine("Пояснение AI относится к предыдущему срезу. Новые данные ещё не разобраны моделью.");
                }
                builder.AppendLine();
                builder.AppendLine("Что произошло");
                builder.AppendLine(ValueOf(item, "summary"));
                builder.AppendLine();
                builder.AppendLine("Рекомендация");
                builder.AppendLine(ValueOf(item, "recommendation"));
                var evidence = item.ContainsKey("evidence") ? serializer.Serialize(item["evidence"]) : "";
                if (!string.IsNullOrWhiteSpace(evidence))
                {
                    builder.AppendLine();
                    builder.AppendLine("Признаки");
                    builder.AppendLine(evidence);
                }
            }
            else if (kind == "error")
            {
                builder.AppendLine("Ошибка сервера");
                builder.AppendLine();
                builder.AppendLine("Время в логе: " + ValueOf(item, "display_time"));
                builder.AppendLine("Уровень: " + ValueOf(item, "level"));
                builder.AppendLine("Категория: " + ValueOf(item, "display_category"));
                builder.AppendLine("Модуль: " + ValueOf(item, "module"));
                builder.AppendLine("Источник: " + ValueOf(item, "source_label"));
                builder.AppendLine();
                builder.AppendLine("Строка лога");
                builder.AppendLine(ValueOf(item, "line"));
                builder.AppendLine();
                builder.AppendLine("Что делать");
                builder.AppendLine("Сопоставь время с рестартами, здоровьем PostgreSQL и диском. " +
                                   "Если это старая запись из истории и сейчас /health в порядке, считать её активной ошибкой не нужно.");
            }
            else
            {
                builder.AppendLine("Запуск AI-проверки");
                builder.AppendLine();
                builder.AppendLine("Время: " + ValueOf(item, "display_time"));
                builder.AppendLine("Режим: " + ValueOf(item, "method"));
                builder.AppendLine("Статус: " + ValueOf(item, "status_text"));
                builder.AppendLine("Причина пропуска: " + ValueOf(item, "skip_reason"));
                builder.AppendLine("Health: " + ValueOf(item, "health"));
                builder.AppendLine("Строк ошибок: " + ValueOf(item, "error_lines"));
                builder.AppendLine("Новых ошибок: " + ValueOf(item, "new_errors"));
                builder.AppendLine("Находок: " + ValueOf(item, "findings"));
                builder.AppendLine("Пропущено: " + ValueOf(item, "skipped"));
            }
            _aiDetail.Text = builder.ToString();
            _aiDetail.SelectionStart = 0;
            _aiDetail.ScrollToCaret();
        }

        private static string ValueOf(Dictionary<string, object> item, string key)
        {
            object value;
            return item != null && item.TryGetValue(key, out value) && value != null
                ? Convert.ToString(value)
                : "";
        }

        private void ConfigureAi()
        {
            using (var dialog = new AiSettingsDialog(AiSettingsStore.Load()))
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    AiSettingsStore.Save(dialog.Settings);
                    _aiStatus.Text = string.IsNullOrWhiteSpace(dialog.Settings.ApiKey)
                        ? "Ключ не задан — работает локальная проверка."
                        : "DeepSeek настроен. Нажми «Проверить сейчас».";
                }
                catch (Exception ex)
                {
                    OperationResultDialog.Show(this, "Настройка DeepSeek",
                        "Не удалось сохранить ключ в защищённое хранилище Windows.", ex.Message, false);
                }
            }
        }

        private static Dictionary<string, string> AiEnvironment()
        {
            var settings = AiSettingsStore.Load();
            var environment = new Dictionary<string, string>();
            if (!string.IsNullOrWhiteSpace(settings.ApiKey)) environment["DEEPSEEK_API_KEY"] = settings.ApiKey;
            if (!string.IsNullOrWhiteSpace(settings.Model)) environment["DEEPSEEK_MODEL"] = settings.Model;
            if (!string.IsNullOrWhiteSpace(settings.BaseUrl)) environment["DEEPSEEK_BASE_URL"] = settings.BaseUrl;
            environment["AI_WATCHER_INTERVAL_SECONDS"] = Math.Max(60, settings.IntervalSeconds).ToString();
            environment["AI_SCAN_SCOPE"] = settings.ScanAllLogs ? "all" : "current";
            environment["AI_LOG_LEVELS"] = settings.ScanAllLogs ? "error,warn,info" : "error,warn";
            environment["AI_INCLUDE_DATABASE"] = settings.IncludeDatabase ? "1" : "0";
            environment["AI_DATABASE_DUMP"] = settings.DatabaseDump ? "1" : "0";
            environment["AI_SEND_RAW_TO_API"] = settings.SendRawToApi ? "1" : "0";
            environment["AI_INCLUDE_AUDIT"] = settings.IncludeAudit ? "1" : "0";
            environment["AI_INCLUDE_DOCKER"] = settings.IncludeDocker ? "1" : "0";
            environment["AI_INCLUDE_PERFORMANCE"] = settings.IncludePerformance ? "1" : "0";
            return environment;
        }

        private void RefreshAiFindings()
        {
            LoadAiView();
        }

        private string AiViewKey()
        {
            switch (_aiView.SelectedIndex)
            {
                case 1: return "current_errors";
                case 2: return "history_errors";
                case 3: return "journal";
                default: return "findings";
            }
        }

        private void LoadAiView()
        {
            if (_aiGrid == null || _aiLoading) return;
            _aiLoading = true;
            try
            {
                _aiAllRows.Clear();
                var view = AiViewKey();
                if (view == "findings")
                {
                    foreach (var raw in ReadAiJsonLines(AppPaths.AiFindingsFile, 300))
                        _aiAllRows.Add(NormalizeAiFinding(raw));
                    foreach (var raw in ReadAiJsonLines(AppPaths.AiIssuesFile, 200))
                        _aiAllRows.Add(NormalizeAiFinding(raw));
                }
                else if (view == "current_errors" || view == "history_errors")
                {
                    var path = view == "current_errors" ? AppPaths.AiCurrentErrorsFile : AppPaths.AiErrorsFile;
                    var seenErrors = new HashSet<string>(StringComparer.Ordinal);
                    foreach (var raw in ReadAiJsonLines(path, 800))
                    {
                        var key = FirstValue(raw, "id", FirstValue(raw, "line", ""));
                        if (!string.IsNullOrWhiteSpace(key) && !seenErrors.Add(key)) continue;
                        _aiAllRows.Add(NormalizeAiError(raw, view == "current_errors" ? "Текущий запуск" : "История"));
                    }
                }
                else
                {
                    foreach (var raw in ReadAiJsonLines(AppPaths.AiJournalFile, 300))
                        _aiAllRows.Add(NormalizeAiJournal(raw));
                }
                _aiAllRows.Reverse();
                PopulateAiCategories(view);
                PopulateAiSeverities(view);
                ConfigureGrid(_aiGrid, view == "findings"
                    ? new[] { "время", "уровень", "категория", "источник", "что обнаружено" }
                    : view == "journal"
                        ? new[] { "время", "режим", "health", "статус", "ошибок", "новых", "находок" }
                        : new[] { "время", "уровень", "категория", "модуль", "сообщение" });
                _aiSeverity.Enabled = view != "journal";
                _aiCategory.Enabled = view != "journal";
                _aiSearch.Enabled = view != "journal";
                ApplyAiFilters();
            }
            catch (Exception ex)
            {
                _aiStatus.Text = "Не удалось прочитать наблюдения: " + ex.Message;
            }
            finally { _aiLoading = false; }
        }

        private static List<Dictionary<string, object>> ReadAiJsonLines(string path, int limit)
        {
            var result = new List<Dictionary<string, object>>();
            if (!File.Exists(path)) return result;
            var serializer = new JavaScriptSerializer();
            foreach (var line in File.ReadAllLines(path).Where(line => !string.IsNullOrWhiteSpace(line)).Reverse().Take(limit))
            {
                try
                {
                    var item = serializer.Deserialize<Dictionary<string, object>>(line);
                    if (item != null) result.Add(item);
                }
                catch { }
            }
            return result;
        }

        private static string CleanAiLine(string line)
        {
            return AiAnsi.Replace(line ?? "", "").Replace("\x00", "").Trim();
        }

        private static string AiLogTimeText(string line)
        {
            var match = AiLogTime.Match(line ?? "");
            return match.Success ? match.Groups[1].Value + " " + match.Groups[2].Value : "";
        }

        private static string FirstValue(Dictionary<string, object> item, string key, string fallback)
        {
            if (item != null && item.ContainsKey(key))
            {
                var value = Convert.ToString(item[key]);
                if (!string.IsNullOrWhiteSpace(value)) return value;
            }
            return fallback ?? "";
        }

        private static string ModuleFromLine(string line)
        {
            var module = AiModule.Match(line ?? "");
            return module.Success ? module.Groups[1].Value : "—";
        }

        private static string AiErrorLevel(string line)
        {
            var upper = (line ?? "").ToUpperInvariant();
            if (upper.Contains("PANIC")) return "PANIC";
            if (upper.Contains("ERROR")) return "ERROR";
            if (upper.Contains("WARN")) return "WARN";
            if (upper.Contains("INFO")) return "INFO";
            return "ERROR";
        }

        private static string AiErrorCategory(string line)
        {
            var lower = (line ?? "").ToLowerInvariant();
            if (lower.Contains("sqlx") || lower.Contains("postgres") || lower.Contains("database") || lower.Contains("pool")) return "База данных";
            if (lower.Contains("calls::") || lower.Contains("call sweep") || lower.Contains("ringing calls")) return "Звонки";
            if (lower.Contains("push") || lower.Contains("outbox")) return "Push";
            if (lower.Contains("presence")) return "Presence";
            if (lower.Contains("tls") || lower.Contains("ssl") || lower.Contains("https") || lower.Contains("socket") || lower.Contains("websocket") || lower.Contains("network")) return "Сеть/TLS";
            if (lower.Contains("auth") || lower.Contains("admin") || lower.Contains("security") || lower.Contains("permission")) return "Безопасность";
            if (lower.Contains("messenger_server::error")) return "Сервер";
            return "Другое";
        }

        private static string AiFindingCategory(string category)
        {
            switch ((category ?? "").ToLowerInvariant())
            {
                case "backend_health": return "Health backend";
                case "server_errors": return "Ошибки сервера";
                case "service_health": return "Health сервисов";
                case "admin_action_burst": return "Действия администратора";
                case "rapid_admin_actions": return "Серия админ-действий";
                case "rapid_reversal": return "Быстрые откаты";
                case "suspend_unsuspend_cycle": return "Блокировки и разблокировки";
                case "audit_data_quality": return "Качество аудита";
                case "audit_log_integrity": return "Целостность аудита";
                case "ai_api": return "DeepSeek API";
                case "ai_response": return "Ответ AI";
                default: return string.IsNullOrWhiteSpace(category) ? "Другое" : category;
            }
        }

        private static Dictionary<string, object> NormalizeAiFinding(Dictionary<string, object> raw)
        {
            var item = new Dictionary<string, object>();
            item["kind"] = "finding";
            item["display_time"] = FirstValue(raw, "observed_at", ValueOf(raw, "created_at"));
            item["level"] = ValueOf(raw, "severity");
            item["display_category"] = FirstValue(raw, "category_label", AiFindingCategory(ValueOf(raw, "category")));
            item["source_label"] = FirstValue(raw, "source_label", ValueOf(raw, "source") == "deepseek" ? "DeepSeek" : "Локальная проверка");
            item["title_text"] = ValueOf(raw, "title");
            item["summary"] = ValueOf(raw, "summary");
            foreach (var field in new[] { "issue_key", "first_seen", "last_seen", "observations", "confidence", "cause_confidence", "analysis_stale", "not_observed_in_latest_analysis" })
                item[field] = ValueOf(raw, field);
            item["recommendation"] = ValueOf(raw, "recommendation");
            item["evidence"] = raw.ContainsKey("evidence") ? raw["evidence"] : new object[0];
            return item;
        }

        private static Dictionary<string, object> NormalizeAiError(Dictionary<string, object> raw, string source)
        {
            var line = CleanAiLine(ValueOf(raw, "line"));
            var item = new Dictionary<string, object>();
            item["kind"] = "error";
            item["display_time"] = FirstValue(raw, "occurred_at", AiLogTimeText(line));
            if (string.IsNullOrWhiteSpace(Convert.ToString(item["display_time"]))) item["display_time"] = ValueOf(raw, "created_at");
            item["level"] = FirstValue(raw, "level", AiErrorLevel(line));
            item["display_category"] = FirstValue(raw, "category_label", AiErrorCategory(line));
            item["module"] = FirstValue(raw, "module", ModuleFromLine(line));
            item["source_label"] = FirstValue(raw, "source_label", source);
            item["fresh"] = ValueOf(raw, "fresh");
            item["age_seconds"] = ValueOf(raw, "age_seconds");
            item["line"] = line;
            return item;
        }

        private static Dictionary<string, object> NormalizeAiJournal(Dictionary<string, object> raw)
        {
            var item = new Dictionary<string, object>();
            item["kind"] = "journal";
            item["display_time"] = FirstValue(raw, "started_at", ValueOf(raw, "created_at"));
            item["method"] = ValueOf(raw, "mode") == "ai" ? "DeepSeek" : "Локально";
            var health = raw.ContainsKey("health") && raw["health"] != null
                ? new JavaScriptSerializer().Serialize(raw["health"])
                : "—";
            item["health"] = health.Length > 40 ? health.Substring(0, 40) : health;
            item["error_lines"] = ValueOf(raw, "error_lines_seen");
            item["new_errors"] = ValueOf(raw, "new_error_lines");
            item["findings"] = ValueOf(raw, "findings");
            item["skipped"] = ValueOf(raw, "skipped");
            item["status"] = FirstValue(raw, "status", ValueOf(raw, "skipped") == "true" ? "skipped" : "ok");
            item["skip_reason"] = ValueOf(raw, "skip_reason");
            item["status_text"] = ValueOf(item, "status") == "busy" ? "уже запущено"
                : ValueOf(item, "status") == "error" ? "ошибка"
                : ValueOf(item, "status") == "skipped" ? "без изменений" : "готово";
            return item;
        }

        private void PopulateAiCategories(string view)
        {
            var selected = Convert.ToString(_aiCategory.SelectedItem);
            _aiCategory.Items.Clear();
            _aiCategory.Items.Add("Все категории");
            if (view != "journal")
            {
                foreach (var value in _aiAllRows.Select(row => Convert.ToString(row["display_category"]))
                                             .Where(value => !string.IsNullOrWhiteSpace(value)).Distinct().OrderBy(value => value))
                    _aiCategory.Items.Add(value);
            }
            _aiCategory.SelectedItem = _aiCategory.Items.Contains(selected) ? selected : "Все категории";
        }

        private void PopulateAiSeverities(string view)
        {
            var selected = Convert.ToString(_aiSeverity.SelectedItem);
            _aiSeverity.Items.Clear();
            _aiSeverity.Items.Add("Все уровни");
            if (view != "journal")
            {
                foreach (var value in _aiAllRows.Select(row => Convert.ToString(row["level"]))
                                             .Where(value => !string.IsNullOrWhiteSpace(value)).Distinct().OrderBy(value => value))
                    _aiSeverity.Items.Add(value);
            }
            _aiSeverity.SelectedItem = _aiSeverity.Items.Contains(selected) ? selected : "Все уровни";
        }

        private void ResetAiFilters()
        {
            _aiLoading = true;
            try
            {
                _aiSeverity.SelectedIndex = 0;
                _aiCategory.SelectedItem = "Все категории";
                _aiSearch.Text = "";
                ApplyAiFilters();
            }
            finally { _aiLoading = false; }
        }

        private void UpdateAiChips()
        {
            if (_aiModeChip == null) return;
            var settings = AiSettingsStore.Load();
            var configured = !string.IsNullOrWhiteSpace(settings.ApiKey);
            _aiModeChip.Text = configured ? "Подключён" : "Локально";
            _aiModeChip.ForeColor = configured ? UiTheme.Success : UiTheme.Warning;
            var health = (_backend.Text ?? "").Trim();
            var healthOk = string.Equals(health, "online", StringComparison.OrdinalIgnoreCase);
            if (!healthOk && (string.IsNullOrWhiteSpace(health) || health == "—"))
            {
                try
                {
                    if (File.Exists(AppPaths.AiEvidenceFile))
                    {
                        var evidence = File.ReadAllText(AppPaths.AiEvidenceFile);
                        healthOk = evidence.IndexOf("\"status\": \"ok\"", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                   evidence.IndexOf("\"status\":\"ok\"", StringComparison.OrdinalIgnoreCase) >= 0;
                    }
                }
                catch { }
            }
            _aiHealthChip.Text = healthOk ? "Онлайн" : (string.IsNullOrWhiteSpace(health) || health == "—" ? "Неизвестно" : "Сбой");
            _aiHealthChip.ForeColor = healthOk ? UiTheme.Success : UiTheme.Danger;
            var freshCount = 0;
            try
            {
                if (File.Exists(AppPaths.AiCurrentErrorsFile))
                    freshCount = File.ReadAllLines(AppPaths.AiCurrentErrorsFile).Count(line => !string.IsNullOrWhiteSpace(line));
            }
            catch { }
            _aiFreshChip.Text = freshCount.ToString();
            _aiFreshChip.ForeColor = freshCount == 0 ? UiTheme.Success : UiTheme.Warning;
            var last = ReadAiJsonLines(AppPaths.AiJournalFile, 1).FirstOrDefault();
            if (last != null)
            {
                var time = ValueOf(last, "created_at");
                _aiLastChip.Text = time.Length >= 16 ? time.Substring(11, 5) : "—";
                _aiLastChip.ForeColor = UiTheme.TextSecondary;
            }
            else
            {
                _aiLastChip.Text = "—";
                _aiLastChip.ForeColor = UiTheme.TextMuted;
            }
        }

        private void ShowAiEmpty(bool show)
        {
            if (_aiEmptyPanel == null) return;
            _aiEmptyPanel.Visible = show;
            if (show) _aiEmptyPanel.BringToFront();
        }

        private void ApplyAiFilters()
        {
            if (_aiGrid == null) return;
            var view = AiViewKey();
            var severity = Convert.ToString(_aiSeverity.SelectedItem);
            var category = Convert.ToString(_aiCategory.SelectedItem);
            var search = (_aiSearch.Text ?? "").Trim().ToLowerInvariant();
            var shown = 0;
            _aiGrid.Rows.Clear();
            foreach (var item in _aiAllRows)
            {
                var level = Convert.ToString(item["level"]);
                if (view != "journal" && severity != "Все уровни" && severity != null &&
                    !string.Equals(level, severity, StringComparison.OrdinalIgnoreCase)) continue;
                var rowCategory = Convert.ToString(item["display_category"]);
                if (view != "journal" && category != "Все категории" && category != null &&
                    !string.Equals(rowCategory, category, StringComparison.OrdinalIgnoreCase)) continue;
                var haystack = (ValueOf(item, "title_text") + " " + ValueOf(item, "summary") + " " +
                                ValueOf(item, "line") + " " + ValueOf(item, "module")).ToLowerInvariant();
                if (search.Length > 0 && haystack.IndexOf(search, StringComparison.Ordinal) < 0) continue;
                DataGridViewRow row;
                if (view == "journal")
                    row = _aiGrid.Rows[_aiGrid.Rows.Add(ValueOf(item, "display_time"), ValueOf(item, "method"),
                        ValueOf(item, "health"), ValueOf(item, "status_text"), ValueOf(item, "error_lines"),
                        ValueOf(item, "new_errors"), ValueOf(item, "findings"))];
                else if (view == "findings")
                    row = _aiGrid.Rows[_aiGrid.Rows.Add(ValueOf(item, "display_time"), level,
                        rowCategory, ValueOf(item, "source_label"), ValueOf(item, "title_text"))];
                else
                    row = _aiGrid.Rows[_aiGrid.Rows.Add(ValueOf(item, "display_time"), level,
                        rowCategory, ValueOf(item, "module"), ValueOf(item, "line"))];
                row.Tag = item;
                shown++;
            }
            if (_aiGrid.Rows.Count > 0)
            {
                _aiGrid.Rows[0].Selected = true;
                _aiGrid.CurrentCell = _aiGrid.Rows[0].Cells[0];
                ShowAiDetails(_aiGrid.Rows[0].Tag as Dictionary<string, object>);
            }
            else
            {
                _aiDetail.Text = _aiAllRows.Count == 0
                    ? "Здесь пока нет данных. Нажми «Проверить сейчас» или включи «Автопроверку»."
                    : "Под текущие фильтры ничего не подходит. Нажми «Сбросить фильтры».";
            }
            _aiStatus.Text = "Показано " + shown + " из " + _aiAllRows.Count +
                             " · DeepSeek: " + (AiSettingsStore.IsConfigured ? "подключён" : "локальный режим");
            ShowAiEmpty(shown == 0);
            UpdateAiChips();
        }

        private async Task RunAiWatcherAsync()
        {
            if (_operationBusy) return;
            if (!File.Exists(AppPaths.AiWatcherScript))
            {
                OperationResultDialog.Show(this, "AI-наблюдение",
                    "Не найден watcher: " + AppPaths.AiWatcherScript, "", false);
                return;
            }
            _operationBusy = true;
            try
            {
                _aiStatus.Text = "Проверка...";
                var script = "\"" + AppPaths.AiWatcherScript + "\" --once";
                var environment = AiEnvironment();
                var result = await ProcessRunner.RunAsync("python", script, AppPaths.Workspace, 180000, null, environment);
                if (!result.Ok && result.ExitCode == 2)
                {
                    RefreshAiFindings();
                    OperationResultDialog.Show(this, "AI-наблюдение",
                        "Проверка уже выполняется другим процессом.", result.Combined, true);
                    return;
                }
                if (!result.Ok)
                {
                    var py = await ProcessRunner.RunAsync("py", "-3 " + script, AppPaths.Workspace, 180000, null, environment);
                    if (py.Ok) result = py;
                }
                RefreshAiFindings();
                var output = result.Combined;
                if (output.Length > 8000) output = output.Substring(output.Length - 8000);
                OperationResultDialog.Show(this, "AI-наблюдение",
                    result.Ok ? "Проверка завершена. Найдено записей: " + _aiGrid.Rows.Count + "."
                              : "Watcher завершился с ошибкой.",
                    output, result.Ok);
            }
            finally { _operationBusy = false; }
        }

        private void StartAiWatcherLoop()
        {
            if (_aiWatcherLoop != null && !_aiWatcherLoop.HasExited)
            {
                _aiStatus.Text = "Автопроверка уже запущена.";
                return;
            }
            if (!File.Exists(AppPaths.AiWatcherScript))
            {
                OperationResultDialog.Show(this, "AI-наблюдение",
                    "Не найден watcher: " + AppPaths.AiWatcherScript, "", false);
                return;
            }
            var startInfo = new ProcessStartInfo
            {
                FileName = "python",
                Arguments = "\"" + AppPaths.AiWatcherScript + "\" --loop",
                WorkingDirectory = AppPaths.Workspace,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            foreach (var pair in AiEnvironment())
                startInfo.EnvironmentVariables[pair.Key] = pair.Value;
            try
            {
                _aiWatcherLoop = Process.Start(startInfo);
            }
            catch
            {
                try
                {
                    startInfo.FileName = "py";
                    startInfo.Arguments = "-3 \"" + AppPaths.AiWatcherScript + "\" --loop";
                    _aiWatcherLoop = Process.Start(startInfo);
                }
                catch (Exception ex)
                {
                    OperationResultDialog.Show(this, "AI-наблюдение", "Не удалось запустить watcher.", ex.Message, false);
                    return;
                }
            }
            if (_aiWatcherLoop == null)
            {
                _aiStatus.Text = "Watcher не запустился.";
                return;
            }
            _aiWatcherLoop.EnableRaisingEvents = true;
            _aiWatcherLoop.Exited += (s, e) => BeginInvoke((Action)(() =>
            {
                _aiStatus.Text = "Автопроверка остановлена.";
                RefreshAiFindings();
            }));
            _aiStatus.Text = "Автопроверка запущена; интервал — из ai-watcher.json.";
        }

        private void ClearAiHistory()
        {
            if (MessageBox.Show(this,
                "Очистить историю AI-наблюдения? Найденные отчёты, старые ошибки и журнал будут удалены. " +
                "Настройки DeepSeek не удаляются.",
                "Очистка истории AI", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            var files = new[]
            {
                AppPaths.AiFindingsFile, AppPaths.AiErrorsFile, AppPaths.AiCurrentErrorsFile,
                AppPaths.AiJournalFile, AppPaths.AiEvidenceFile, AppPaths.AiStateFile,
                AppPaths.AiDatabaseIndexFile, AppPaths.AiDatabaseDumpFile
            };
            var errors = new List<string>();
            foreach (var file in files)
            {
                try { if (File.Exists(file)) File.Delete(file); }
                catch (Exception ex) { errors.Add(Path.GetFileName(file) + ": " + ex.Message); }
            }
            RefreshAiFindings();
            if (errors.Count == 0)
                OperationResultDialog.Show(this, "История AI", "История наблюдения очищена.", "", true);
            else
                OperationResultDialog.Show(this, "История AI", "Часть файлов не удалена:",
                    string.Join(Environment.NewLine, errors), false);
        }

        private void ShowAiFile(string path, string title)
        {
            try
            {
                if (!File.Exists(path))
                {
                    OperationResultDialog.Show(this, title, "Файл пока не создан.", path, false);
                    return;
                }
                var lines = File.ReadAllLines(path);
                var tail = lines.Skip(Math.Max(0, lines.Length - 300));
                var text = string.Join(Environment.NewLine, tail);
                if (text.Length > 20000) text = text.Substring(text.Length - 20000);
                OperationResultDialog.Show(this, title,
                    "Показаны последние " + Math.Min(300, lines.Length) + " записей. Файл: " + path,
                    text, true);
            }
            catch (Exception ex)
            {
                OperationResultDialog.Show(this, title, "Не удалось прочитать файл.", ex.Message, false);
            }
        }

        private async Task PreviewAiEvidenceAsync()
        {
            if (_operationBusy) return;
            if (!File.Exists(AppPaths.AiWatcherScript))
            {
                OperationResultDialog.Show(this, "AI-наблюдение",
                    "Не найден watcher: " + AppPaths.AiWatcherScript, "", false);
                return;
            }
            _operationBusy = true;
            try
            {
                var script = "\"" + AppPaths.AiWatcherScript + "\" --preview";
                var environment = AiEnvironment();
                var result = await ProcessRunner.RunAsync("python", script, AppPaths.Workspace, 60000, null, environment);
                if (!result.Ok)
                {
                    var py = await ProcessRunner.RunAsync("py", "-3 " + script, AppPaths.Workspace, 60000, null, environment);
                    if (py.Ok) result = py;
                }
                var text = result.Combined;
                if (text.Length > 12000) text = text.Substring(text.Length - 12000);
                OperationResultDialog.Show(this, "Что видит AI",
                    "Ниже — уже отредактированный срез, который может уйти в DeepSeek. " +
                    "Сырые токены, пароли, ключи, email и телефоны вырезаны.",
                    text.Length == 0 ? "Watcher не вернул данных." : text,
                    true);
            }
            finally { _operationBusy = false; }
        }

        private void StopAiWatcherLoop()
        {
            if (_aiWatcherLoop == null || _aiWatcherLoop.HasExited)
            {
                _aiStatus.Text = "Автопроверка не запущена.";
                return;
            }
            try { _aiWatcherLoop.Kill(); }
            catch (Exception ex)
            {
                OperationResultDialog.Show(this, "AI-наблюдение", "Не удалось остановить watcher.", ex.Message, false);
                return;
            }
            _aiStatus.Text = "Автопроверка остановлена.";
        }

        private async Task RefreshAllAsync()
        {
            if (_busy) { _refreshPending = true; return; }
            var selected = _tabs.SelectedIndex;
            var auth = _auth;
            _busy = true;
            _refreshErrors.Clear();
            _message.Text = "Обновление...";
            try
            {
                // Never poll a local database merely because a remote
                // administrator opened the app. Each workspace owns its data.
                if (selected == 0)
                {
                    if (auth == null) { _message.Text = "Войдите для управления выбранным сервером"; return; }
                    await _adminPanel.RefreshAllAsync();
                    if (ReferenceEquals(auth, _auth) && _tabs.SelectedIndex == selected)
                        _message.Text = "Сервер: " + AdminServerTarget.Display(auth.BaseUrl);
                    return;
                }
                if (selected == 1) await Task.WhenAll(RefreshOverviewAsync(), RefreshHealthOnlyAsync());
                else if (selected == 2) await RefreshUsersAsync();
                else if (selected == 3) await RefreshReportsAsync();
                else if (selected == 4) await RefreshFeedbackAsync();
                else if (selected == 5) await RefreshDependenciesAsync();
                else if (selected == 6) await RefreshLogsAsync();
                else if (selected == 7) RefreshAiFindings();
                if (_tabs.SelectedIndex != selected || !ReferenceEquals(auth, _auth)) return;
                _message.Text = _refreshErrors.Count == 0
                    ? "Этот ПК · обновлено " + DateTime.Now.ToString("HH:mm:ss")
                    : string.Join("; ", _refreshErrors.Distinct());
            }
            catch (Exception ex)
            {
                if (_tabs.SelectedIndex == selected && ReferenceEquals(auth, _auth))
                    _message.Text = "Ошибка обновления: " + ex.Message;
            }
            finally
            {
                _busy = false;
                if (_refreshPending && IsHandleCreated && !IsDisposed)
                {
                    _refreshPending = false;
                    BeginInvoke(new Action(async () => await RefreshAllAsync()));
                }
            }
        }

        private async Task RefreshOverviewAsync()
        {
            var requestVersion = ++_overviewRequestVersion;
            var stats = await DbReader.LoadOverviewAsync();
            if (requestVersion != _overviewRequestVersion || IsDisposed) return;
            if (!stats.DbOk)
            {
                _usersTotal.Text = "—";
                _usersOnline.Text = "—";
                _reports.Text = "—";
                _feedback.Text = "—";
                _diagnostics.Text = "—";
                _message.Text = "БД: " + (string.IsNullOrWhiteSpace(stats.Error) ? "нет данных" : stats.Error);
                _refreshErrors.Add("PostgreSQL: " + (string.IsNullOrWhiteSpace(stats.Error) ? "нет данных" : stats.Error));
                return;
            }
            _usersTotal.Text = stats.Users.ToString();
            _usersOnline.Text = stats.RecentlyActive.ToString();
            _reports.Text = stats.OpenReports.ToString();
            _feedback.Text = stats.OpenFeedback.ToString();
            _diagnostics.Text = stats.NewDiagnostics < 0 ? "В Redis" : stats.NewDiagnostics.ToString();
        }

        private async Task RefreshHealthOnlyAsync()
        {
            if (_healthBusy || IsDisposed) return;
            _healthBusy = true;
            try {
            if (_tabs.SelectedIndex == 0)
            {
                var auth = _auth;
                if (auth == null) return;
                try
                {
                    var remoteHealth = await auth.SendAsync(System.Net.Http.HttpMethod.Get, "health", null, false, false);
                    if (!ReferenceEquals(auth, _auth) || _tabs.SelectedIndex != 0 || IsDisposed) return;
                    object status;
                    var ok = remoteHealth.TryGetValue("status", out status) && string.Equals(Convert.ToString(status), "ok", StringComparison.OrdinalIgnoreCase);
                    _message.Text = (ok ? "На связи · " : "Проверьте состояние · ") + AdminServerTarget.Display(auth.BaseUrl);
                }
                catch (Exception ex)
                {
                    if (ReferenceEquals(auth, _auth) && _tabs.SelectedIndex == 0 && !IsDisposed)
                        _message.Text = "Выбранный сервер недоступен: " + ex.Message;
                }
                return;
            }
            if (_tabs.SelectedIndex != 1) return;
            var health = await DependencyChecker.BackendHealthAsync();
            SetStatus(_backend, health.Ok,
                health.Ok ? "online" : (health.Status.Length > 0 ? health.Status : "offline"));
            var docker = await ProcessRunner.DockerAsync("info --format {{.ServerVersion}}", 5000);
            SetStatus(_docker, docker.Ok, docker.Ok ? docker.StdOut.Trim() : "нет Docker");
            } finally { _healthBusy = false; }
        }

        private async Task RefreshUsersAsync()
        {
            var requestVersion = ++_usersRequestVersion;
            var result = await DbReader.LoadUsersAsync(_search.Text);
            if (requestVersion != _usersRequestVersion || IsDisposed) return;
            _usersGrid.Rows.Clear();
            if (!result.Ok)
            {
                _userCount.Text = "нет данных";
                _usersFooter.ForeColor = UiTheme.Danger;
                _usersFooter.Text = result.Error;
                return;
            }
            foreach (var row in result.Rows) _usersGrid.Rows.Add(Pad(row, 4));
            _userCount.Text = result.Rows.Count + " записей";
            _usersFooter.ForeColor = UiTheme.TextMuted;
            _usersFooter.Text = result.Rows.Count + " записей · онлайн = активность за 2 минуты";
        }

        private async Task RefreshReportsAsync()
        {
            var requestVersion = ++_reportsRequestVersion;
            var result = await DbReader.LoadReportsAsync();
            if (requestVersion != _reportsRequestVersion || IsDisposed) return;
            _reportsGrid.Rows.Clear();
            if (!result.Ok) { _reportCount.Text = "нет данных"; _reportsFooter.ForeColor = UiTheme.Danger; _reportsFooter.Text = result.Error; return; }
            foreach (var row in result.Rows) _reportsGrid.Rows.Add(Pad(row, 4));
            _reportCount.Text = result.Rows.Count + " жалоб";
            _reportsFooter.ForeColor = UiTheme.TextMuted;
            _reportsFooter.Text = "Показаны последние " + result.Rows.Count + " жалоб из локального PostgreSQL";
        }

        private async Task RefreshFeedbackAsync()
        {
            var requestVersion = ++_feedbackRequestVersion;
            var result = await DbReader.LoadFeedbackAsync();
            if (requestVersion != _feedbackRequestVersion || IsDisposed) return;
            _feedbackGrid.Rows.Clear();
            if (!result.Ok) { _feedbackCount.Text = "нет данных"; _feedbackFooter.ForeColor = UiTheme.Danger; _feedbackFooter.Text = result.Error; return; }
            foreach (var row in result.Rows) _feedbackGrid.Rows.Add(Pad(row, 5));
            _feedbackCount.Text = result.Rows.Count + " обращений";
            _feedbackFooter.ForeColor = UiTheme.TextMuted;
            _feedbackFooter.Text = "Показаны последние " + result.Rows.Count + " обращений из локального PostgreSQL";
        }

        private async Task RefreshDependenciesAsync()
        {
            var requestVersion = ++_dependenciesRequestVersion;
            var items = await DependencyChecker.CheckAllAsync();
            if (requestVersion != _dependenciesRequestVersion || IsDisposed) return;
            _depsGrid.Rows.Clear();
            foreach (var item in items)
            {
                _depsGrid.Rows.Add(item.Name, item.Status, item.Detail, ActionLabel(item.Action));
            }
            var unavailable = items.Where(i => !i.Ok && i.Name != "Права администратора")
                .Select(i => i.Name).ToArray();
            var needsElevation = items.Any(i => !i.Ok && i.Name == "Права администратора");
            if (_busy && unavailable.Length > 0)
                _refreshErrors.Add("Не пройдены проверки: " + string.Join(", ", unavailable.Take(2)) +
                    (unavailable.Length > 2 ? " и ещё " + (unavailable.Length - 2) : ""));
            if (_busy && needsElevation)
                _refreshErrors.Add("Перезапуск сервисов требует прав администратора");
            _message.Text = unavailable.Length == 0
                ? needsElevation ? "Мониторинг доступен · перезапуск требует прав администратора" : "Все проверки пройдены"
                : "Не пройдены проверки: " + string.Join(", ", unavailable.Take(2)) +
                    (unavailable.Length > 2 ? " и ещё " + (unavailable.Length - 2) : "");
            _depsFooter.ForeColor = unavailable.Length == 0 ? UiTheme.TextMuted : UiTheme.Warning;
            _depsFooter.Text = unavailable.Length == 0
                ? "Проверено компонентов: " + items.Count + (needsElevation ? " · запуск сервисов требует прав администратора" : "")
                : "Не пройдено: " + string.Join(", ", unavailable.Take(4));
        }

        private async Task RefreshLogsAsync()
        {
            var requestVersion = ++_logsRequestVersion;
            var text = await Task.Run(() => ServerControl.LatestServerLog(_logErrors));
            if (requestVersion != _logsRequestVersion || IsDisposed) return;
            var visibleText = AiAnsi.Replace(text ?? "", "").Replace("\x00", "").TrimEnd();
            _log.Text = visibleText;
            _log.SelectAll();
            _log.SelectionColor = UiTheme.TextSecondary;
            _log.SelectionFont = UiTheme.Mono;
            _log.SelectionLength = 0;
            _log.SelectionStart = Math.Max(0, _log.TextLength - 1);
            _log.ScrollToCaret();
            _log.Invalidate();
            _logsFooter.ForeColor = UiTheme.TextMuted;
            _logsFooter.Text = (_logErrors ? "Ошибки" : "События") + " · символов: " + visibleText.Length + " · " + DateTime.Now.ToString("HH:mm:ss");
        }

        private async Task StartStackAsync(bool build, bool restart)
        {
            if (_operationBusy) return;
            var text = build ? "Пересобрать backend и перезапустить весь стек?" : "Запустить существующий стек?";
            if (MessageBox.Show(this, text + "\n\nДанные, сертификаты и .env не изменяются.", "UNYRI Admin",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            _operationBusy = true;
            try {
            var checks = await DependencyChecker.CheckAllAsync();
            var missing = new List<Tuple<string, Func<Task<CommandResult>>>>();
            var docker = checks.FirstOrDefault(item => item.Name == "Docker Engine");
            if (docker != null && docker.Action == "install-docker")
                missing.Add(Tuple.Create("Docker Desktop", (Func<Task<CommandResult>>)ServerControl.StartDockerInstall));
            var git = checks.FirstOrDefault(item => item.Name == "Git");
            if (git != null && !git.Ok)
                missing.Add(Tuple.Create("Git for Windows", (Func<Task<CommandResult>>)ServerControl.StartGitInstall));
            var cargo = checks.FirstOrDefault(item => item.Name == "Rust/Cargo");
            var backendBinary = checks.FirstOrDefault(item => item.Name == "Backend binary");
            if (cargo != null && !cargo.Ok && (build || (backendBinary != null && !backendBinary.Ok)))
                missing.Add(Tuple.Create("Rust/Cargo", (Func<Task<CommandResult>>)ServerControl.StartRustInstall));

            if (missing.Count > 0)
            {
                var names = string.Join(", ", missing.Select(item => item.Item1));
                if (MessageBox.Show(this,
                    "Для запуска не найдены обязательные инструменты: " + names +
                    ". UNYRI Admin установит их через winget, затем продолжит запуск. " +
                    "Установщики могут запросить права администратора.",
                    "Подготовка компьютера", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                foreach (var dependency in missing)
                {
                    _message.Text = "Установка " + dependency.Item1 + "...";
                    var install = await dependency.Item2();
                    if (!install.Ok)
                    {
                        OperationResultDialog.Show(this, "Подготовка компьютера",
                            OperationResultDialog.DescribeFailure(install, dependency.Item1 + " не установлен."),
                            install.Combined, false);
                        return;
                    }
                }
                ServerControl.RefreshProcessPath();
                checks = await DependencyChecker.CheckAllAsync();
                var missingGit = checks.FirstOrDefault(item => item.Name == "Git");
                var missingCargo = checks.FirstOrDefault(item => item.Name == "Rust/Cargo");
                if ((missingGit != null && !missingGit.Ok) ||
                    ((build || (backendBinary != null && !backendBinary.Ok)) && missingCargo != null && !missingCargo.Ok))
                {
                    MessageBox.Show(this,
                        "Инструменты установлены, но ещё не доступны приложению. Перезапустите UNYRI Admin и повторите запуск.",
                        "Требуется перезапуск приложения", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
            }
            _message.Text = "Подготовка Docker...";
            var preparation = await ServerControl.PrepareDockerAsync();
            if (!preparation.Ok)
            {
                _message.Text = "Не удалось подготовить Docker";
                OperationResultDialog.Show(this, "Подготовка Docker",
                    OperationResultDialog.DescribeFailure(preparation, "Подготовка Docker"),
                    preparation.Combined, false);
                return;
            }
            _message.Text = "Проверка проекта контейнеров...";
            var ownership = await ServerControl.CheckStackOwnershipAsync();
            string confirmedWorkspace = null;
            if (!ownership.Safe && string.IsNullOrWhiteSpace(ownership.ActualWorkspace))
                throw new InvalidOperationException(ownership.Detail);
            if (!ownership.Safe && !string.IsNullOrWhiteSpace(ownership.ActualWorkspace))
            {
                if (MessageBox.Show(this, "Контейнеры созданы проектом " + ownership.ActualWorkspace +
                    ". Запустить существующие контейнеры с сохранением данных для backend " + AppPaths.Workspace + "?",
                    "Подтвердите проект контейнеров", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                confirmedWorkspace = ownership.ActualWorkspace;
            }
            _message.Text = "Запуск...";
            var result = await ServerControl.StartStackAsync(build, restart, confirmedWorkspace);
            _message.Text = result.Ok ? "Команда завершена" : "Ошибка запуска";
            OperationResultDialog.Show(this, "Запуск сервера",
                result.Ok ? CommandSuccessSummary(result, "Команда запуска завершена.")
                    : OperationResultDialog.DescribeFailure(result, "Запуск сервера"),
                result.Combined, result.Ok);
            await RefreshAllAsync();
            } finally { _operationBusy = false; }
        }

        private async Task StopStackAsync()
        {
            if (_operationBusy) return;
            _operationBusy = true;
            try
            {
                var ownership = await ServerControl.CheckStackOwnershipAsync();
                var containerWorkspace = string.IsNullOrWhiteSpace(ownership.ActualWorkspace)
                    ? AppPaths.Workspace : ownership.ActualWorkspace;
                var prompt = "Будут остановлены backend, HTTPS-прокси, PostgreSQL, Redis, MinIO и coturn. " +
                    "Все клиенты потеряют соединение. База данных и файлы сохранятся.\n\n" +
                    "Проект контейнеров: " + containerWorkspace + "\n" +
                    "Backend: " + AppPaths.Workspace + "\n\nОстановить сервер полностью?";
                if (MessageBox.Show(this, prompt, "Полная остановка UNYRI", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning) != DialogResult.Yes) return;
                _message.Text = "Остановка сервера и контейнеров...";
                StopAiWatcherLoop();
                var result = await ServerControl.StopStackAsync(containerWorkspace);
                _message.Text = result.Ok ? "Сервер полностью остановлен" : "Остановка завершилась с ошибкой";
                OperationResultDialog.Show(this, "Остановка сервера",
                    result.Ok ? "UNYRI остановлен. База данных и файлы сохранены."
                        : OperationResultDialog.DescribeFailure(result, "Остановка сервера"), result.Combined, result.Ok);
                await RefreshAllAsync();
            }
            finally { _operationBusy = false; }
        }

        private async Task CheckUpdatesAsync()
        {
            if (_operationBusy) return;
            _operationBusy = true;
            try
            {
                _message.Text = "Проверка доступных обновлений...";
                var output = await ServerControl.CheckUpdatesAsync();
                if (output.Length > 12000) output = output.Substring(output.Length - 12000);
                MessageBox.Show(this, output, "Проверка версий и обновлений", MessageBoxButtons.OK, MessageBoxIcon.Information);
                _message.Text = "Проверка обновлений завершена";
            }
            finally { _operationBusy = false; }
        }

        private async Task UpdateToolsAsync()
        {
            if (_operationBusy) return;
            if (MessageBox.Show(this,
                "Будут обновлены только Docker Desktop, Git и стабильный Rust toolchain через winget/rustup.\n\n" +
                "Установщики могут запросить права администратора и перезапустить Docker, временно остановив сервер. " +
                "Базы данных и Docker-образы сервисов не обновляются.",
                "Обновление инструментов сервера", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            _operationBusy = true;
            try
            {
                _message.Text = "Обновление инструментов...";
                var output = await ServerControl.UpdateToolsAsync();
                if (output.Length > 12000) output = output.Substring(output.Length - 12000);
                MessageBox.Show(this, output, "Обновление инструментов", MessageBoxButtons.OK, MessageBoxIcon.Information);
                await RefreshDependenciesAsync();
            }
            finally { _operationBusy = false; }
        }

        private async Task FixSelectedDependencyAsync()
        {
            if (_operationBusy) return;
            if (_depsGrid.SelectedRows.Count == 0)
            {
                MessageBox.Show(this, "Выбери строку в таблице.", "UNYRI Admin", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            var action = ActionKey(_depsGrid.SelectedRows[0].Cells[3].Value == null ? "" : _depsGrid.SelectedRows[0].Cells[3].Value.ToString());
            try
            {
                switch (action)
                {
                    case "open-docker":
                        ServerControl.OpenDockerDesktop();
                        _message.Text = "Docker Desktop открывается. Повторите проверку после запуска Engine.";
                        return;
                    case "install-docker":
                        if (MessageBox.Show(this,
                            "Будет запущен winget для установки Docker Desktop. Продолжить?",
                            "Установка Docker Desktop", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                        await RunExternalOperationAsync("Установка Docker Desktop...",
                            ServerControl.StartDockerInstall, "Docker Desktop установлен/уже установлен", "Docker Desktop");
                        break;
                    case "install-rust":
                        if (MessageBox.Show(this, "Будет установлен официальный Rustup через winget. Перезапустите приложение после установки, чтобы обновить PATH. Продолжить?",
                                "Установка Rust/Cargo", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                        await RunExternalOperationAsync("Установка Rust/Cargo...",
                            ServerControl.StartRustInstall, "Rustup установлен. Перезапустите приложение для обновления PATH.", "Rust/Cargo");
                        break;
                    case "install-git":
                        if (MessageBox.Show(this, "Будет установлен Git for Windows через winget. Перезапустите приложение после установки, чтобы обновить PATH. Продолжить?",
                                "Установка Git", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                        await RunExternalOperationAsync("Установка Git...",
                            ServerControl.StartGitInstall, "Git установлен. Перезапустите приложение для обновления PATH.", "Git");
                        break;
                    case "build-server":
                        if (MessageBox.Show(this, "Собрать backend offline (cargo build --offline)?", "UNYRI Admin",
                                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                        await RunExternalOperationAsync("Сборка backend...",
                            ServerControl.StartCargoBuildAsync, "Backend собран", "Backend");
                        break;
                    case "start-stack":
                        await StartStackAsync(false, false);
                        break;
                    case "https":
                        if (MessageBox.Show(this, "Запустить проверку/настройку HTTPS Caddy?", "UNYRI Admin",
                                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                        await RunExternalOperationAsync("Настройка HTTPS...",
                            ServerControl.StartHttpsAsync, "HTTPS-команда завершена", "HTTPS");
                        break;
                    case "open-workspace":
                        ServerControl.OpenWorkspace();
                        break;
                    default:
                        MessageBox.Show(this, "Для этого пункта автоматическое действие не требуется. Подробности указаны в таблице.");
                        return;
                }
                await RefreshDependenciesAsync();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "Ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private async Task RunExternalOperationAsync(string status, Func<Task<CommandResult>> operation, string successMessage, string title)
        {
            if (_operationBusy) return;
            _operationBusy = true;
            try
            {
                _message.Text = status;
                var result = await operation();
                _message.Text = result.Ok ? successMessage : "Ошибка: " + title;
                OperationResultDialog.Show(this, title,
                    result.Ok ? CommandSuccessSummary(result, successMessage)
                        : OperationResultDialog.DescribeFailure(result, title),
                    result.Combined, result.Ok);
            }
            finally
            {
                _operationBusy = false;
            }
        }

        private static string CommandSuccessSummary(CommandResult result, string fallback)
        {
            var firstLine = (result == null ? "" : result.StdOut ?? "")
                .Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries)
                .Select(line => line.Trim())
                .FirstOrDefault(line => line.StartsWith("COCO ready:", StringComparison.OrdinalIgnoreCase) ||
                                        line.StartsWith("A healthy API", StringComparison.OrdinalIgnoreCase) ||
                                        line.StartsWith("COCO is already running", StringComparison.OrdinalIgnoreCase));
            if (string.IsNullOrWhiteSpace(firstLine)) firstLine = fallback;
            return firstLine + Environment.NewLine + "Технический вывод доступен в разделе «Подробности».";
        }

        private static string ActionLabel(string action)
        {
            if (action == "install-docker") return "Установить Docker Desktop";
            if (action == "install-rust") return "Установить Rust/Cargo";
            if (action == "install-git") return "Установить Git";
            if (action == "open-docker") return "Открыть Docker Desktop";
            if (action == "build-server") return "Собрать backend";
            if (action == "start-stack") return "Запустить стек";
            if (action == "https") return "Настроить HTTPS";
            if (action == "open-workspace") return "Открыть проект";
            return "";
        }

        private static string ActionKey(string label)
        {
            if (label == "Установить Docker Desktop") return "install-docker";
            if (label == "Установить Rust/Cargo") return "install-rust";
            if (label == "Установить Git") return "install-git";
            if (label == "Открыть Docker Desktop") return "open-docker";
            if (label == "Собрать backend") return "build-server";
            if (label == "Запустить стек") return "start-stack";
            if (label == "Настроить HTTPS") return "https";
            if (label == "Открыть проект") return "open-workspace";
            return "";
        }

        private static Panel StatCard(string title, Label value)
        {
            var panel = new Panel
            {
                Margin = new Padding(6, 6, 6, 6),
                Padding = new Padding(18, 14, 16, 12),
                Dock = DockStyle.None
            };
            UiTheme.StyleCard(panel, 14);
            value.Text = "—";
            value.Font = UiTheme.Kpi;
            value.ForeColor = UiTheme.Text;
            value.AutoSize = true;
            value.Location = new Point(18, 38);
            var titleLabel = new Label
            {
                Text = title,
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextSecondary,
                AutoSize = true,
                Location = new Point(18, 15)
            };
            var glyph = title == "Backend" ? IconFactory.Admin :
                        title == "Docker Engine" ? IconFactory.Dependencies :
                        title == "Активны за 2 минуты" ? IconFactory.Users :
                        title == "Хранилище диагностики" ? IconFactory.Logs :
                        IconFactory.GlyphForText(title) ?? IconFactory.Overview;
            var icon = new Label
            {
                Text = glyph,
                Font = new Font("Segoe MDL2 Assets", 18f),
                ForeColor = UiTheme.Accent,
                Dock = DockStyle.Right,
                Width = 38,
                TextAlign = ContentAlignment.TopCenter,
                Padding = new Padding(0, 12, 0, 0)
            };
            panel.Controls.Add(titleLabel);
            panel.Controls.Add(icon);
            panel.Controls.Add(value);
            return panel;
        }

        private static void SetStatus(Label label, bool ok, string text)
        {
            label.Text = text;
            label.ForeColor = ok ? UiTheme.Success : UiTheme.Danger;
        }

        private static Button Button(string text, Action action, int width = 0)
        {
            var button = new Button
            {
                Text = text,
                AutoSize = true,
                Height = 36,
                FlatStyle = FlatStyle.Flat,
                BackColor = UiTheme.SurfaceRaised,
                ForeColor = UiTheme.Text,
                Margin = new Padding(5, 4, 5, 4),
                Padding = new Padding(9, 0, 9, 0),
                Cursor = Cursors.Hand,
                UseVisualStyleBackColor = false
            };
            if (width > 0) button.MinimumSize = new Size(width, button.Height);
            var kind = text == "Обновить" || text.StartsWith("Запустить")
                ? UiButtonKind.Primary
                : text == "Выйти" ? UiButtonKind.Ghost : UiButtonKind.Secondary;
            UiTheme.StyleButton(button, kind);
            button.Click += (s, e) => action();
            return button;
        }

        private static Button Button(string text, Func<Task> action, int width = 0)
        {
            var button = Button(text, (Action)(() => {}), width);
            button.Click += async (s, e) => {
                button.Enabled = false;
                try { await RunAsync(action); }
                finally { if (!button.IsDisposed) button.Enabled = true; }
            };
            return button;
        }

        private static async Task RunAsync(Func<Task> action)
        {
            try { await action(); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "Ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        }

        private static DataGridView Grid()
        {
            var grid = new DataGridView
            {
                BackgroundColor = UiTheme.Surface,
                BorderStyle = BorderStyle.None,
                ReadOnly = true,
                AllowUserToAddRows = false,
                AllowUserToDeleteRows = false,
                AllowUserToResizeRows = false,
                RowHeadersVisible = false,
                SelectionMode = DataGridViewSelectionMode.FullRowSelect,
                MultiSelect = false,
                AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
                GridColor = UiTheme.BorderSubtle,
                EnableHeadersVisualStyles = false,
                CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal,
                ColumnHeadersHeight = 40,
                ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing,
                ScrollBars = ScrollBars.Both,
                Font = new Font("Segoe UI", 9f)
            };
            UiTheme.StyleGrid(grid);
            grid.ScrollBars = ScrollBars.Both;
            return grid;
        }

        private static void ConfigureGrid(DataGridView grid, string[] columns)
        {
            grid.Columns.Clear();
            foreach (var column in columns)
            {
                grid.Columns.Add(new DataGridViewTextBoxColumn
                {
                    HeaderText = column.ToUpperInvariant(),
                    ReadOnly = true,
                    SortMode = DataGridViewColumnSortMode.NotSortable
                });
            }
            grid.ColumnHeadersDefaultCellStyle.BackColor = UiTheme.TableHeader;
            grid.ColumnHeadersDefaultCellStyle.ForeColor = UiTheme.TextSecondary;
            grid.ColumnHeadersDefaultCellStyle.SelectionBackColor = UiTheme.TableHeader;
            grid.ColumnHeadersDefaultCellStyle.Font = UiTheme.Micro;
            grid.ColumnHeadersDefaultCellStyle.Padding = new Padding(14, 0, 10, 0);
            grid.DefaultCellStyle.BackColor = UiTheme.Surface;
            grid.DefaultCellStyle.ForeColor = UiTheme.Text;
            grid.DefaultCellStyle.SelectionBackColor = UiTheme.AccentSoft;
            grid.DefaultCellStyle.SelectionForeColor = UiTheme.Text;
            grid.DefaultCellStyle.Padding = new Padding(14, 0, 10, 0);
            grid.AlternatingRowsDefaultCellStyle.BackColor = UiTheme.Surface;
            grid.RowTemplate.Height = 44;
        }

        private static string[] Pad(string[] row, int count)
        {
            var result = new string[count];
            for (var i = 0; i < count; i++) result[i] = i < row.Length ? row[i] : "";
            return result;
        }
    }

    internal sealed class PageHost : Panel
    {
        private readonly List<Panel> _pages = new List<Panel>();
        private int _selectedIndex = -1;

        public event EventHandler SelectedIndexChanged;

        public int SelectedIndex
        {
            get { return _selectedIndex; }
            set
            {
                if (value < 0 || value >= _pages.Count) throw new ArgumentOutOfRangeException("value");
                if (_selectedIndex == value) return;
                _selectedIndex = value;
                for (var i = 0; i < _pages.Count; i++)
                    _pages[i].Visible = i == _selectedIndex;
                _pages[_selectedIndex].BringToFront();
                _pages[_selectedIndex].PerformLayout();
                _pages[_selectedIndex].Invalidate(true);
                _pages[_selectedIndex].Update();
                var handler = SelectedIndexChanged;
                if (handler != null) handler(this, EventArgs.Empty);
            }
        }

        public Panel SelectedTab
        {
            get { return _selectedIndex >= 0 ? _pages[_selectedIndex] : null; }
            set
            {
                var index = _pages.IndexOf(value);
                if (index < 0) throw new ArgumentException("The page does not belong to this host.", "value");
                SelectedIndex = index;
            }
        }

        public void AddPage(Panel page)
        {
            if (page == null) throw new ArgumentNullException("page");
            page.Dock = DockStyle.Fill;
            page.Visible = false;
            _pages.Add(page);
            Controls.Add(page);
            if (_selectedIndex < 0) SelectedIndex = 0;
        }
    }

}
