using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using MessageBox = CocoAdmin.ConsoleDialog;

namespace CocoAdmin
{
    internal sealed class NativeAdminPanel : UserControl
    {
        private readonly Panel _pageContainer = new Panel();
        private readonly FlowLayoutPanel _pageNavigation = new FlowLayoutPanel();
        private readonly List<Panel> _adminPages = new List<Panel>();
        private readonly List<Button> _adminNavigationButtons = new List<Button>();
        private readonly Label _summary = new Label();
        private readonly Label[] _summaryMetricValues = new Label[8];
        private readonly Label _summarySessionTitle = new Label();
        private readonly TextBox _userSearch = new TextBox();
        private readonly ComboBox _userStatus = new ComboBox();
        private readonly ComboBox _reportStatus = new ComboBox();
        private readonly ComboBox _feedbackStatus = new ComboBox();
        private readonly DataGridView _users = CreateGrid("Имя пользователя", "Отображаемое имя", "Статус", "Ограничения устройств");
        private readonly DataGridView _reports = CreateGrid(
            "Дата", "Кто пожаловался", "На кого", "Причина", "Статус", "Описание");
        private readonly DataGridView _feedback = CreateGrid("Дата", "Платформа", "Версия", "Статус", "Описание");
        private readonly DataGridView _audit = CreateGrid("Дата", "Действие", "Инициатор", "Цель", "Причина");
        private readonly Label _userInfo = new Label();
        private readonly Label _reportInfo = new Label();
        private readonly Label _feedbackInfo = new Label();
        private readonly Label _storageInfo = new Label();
        private readonly Label _operationsMetrics = new Label();
        private readonly Label _backupInfo = new Label();
        private readonly Label _noticeInfo = new Label();
        private readonly Label _operationsInfo = new Label();
        private readonly Label _securityInfo = new Label();
        private readonly DataGridView _operators = CreateGrid("Логин", "Имя", "Роль", "Защита входа", "Аутентификатор", "Бумажный ключ");
        private readonly TextBox[] _controlFields = new TextBox[5];
        private readonly CheckBox _registrationOpen = new CheckBox();
        private readonly CheckBox _automaticProtection = new CheckBox();
        private readonly TextBox _noticeTitle = new TextBox();
        private readonly TextBox _noticeBody = new TextBox();
        private readonly ComboBox _noticeSeverity = new ComboBox();
        private readonly NumericUpDown _noticeHours = new NumericUpDown();
        private readonly LiveMetricsChart _liveChart = new LiveMetricsChart();
        private readonly Timer _operationsTimer = new Timer { Interval = 10000 };
        private Button _deleteUserButton;
        private Button _userSuspensionButton;
        private Button _grantModeratorButton;
        private Panel _auditPage;
        private Panel _storagePage;
        private Panel _operationsPage;
        private readonly PerformanceDiagnosticsPanel _performance = new PerformanceDiagnosticsPanel();
        private readonly TextBox[] _policyFields = new TextBox[8];
        private readonly string[] _policyNames =
        {
            "name", "operator", "contact", "rules", "retention",
            "max_file_bytes", "user_quota_bytes", "storage_budget_bytes"
        };
        private string _usersCursor;
        private string _reportsCursor;
        private string _feedbackCursor;
        private string _auditCursor;
        private string _storagePlanId;
        private long _storagePlanBytes;
        private AdminAuth _auth;
        private bool _refreshing;
        private int _selectedPageIndex;
        private int _authGeneration;
        private int _usersRefreshVersion;
        private int _reportsRefreshVersion;
        private int _feedbackRefreshVersion;
        private int _auditRefreshVersion;
        private int _storageRefreshVersion;
        private int _operationsRefreshVersion;
        private bool _operationsControlsLoaded;
        private bool _operationsControlsDirty;
        private bool _applyingOperationsControls;
        private bool _operationsLoading;
        private bool _savingOperationsControls;
        private long _operationsEditRevision;
        private bool _policyLoaded, _policyDirty, _applyingPolicy;
        private long _policyEditRevision;
        private readonly List<Button> _localMaintenanceButtons = new List<Button>();
        private string _activeNoticeId;
        private const string AdministrativeReason = "Действие владельца через UNYRI Admin Console";

        public event Func<Task> LogoutRequested;
        public event Action<bool> LocalOperationStateChanged;
        public event Action LocalSessionInvalidated;

        public AdminAuth Auth
        {
            get { return _auth; }
            set
            {
                var changedOwner = !ReferenceEquals(_auth, value);
                _auth = value;
                if (changedOwner) _authGeneration++;
                _performance.Auth = value;
                _operationsTimer.Enabled = value != null && IsOwner && _operationsPage != null && _operationsPage.Visible;
                if (changedOwner) ResetAdminContent();
                foreach (var button in _localMaintenanceButtons) button.Enabled = AllowsLocalMaintenance;
                if (_deleteUserButton != null) _deleteUserButton.Enabled = IsOwner;
                if (_grantModeratorButton != null) _grantModeratorButton.Enabled = IsOwner;
                if (value == null)
                {
                    _summary.Text = "Войдите как администратор, чтобы открыть управление.";
                    if (_auditPage != null) _auditPage.Enabled = false;
                    if (_storagePage != null) _storagePage.Enabled = false;
                    if (_operationsPage != null) _operationsPage.Enabled = false;
                    UpdatePagePermissions();
                    SelectPage(0);
                    return;
                }
                if (_auditPage != null) _auditPage.Enabled = IsOwner;
                if (_storagePage != null) _storagePage.Enabled = IsOwner;
                if (_operationsPage != null) _operationsPage.Enabled = IsOwner;
                UpdatePagePermissions();
                if (!IsOwner)
                {
                    _storageInfo.Text = "Раздел хранилища и правил доступен владельцу сервера.";
                    if (_selectedPageIndex >= 4 && _selectedPageIndex != 8) SelectPage(0);
                }
                _summary.Text = "Загрузка данных администратора...";
            }
        }

        public NativeAdminPanel()
        {
            Dock = DockStyle.Fill;
            BackColor = UiTheme.Background;
            ForeColor = UiTheme.Text;
            _pageContainer.Dock = DockStyle.Fill;
            _pageContainer.BackColor = UiTheme.Background;
            _pageNavigation.Dock = DockStyle.Top;
            _pageNavigation.Height = 60;
            _pageNavigation.MinimumSize = new Size(0, 60);
            _pageNavigation.AutoSize = true;
            _pageNavigation.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            _pageNavigation.WrapContents = true;
            _pageNavigation.AutoScroll = false;
            _pageNavigation.Padding = new Padding(24, 10, 24, 8);
            _pageNavigation.BackColor = UiTheme.Background;
            AddPage(BuildSummaryPage());
            AddPage(BuildUsersPage());
            AddPage(BuildReportsPage());
            AddPage(BuildFeedbackPage());
            _auditPage = BuildAuditPage();
            _storagePage = BuildStoragePage();
            AddPage(_auditPage);
            AddPage(_storagePage);
            var performancePage = NewPage("Диагностика");
            performancePage.Controls.Add(_performance);
            AddPage(performancePage);
            _operationsPage = BuildOperationsPage();
            AddPage(_operationsPage);
            AddPage(BuildSecurityPage());
            _operationsTimer.Tick += async (s, e) => await RefreshOperationsAsync(false);
            VisibleChanged += (s, e) => UpdateOperationsTimer();
            Controls.Add(_pageContainer);
            Controls.Add(_pageNavigation);
            UpdatePagePermissions();
            SelectPage(0);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _operationsTimer.Stop();
                _operationsTimer.Dispose();
            }
            base.Dispose(disposing);
        }

        public async Task RefreshAllAsync()
        {
            if (_auth == null || _refreshing || IsDisposed) return;
            var generation = _authGeneration;
            _refreshing = true;
            try
            {
                var work = new List<Task> { RefreshSummaryAsync() };
                if (_selectedPageIndex == 1) work.Add(RefreshUsersAsync(true));
                else if (_selectedPageIndex == 2) work.Add(RefreshReportsAsync(true));
                else if (_selectedPageIndex == 3) work.Add(RefreshFeedbackAsync(true));
                else if (_selectedPageIndex == 4 && IsOwner) work.Add(RefreshAuditAsync(true));
                else if (_selectedPageIndex == 5 && IsOwner) work.Add(RefreshStorageAsync());
                else if (_selectedPageIndex == 6 && IsOwner) work.Add(_performance.RefreshAsync());
                else if (_selectedPageIndex == 7 && IsOwner) work.Add(RefreshOperationsAsync(false));
                else if (_selectedPageIndex == 8) work.Add(RefreshSecurityAsync());
                await Task.WhenAll(work);
            }
            catch (Exception ex)
            {
                if (generation == _authGeneration && !IsDisposed)
                    _summary.Text = "Не удалось обновить данные: " + ex.Message;
            }
            finally
            {
                _refreshing = false;
            }
        }

        private bool IsOwner { get { return _auth != null && string.Equals(_auth.AdminRole, "owner", StringComparison.OrdinalIgnoreCase); } }
        private bool AllowsLocalMaintenance { get { return IsOwner && AdminServerTarget.AllowsLocalMaintenance(_auth.BaseUrl); } }

        private void UpdateOperationsTimer()
        {
            _operationsTimer.Enabled = Visible && _selectedPageIndex == 7 && IsOwner && _auth != null;
        }

        private void AddPage(Panel page)
        {
            page.Dock = DockStyle.Fill;
            page.Visible = false;
            page.Margin = new Padding(0);
            _adminPages.Add(page);
            _pageContainer.Controls.Add(page);
            var index = _adminPages.Count - 1;
            var button = new Button
            {
                Text = page.Text,
                Tag = index,
                AutoSize = true,
                MinimumSize = new Size(128, 38),
                Height = 38,
                Margin = new Padding(0, 0, 8, 0),
                Padding = new Padding(14, 0, 14, 0),
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.Transparent,
                ForeColor = UiTheme.TextSecondary,
                Font = new Font("Segoe UI Semibold", 9f),
                Cursor = Cursors.Hand,
                UseVisualStyleBackColor = false
            };
            button.FlatAppearance.BorderSize = 0;
            button.FlatAppearance.MouseOverBackColor = UiTheme.SurfaceHover;
            UiTheme.Round(button, 10);
            button.Paint += (s, e) =>
            {
                if (button.BackColor != UiTheme.AccentSoft) return;
                using (var brush = new SolidBrush(UiTheme.Accent))
                    e.Graphics.FillRectangle(brush, 12, button.Height - 3, button.Width - 24, 3);
            };
            button.Click += (s, e) => SelectPage((int)button.Tag);
            _adminNavigationButtons.Add(button);
            _pageNavigation.Controls.Add(button);
        }

        private void ResetAdminContent()
        {
            _usersCursor = null;
            _reportsCursor = null;
            _feedbackCursor = null;
            _auditCursor = null;
            _storagePlanId = null;
            _storagePlanBytes = 0;
            _users.Rows.Clear();
            _reports.Rows.Clear();
            _feedback.Rows.Clear();
            _audit.Rows.Clear();
            _summary.Text = "";
            _summarySessionTitle.Text = "Административная сессия";
            for (var i = 0; i < _summaryMetricValues.Length; i++)
                if (_summaryMetricValues[i] != null) _summaryMetricValues[i].Text = "—";
            _storageInfo.Text = "";
            _operationsMetrics.Text = "";
            _backupInfo.Text = "";
            _noticeInfo.Text = "";
            _operationsInfo.Text = "";
            _securityInfo.Text = "";
            _operationsControlsLoaded = false;
            _operationsControlsDirty = false;
            _applyingOperationsControls = true;
            try
            {
                foreach (var box in _controlFields) if (box != null) box.Text = "";
                _registrationOpen.Checked = false;
                _automaticProtection.Checked = false;
                _noticeTitle.Text = "";
                _noticeBody.Text = "";
            }
            finally { _applyingOperationsControls = false; }
            _operationsEditRevision++;
            foreach (var box in _controlFields) if (box != null) box.Enabled = false;
            _registrationOpen.Enabled = false;
            _automaticProtection.Enabled = false;
            _policyLoaded = false;
            _policyDirty = false;
            _applyingPolicy = true;
            _operators.Rows.Clear();
            _activeNoticeId = null;
            _liveChart.Clear();
            _userInfo.Text = "Выберите пользователя, чтобы просмотреть карточку и устройства.";
            try
            {
                for (var i = 0; i < _policyFields.Length; i++)
                    if (_policyFields[i] != null) { _policyFields[i].Text = ""; _policyFields[i].Enabled = false; }
            }
            finally { _applyingPolicy = false; }
            _policyEditRevision++;
        }

        private void SelectPage(int index)
        {
            if (index < 0 || index >= _adminPages.Count) return;
            var page = _adminPages[index];
            if (!page.Enabled) return;
            _selectedPageIndex = index;
            for (var i = 0; i < _adminPages.Count; i++)
            {
                var selected = i == index;
                _adminPages[i].Visible = selected;
                var button = _adminNavigationButtons[i];
                button.BackColor = selected ? UiTheme.AccentSoft : Color.Transparent;
                button.ForeColor = selected ? UiTheme.Text : UiTheme.TextSecondary;
                button.FlatAppearance.BorderSize = 0;
                button.Invalidate();
            }
            page.BringToFront();
            UpdateOperationsTimer();
            LoadPageIfNeeded(index);
        }

        private void UpdatePagePermissions()
        {
            if (_adminNavigationButtons.Count < 6) return;
            _adminNavigationButtons[4].Enabled = IsOwner;
            _adminNavigationButtons[5].Enabled = IsOwner;
            if (_adminNavigationButtons.Count > 6) _adminNavigationButtons[6].Enabled = IsOwner;
            if (_adminNavigationButtons.Count > 7) _adminNavigationButtons[7].Enabled = IsOwner;
            _adminNavigationButtons[4].ForeColor = IsOwner ? Color.FromArgb(164, 178, 201) : Color.FromArgb(91, 101, 119);
            _adminNavigationButtons[5].ForeColor = IsOwner ? Color.FromArgb(164, 178, 201) : Color.FromArgb(91, 101, 119);
        }

        private async void LoadPageIfNeeded(int index)
        {
            if (_auth == null) return;
            try
            {
                if (index == 1 && _users.Rows.Count == 0) await RefreshUsersAsync(true);
                else if (index == 2 && _reports.Rows.Count == 0) await RefreshReportsAsync(true);
                else if (index == 3 && _feedback.Rows.Count == 0) await RefreshFeedbackAsync(true);
                else if (index == 4 && IsOwner && _audit.Rows.Count == 0) await RefreshAuditAsync(true);
                else if (index == 5 && IsOwner) await RefreshStorageAsync();
                else if (index == 7 && IsOwner) await RefreshOperationsAsync(true);
                else if (index == 8) await RefreshSecurityAsync();
            }
            catch (Exception ex)
            {
                NoticeError(ex.Message);
            }
        }

        private Panel BuildSummaryPage()
        {
            var page = NewPage("Сводка");
            var toolbar = Toolbar();
            toolbar.Controls.Add(ActionButton("Обновить", async () => await RefreshAllAsync(), 120));
            toolbar.Controls.Add(ActionButton("Мой вход", async () => { SelectPage(8); await Task.FromResult(0); }, 120));
            toolbar.Controls.Add(ActionButton("В браузере", async () => await OpenBrowserConsoleAsync(), 140));
            toolbar.Controls.Add(ActionButton("Выйти", async () =>
            {
                if (LogoutRequested != null) await LogoutRequested();
            }, 110));
            var header = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                MinimumSize = new Size(0, 76),
                ColumnCount = 1,
                RowCount = 2,
                Margin = new Padding(0),
                Padding = new Padding(2, 0, 0, 10),
                BackColor = UiTheme.Background
            };
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            header.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            header.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            header.Controls.Add(new Label
            {
                Text = "Сводка сервера",
                Font = UiTheme.H1,
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(0)
            }, 0, 0);
            header.Controls.Add(new Label
            {
                Text = "Состояние административной сессии и модерации",
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(1, 4, 0, 0)
            }, 0, 1);
            var metrics = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 248,
                ColumnCount = 4,
                RowCount = 2,
                Margin = new Padding(0),
                Padding = new Padding(0, 0, 0, 12),
                BackColor = UiTheme.Background
            };
            for (var column = 0; column < 4; column++)
                metrics.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
            metrics.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
            metrics.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
            var metricTitles = new[]
            {
                "Новые жалобы", "На рассмотрении", "Новые обращения", "Жалоб за 24 часа",
                "Ограничены аккаунты", "Ограничены устройства", "Хранилище", "Роль"
            };
            var metricColors = new[]
            {
                UiTheme.Warning, UiTheme.Info, UiTheme.Accent, UiTheme.TextSecondary,
                UiTheme.Danger, UiTheme.Warning, UiTheme.Success, UiTheme.Accent
            };
            for (var index = 0; index < metricTitles.Length; index++)
                metrics.Controls.Add(SummaryMetricCard(index, metricTitles[index], metricColors[index]), index % 4, index / 4);

            _summarySessionTitle.Text = "Административная сессия";
            _summarySessionTitle.Dock = DockStyle.Top;
            _summarySessionTitle.Height = 40;
            _summarySessionTitle.Padding = new Padding(22, 12, 22, 0);
            _summarySessionTitle.Font = UiTheme.H3;
            _summarySessionTitle.ForeColor = UiTheme.Text;
            _summary.Dock = DockStyle.Fill;
            _summary.Padding = new Padding(22, 8, 22, 18);
            _summary.Font = UiTheme.Body;
            _summary.TextAlign = ContentAlignment.TopLeft;
            _summary.ForeColor = UiTheme.TextSecondary;
            _summary.BackColor = UiTheme.Surface;
            var sessionCard = new Panel
            {
                Dock = DockStyle.Fill,
                BackColor = UiTheme.Surface,
                Padding = new Padding(2)
            };
            UiTheme.StyleCard(sessionCard, 14);
            sessionCard.Controls.Add(_summary);
            sessionCard.Controls.Add(_summarySessionTitle);
            page.Controls.Add(sessionCard);
            page.Controls.Add(metrics);
            page.Controls.Add(header);
            page.Controls.Add(toolbar);
            return page;
        }

        private Panel SummaryMetricCard(int index, string title, Color accent)
        {
            var card = new Panel
            {
                Dock = DockStyle.Fill,
                Margin = new Padding(index % 4 == 0 ? 0 : 6, index < 4 ? 0 : 6,
                    index % 4 == 3 ? 0 : 6, index < 4 ? 6 : 0),
                Padding = new Padding(18, 14, 14, 12),
                BackColor = UiTheme.Surface
            };
            UiTheme.StyleCard(card, 12);
            var stripe = new Panel { Dock = DockStyle.Left, Width = 4, BackColor = accent };
            var label = new Label
            {
                Text = title,
                Dock = DockStyle.Bottom,
                Height = 30,
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                TextAlign = ContentAlignment.MiddleLeft
            };
            var value = new Label
            {
                Text = "—",
                Dock = DockStyle.Fill,
                Font = UiTheme.Kpi,
                ForeColor = UiTheme.Text,
                TextAlign = ContentAlignment.MiddleLeft,
                AutoEllipsis = true
            };
            _summaryMetricValues[index] = value;
            card.Controls.Add(value);
            card.Controls.Add(label);
            card.Controls.Add(stripe);
            return card;
        }

        private Panel BuildUsersPage()
        {
            var page = NewPage("Пользователи");
            var toolbar = Toolbar();
            _userSearch.Width = 240;
            _userSearch.Font = UiTheme.Body;
            UiTheme.StyleInput(_userSearch);
            _userStatus.DropDownStyle = ComboBoxStyle.DropDownList;
            _userStatus.Width = 150;
            UiTheme.StyleInput(_userStatus);
            _userStatus.Items.AddRange(new object[] { "Все", "Активные", "Ограниченные" });
            _userStatus.SelectedIndex = 0;
            _userSearch.TextChanged += (sender, args) => { _usersCursor = null; ++_usersRefreshVersion; };
            _userStatus.SelectedIndexChanged += (sender, args) => { _usersCursor = null; ++_usersRefreshVersion; };
            toolbar.Controls.Add(_userSearch);
            toolbar.Controls.Add(_userStatus);
            toolbar.Controls.Add(ActionButton("Найти", async () => await RefreshUsersAsync(true), 100));
            toolbar.Controls.Add(ActionButton("Загрузить ещё", async () => await RefreshUsersAsync(false), 130));
            toolbar.Controls.Add(ActionButton("Открыть профиль", async () => await ShowSelectedUserAsync(), 145));
            _userSuspensionButton = ActionButton("Изменить ограничения", async () => await ToggleUserSuspensionAsync(), 240);
            _userSuspensionButton.Enabled = false;
            toolbar.Controls.Add(_userSuspensionButton);
            _deleteUserButton = ActionButton("Удалить выбранных", async () => await DeleteSelectedUserAsync(), 190);
            _deleteUserButton.Enabled = IsOwner;
            toolbar.Controls.Add(_deleteUserButton);
            _grantModeratorButton = ActionButton("Назначить Moderator", async () => await GrantSelectedModeratorAsync(), 190);
            _grantModeratorButton.Enabled = IsOwner;
            toolbar.Controls.Add(_grantModeratorButton);
            _userInfo.Text = "Выберите пользователя, чтобы просмотреть карточку и устройства.";
            _userInfo.Dock = DockStyle.Bottom;
            _userInfo.Height = 34;
            _userInfo.ForeColor = UiTheme.TextMuted;
            _userInfo.Font = UiTheme.BodySmall;
            _users.Dock = DockStyle.Fill;
            _users.MultiSelect = true;
            _users.SelectionChanged += (s, e) => UpdateUserActionLabels();
            _users.CellDoubleClick += async (s, e) =>
            {
                if (e.RowIndex < 0) return;
                _users.CurrentCell = _users.Rows[e.RowIndex].Cells[0];
                try { await ShowSelectedUserAsync(); }
                catch (Exception ex) { NoticeError("Не удалось открыть профиль пользователя: " + ex.Message); }
            };
            page.Controls.Add(_users);
            page.Controls.Add(_userInfo);
            page.Controls.Add(toolbar);
            return page;
        }

        private Panel BuildReportsPage()
        {
            var page = NewPage("Жалобы");
            var toolbar = Toolbar();
            ConfigureStatusFilter(_reportStatus, new[] { "Все статусы", "Открытые", "В работе", "Решённые", "Отклонённые" });
            toolbar.Controls.Add(_reportStatus);
            toolbar.Controls.Add(ActionButton("Обновить", async () => await RefreshReportsAsync(true), 110));
            toolbar.Controls.Add(ActionButton("Ещё", async () => await RefreshReportsAsync(false), 90));
            toolbar.Controls.Add(ActionButton("Рассмотреть выбранные", async () => await ReviewReportAsync(), 210));
            _reports.Dock = DockStyle.Fill;
            _reports.MultiSelect = true;
            ConfigureListInfo(_reportInfo, "Жалобы ещё не загружены.");
            page.Controls.Add(_reports);
            page.Controls.Add(_reportInfo);
            page.Controls.Add(toolbar);
            return page;
        }

        private Panel BuildFeedbackPage()
        {
            var page = NewPage("Обращения");
            var toolbar = Toolbar();
            ConfigureStatusFilter(_feedbackStatus, new[] { "Все статусы", "Открытые", "Решённые", "Отклонённые" });
            toolbar.Controls.Add(_feedbackStatus);
            toolbar.Controls.Add(ActionButton("Обновить", async () => await RefreshFeedbackAsync(true), 110));
            toolbar.Controls.Add(ActionButton("Ещё", async () => await RefreshFeedbackAsync(false), 90));
            toolbar.Controls.Add(ActionButton("Рассмотреть выбранные", async () => await ReviewFeedbackAsync(), 210));
            toolbar.Controls.Add(ActionButton("Открыть скриншот", async () => await ShowFeedbackScreenshotAsync(), 165));
            toolbar.Controls.Add(ActionButton("Диагностика", async () => await ShowFeedbackPerformanceAsync(), 140));
            var retention = new Label
            {
                Text = "Скриншоты могут содержать личные данные · срок хранения обращений 30 дней",
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Margin = new Padding(12, 9, 0, 0)
            };
            toolbar.Controls.Add(retention);
            _feedback.Dock = DockStyle.Fill;
            _feedback.MultiSelect = true;
            ConfigureListInfo(_feedbackInfo, "Обращения ещё не загружены.");
            page.Controls.Add(_feedback);
            page.Controls.Add(_feedbackInfo);
            page.Controls.Add(toolbar);
            return page;
        }

        private Panel BuildAuditPage()
        {
            var page = NewPage("Аудит");
            var toolbar = Toolbar();
            toolbar.Controls.Add(ActionButton("Обновить", async () => await RefreshAuditAsync(true), 110));
            toolbar.Controls.Add(ActionButton("Ещё", async () => await RefreshAuditAsync(false), 90));
            toolbar.Controls.Add(ActionButton("Экспорт CSV", async () => await ExportAuditCsvAsync(), 130));
            _audit.Dock = DockStyle.Fill;
            _audit.MultiSelect = true;
            page.Controls.Add(_audit);
            page.Controls.Add(toolbar);
            return page;
        }

        private Panel BuildStoragePage()
        {
            var page = NewPage("Сервер и данные");
            var outer = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Padding = new Padding(20), BackColor = UiTheme.Background };
            var toolbar = Toolbar();
            toolbar.Controls.Add(ActionButton("Обновить хранилище", async () => await RefreshStorageAsync(), 180));
            toolbar.Controls.Add(ActionButton("Отменить правки правил", async () =>
            {
                if (_policyDirty && MessageBox.Show(this, "Отменить несохранённые изменения правил и загрузить значения сервера?",
                    "Правила сервера", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                _policyDirty = false;
                await RefreshStorageAsync();
            }, 205));
            toolbar.Controls.Add(ActionButton("Предварительный просмотр очистки", async () => await PreviewCleanupAsync(), 230));
            toolbar.Controls.Add(ActionButton("Выполнить очистку", async () => await RunCleanupAsync(), 160));
            toolbar.Controls.Add(LocalMaintenanceButton("Сброс PostgreSQL этого ПК", async () => await ResetDatabaseAsync(), 235));
            _storageInfo.Dock = DockStyle.Top;
            _storageInfo.Height = 72;
            _storageInfo.ForeColor = UiTheme.TextSecondary;
            _storageInfo.Font = new Font("Segoe UI", 10f);
            var form = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                ColumnCount = 2,
                RowCount = _policyNames.Length,
                Padding = new Padding(0, 14, 0, 0),
                BackColor = UiTheme.Background
            };
            form.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
            form.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            var labels = new[] { "Название сервера", "Оператор", "Контакт", "Правила", "Хранение данных", "Максимальный файл (байт)", "Квота пользователя (байт)", "Бюджет хранилища (байт)" };
            for (var i = 0; i < _policyNames.Length; i++)
            {
                var label = new Label
                {
                    Text = labels[i],
                    ForeColor = UiTheme.TextSecondary,
                    Dock = DockStyle.Fill,
                    TextAlign = ContentAlignment.MiddleLeft,
                    Padding = new Padding(0, 0, 8, 0)
                };
                var box = new TextBox
                {
                    Dock = DockStyle.Fill,
                    BackColor = UiTheme.SurfaceRaised,
                    ForeColor = Color.FromArgb(241, 245, 252),
                    BorderStyle = BorderStyle.FixedSingle,
                    Multiline = i == 3 || i == 4,
                    Height = i == 3 || i == 4 ? 90 : 30,
                    Margin = new Padding(0, 5, 0, 5)
                };
                _policyFields[i] = box;
                UiTheme.StyleInput(box);
                box.TextChanged += (s, e) => { if (!_applyingPolicy) { _policyDirty = true; _policyEditRevision++; } };
                form.Controls.Add(label, 0, i);
                form.Controls.Add(box, 1, i);
            }
            var save = ActionButton("Сохранить правила", async () => await SavePolicyAsync(), 180);
            var saveRow = new Panel { Dock = DockStyle.Top, Height = 54 };
            saveRow.Controls.Add(save);
            save.Location = new Point(190, 8);
            outer.Controls.Add(saveRow);
            outer.Controls.Add(form);
            outer.Controls.Add(_storageInfo);
            outer.Controls.Add(toolbar);
            page.Controls.Add(outer);
            if (_auth != null && !IsOwner)
            {
                page.Enabled = false;
                _storageInfo.Text = "Раздел доступен владельцу сервера.";
            }
            return page;
        }

        private Panel BuildOperationsPage()
        {
            var page = NewPage("Управление");
            var toolbar = Toolbar();
            toolbar.Controls.Add(ActionButton("Обновить", async () => await RefreshOperationsAsync(true), 120));
            toolbar.Controls.Add(ActionButton("Сохранить настройки", async () => await SaveOperationsControlsAsync(), 210));
            toolbar.Controls.Add(ActionButton("Отменить изменения", async () => await DiscardOperationsDraftAsync(), 200));
            _operationsInfo.Text = "Настройки применяются сервером сразу и записываются в аудит.";
            _operationsInfo.AutoSize = true;
            _operationsInfo.ForeColor = UiTheme.TextMuted;
            _operationsInfo.Margin = new Padding(12, 10, 0, 0);
            toolbar.Controls.Add(_operationsInfo);

            var stack = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 1320,
                ColumnCount = 1,
                RowCount = 5,
                Margin = new Padding(0),
                Padding = new Padding(20, 8, 20, 20),
                BackColor = UiTheme.Background
            };
            stack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            stack.RowStyles.Add(new RowStyle(SizeType.Absolute, 200));
            stack.RowStyles.Add(new RowStyle(SizeType.Absolute, 310));
            stack.RowStyles.Add(new RowStyle(SizeType.Absolute, 300));
            stack.RowStyles.Add(new RowStyle(SizeType.Absolute, 300));
            stack.RowStyles.Add(new RowStyle(SizeType.Absolute, 190));

            _operationsMetrics.Dock = DockStyle.Top;
            _operationsMetrics.Height = 56;
            _operationsMetrics.ForeColor = UiTheme.TextSecondary;
            _operationsMetrics.Font = UiTheme.Body;
            _operationsMetrics.Padding = new Padding(0, 4, 0, 4);
            _liveChart.Dock = DockStyle.Fill;
            var metricsBody = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Surface };
            metricsBody.Controls.Add(_liveChart);
            metricsBody.Controls.Add(_operationsMetrics);
            stack.Controls.Add(AdminSectionCard("Live-метрики", "Активные сессии и подключения без персональных идентификаторов", metricsBody), 0, 0);

            var controls = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 4,
                RowCount = 4,
                Margin = new Padding(0),
                Padding = new Padding(0, 4, 0, 0),
                BackColor = UiTheme.Surface
            };
            controls.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170));
            controls.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            controls.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 180));
            controls.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            for (var i = 0; i < 4; i++) controls.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
            var controlLabels = new[] { "Мин. Android", "Мин. iOS", "Мин. Desktop", "Лимит IP / мин", "Лимит аккаунта / 5 мин" };
            for (var i = 0; i < _controlFields.Length; i++)
            {
                _controlFields[i] = new TextBox { Dock = DockStyle.Fill, Margin = new Padding(0, 8, 16, 8) };
                UiTheme.StyleInput(_controlFields[i]);
                _controlFields[i].TextChanged += (s, e) => OperationsDraftChanged();
                var row = i < 3 ? i : i - 3;
                var column = i < 3 ? 0 : 2;
                controls.Controls.Add(FormLabel(controlLabels[i]), column, row);
                controls.Controls.Add(_controlFields[i], column + 1, row);
            }
            _registrationOpen.Text = "Разрешить регистрацию новых аккаунтов";
            _automaticProtection.Text = "Автоматическая защита входа";
            _registrationOpen.CheckedChanged += (s, e) => OperationsDraftChanged();
            _automaticProtection.CheckedChanged += (s, e) => OperationsDraftChanged();
            UiTheme.StyleCheckBox(_registrationOpen, UiTheme.Surface);
            UiTheme.StyleCheckBox(_automaticProtection, UiTheme.Surface);
            _registrationOpen.Dock = DockStyle.Fill;
            _automaticProtection.Dock = DockStyle.Fill;
            controls.Controls.Add(_registrationOpen, 0, 3);
            controls.SetColumnSpan(_registrationOpen, 2);
            controls.Controls.Add(_automaticProtection, 2, 3);
            controls.SetColumnSpan(_automaticProtection, 2);
            stack.Controls.Add(AdminSectionCard("Версии и защита входа", "Минимальные версии публикуются в /server-info; защита ограничивает перебор паролей", controls), 0, 1);

            _operators.Dock = DockStyle.Fill;
            _operators.MultiSelect = false;
            var operatorToolbar = Toolbar();
            operatorToolbar.Dock = DockStyle.Fill;
            operatorToolbar.Margin = new Padding(0);
            operatorToolbar.Controls.Add(ActionButton("Сделать Owner", async () => await ChangeSelectedOperatorAsync("owner"), 175));
            operatorToolbar.Controls.Add(ActionButton("Сделать Moderator", async () => await ChangeSelectedOperatorAsync("moderator"), 220));
            operatorToolbar.Controls.Add(ActionButton("Отозвать роль", async () => await ChangeSelectedOperatorAsync("revoke"), 175));
            operatorToolbar.Controls.Add(ActionButton("Сбросить аутентификатор", async () => await ResetSelectedOperator2FaAsync(), 230));
            var operatorBody = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 2,
                Margin = new Padding(0),
                Padding = new Padding(0),
                BackColor = UiTheme.Surface
            };
            operatorBody.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            operatorBody.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            operatorBody.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            operatorBody.Controls.Add(_operators, 0, 0);
            operatorBody.Controls.Add(operatorToolbar, 0, 1);
            stack.Controls.Add(AdminSectionCard("Администраторы", "Изменения ролей завершают активные административные сессии выбранного оператора", operatorBody), 0, 2);

            _noticeTitle.Dock = DockStyle.Fill;
            _noticeBody.Dock = DockStyle.Fill;
            _noticeBody.Multiline = true;
            _noticeBody.ScrollBars = ScrollBars.Vertical;
            UiTheme.StyleInput(_noticeTitle);
            UiTheme.StyleInput(_noticeBody);
            _noticeSeverity.DropDownStyle = ComboBoxStyle.DropDownList;
            _noticeSeverity.Items.AddRange(new object[] { "Информация", "Предупреждение", "Критично" });
            _noticeSeverity.SelectedIndex = 0;
            _noticeSeverity.Dock = DockStyle.Fill;
            _noticeSeverity.Margin = new Padding(0, 5, 0, 5);
            UiTheme.StyleInput(_noticeSeverity);
            _noticeHours.Minimum = 1;
            _noticeHours.Maximum = 720;
            _noticeHours.Value = 24;
            _noticeHours.BackColor = UiTheme.SurfaceRaised;
            _noticeHours.ForeColor = UiTheme.Text;
            UiTheme.ApplyDarkNativeTheme(_noticeHours);
            var noticeForm = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 4, RowCount = 3, Margin = new Padding(0), BackColor = UiTheme.Surface };
            noticeForm.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
            noticeForm.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 60));
            noticeForm.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
            noticeForm.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40));
            noticeForm.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
            noticeForm.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            noticeForm.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            noticeForm.Controls.Add(FormLabel("Заголовок"), 0, 0);
            noticeForm.Controls.Add(_noticeTitle, 1, 0);
            noticeForm.Controls.Add(FormLabel("Важность"), 2, 0);
            noticeForm.Controls.Add(_noticeSeverity, 3, 0);
            noticeForm.Controls.Add(FormLabel("Сообщение"), 0, 1);
            noticeForm.Controls.Add(_noticeBody, 1, 1);
            noticeForm.SetColumnSpan(_noticeBody, 3);
            _noticeInfo.Dock = DockStyle.Fill;
            _noticeInfo.ForeColor = UiTheme.TextMuted;
            _noticeInfo.TextAlign = ContentAlignment.MiddleLeft;
            var noticeActions = new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                MinimumSize = new Size(0, 52),
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
                BackColor = UiTheme.Surface
            };
            noticeActions.Controls.Add(ActionButton("Опубликовать", async () => await PublishAnnouncementAsync(), 165));
            noticeActions.Controls.Add(ActionButton("Отключить текущее", async () => await DisableAnnouncementAsync(), 210));
            noticeActions.Controls.Add(new Label { Text = "Срок, часов", AutoSize = true, ForeColor = UiTheme.TextMuted, Margin = new Padding(14, 11, 6, 0) });
            noticeActions.Controls.Add(_noticeHours);
            noticeForm.Controls.Add(noticeActions, 0, 2);
            noticeForm.SetColumnSpan(noticeActions, 2);
            noticeForm.Controls.Add(_noticeInfo, 2, 2);
            noticeForm.SetColumnSpan(_noticeInfo, 2);
            stack.Controls.Add(AdminSectionCard("Сервисное объявление", "Публикуется в серверной карточке; одновременно активно одно объявление", noticeForm), 0, 3);

            _backupInfo.Dock = DockStyle.Fill;
            _backupInfo.ForeColor = UiTheme.TextSecondary;
            _backupInfo.Font = UiTheme.Body;
            _backupInfo.TextAlign = ContentAlignment.TopLeft;
            _backupInfo.Padding = new Padding(0, 6, 0, 0);
            var backupActions = Toolbar();
            backupActions.Dock = DockStyle.Fill;
            backupActions.Margin = new Padding(0);
            backupActions.Controls.Add(LocalMaintenanceButton("Копия базы этого ПК", async () => await CreateDatabaseBackupAsync(), 220));
            backupActions.Controls.Add(LocalMaintenanceButton("Восстановить базу этого ПК", async () => await RestoreDatabaseBackupAsync(), 235));
            var backupBody = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 2,
                Margin = new Padding(0),
                Padding = new Padding(0),
                BackColor = UiTheme.Surface
            };
            backupBody.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            backupBody.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            backupBody.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            backupBody.Controls.Add(_backupInfo, 0, 0);
            backupBody.Controls.Add(backupActions, 0, 1);
            stack.Controls.Add(AdminSectionCard("Копии истории и локальные инструменты", "Сведения относятся к выбранному серверу. Кнопки PostgreSQL доступны только для loopback-сервера этого ПК, облаком они не управляют.", backupBody), 0, 4);

            var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true, BackColor = UiTheme.Background };
            UiTheme.ApplyDarkNativeTheme(scroll);
            scroll.Controls.Add(stack);
            page.Controls.Add(scroll);
            page.Controls.Add(toolbar);
            return page;
        }

        private Button LocalMaintenanceButton(string text, Func<Task> action, int width)
        {
            var button = ActionButton(text, action, width);
            button.Enabled = AllowsLocalMaintenance;
            button.EnabledChanged += (s, e) => { if (button.Enabled && !AllowsLocalMaintenance) button.Enabled = false; };
            _localMaintenanceButtons.Add(button);
            return button;
        }

        private void OperationsDraftChanged()
        {
            if (_applyingOperationsControls) return;
            _operationsControlsDirty = true;
            _operationsEditRevision++;
            _operationsInfo.Text = "Есть несохранённые изменения · автообновление их не изменит.";
        }

        private async Task DiscardOperationsDraftAsync()
        {
            if (_savingOperationsControls || _operationsLoading) return;
            if (_operationsControlsDirty && MessageBox.Show(this, "Загрузить настройки сервера заново и отменить несохранённые изменения?",
                "Отменить изменения", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            _operationsControlsDirty = false;
            _operationsControlsLoaded = false;
            await RefreshOperationsAsync(true);
        }

        private Panel BuildSecurityPage()
        {
            var page = NewPage("Мой вход");
            var toolbar = Toolbar();
            toolbar.Controls.Add(ActionButton("Обновить", async () => await RefreshSecurityAsync(), 120));
            toolbar.Controls.Add(ActionButton("Изменить способ входа", async () =>
            {
                var auth = _auth;
                var generation = _authGeneration;
                if (auth == null) return;
                if (await AdminSecurityDialog.ShowForAsync(this, auth) && IsCurrentAuth(auth, generation))
                    if (LocalSessionInvalidated != null) LocalSessionInvalidated();
            }, 240));
            var body = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
                WrapContents = false, AutoScroll = true, Padding = new Padding(24), BackColor = UiTheme.Background };
            var title = new Label { Text = "Защита моего входа", Font = UiTheme.H1, AutoSize = true, Margin = new Padding(0, 4, 0, 16) };
            _securityInfo.AutoSize = true;
            _securityInfo.MaximumSize = new Size(760, 0);
            _securityInfo.ForeColor = UiTheme.Text;
            _securityInfo.Font = UiTheme.H3;
            var description = new Label { AutoSize = true, MaximumSize = new Size(760, 0), ForeColor = UiTheme.TextSecondary,
                Margin = new Padding(0, 22, 0, 0), Text = "Пароль остаётся обязательным. Выберите, чем дополнительно подтверждать вход:\r\n\r\n" +
                "Аутентификатор — одноразовый код из приложения; запасные коды помогут при потере телефона.\r\n\r\n" +
                "Бумажный ключ — отдельный секретный ключ подписи. Храните его отдельно от пароля. Это не запасной код аутентификатора.\r\n\r\n" +
                "Оба способа — при каждом входе нужны и ключ, и код.\r\n\r\n" +
                "Изменение требует свежего подтверждения текущей защиты. После сохранения все административные сессии завершатся. " +
                "Аккаунты, переписка и обычные сессии UNYRI от этого не удаляются." };
            body.Controls.AddRange(new Control[] { title, _securityInfo, description });
            Action fitText = () =>
            {
                var width = Math.Max(240, body.ClientSize.Width - body.Padding.Horizontal - SystemInformation.VerticalScrollBarWidth - 4);
                _securityInfo.MaximumSize = new Size(width, 0);
                description.MaximumSize = new Size(width, 0);
            };
            body.SizeChanged += (s, e) => fitText();
            fitText();
            page.Controls.Add(body);
            page.Controls.Add(toolbar);
            return page;
        }

        private async Task RefreshSecurityAsync()
        {
            var auth = _auth;
            var generation = _authGeneration;
            if (auth == null) return;
            var status = await auth.GetAdminStatusAsync();
            if (!IsCurrentAuth(auth, generation)) return;
            _securityInfo.Text = AdminServerTarget.Display(auth.BaseUrl) + "\r\n\r\n" +
                "Сейчас: пароль + " + (status.AuthPolicy == "authenticator" ? "аутентификатор" :
                status.AuthPolicy == "paper_key" ? "бумажный ключ" : "бумажный ключ и аутентификатор") +
                (status.SupportsFactorChoice ? "" : "\r\nДля выбора способов входа требуется обновление сервера.");
        }

        private async Task OpenBrowserConsoleAsync()
        {
            var auth = _auth;
            var generation = _authGeneration;
            if (auth == null) return;
            // Validate HTTPS before asking the server to mint a credential.
            AdminConsoleLink.Build(auth.BaseUrl, new string('a', 43));
            var data = await auth.SendAsync(HttpMethod.Post, "admin/console-handoff", null, true, true);
            if (!IsCurrentAuth(auth, generation)) return;
            var link = AdminConsoleLink.Build(auth.BaseUrl, Value(data, "handoff"));
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(link.AbsoluteUri) { UseShellExecute = true }); }
            catch { throw new InvalidOperationException("Не удалось открыть браузер. Проверьте браузер по умолчанию и повторите. Одноразовая ссылка не сохранена."); }
        }

        private static Panel AdminSectionCard(string title, string subtitle, Control content)
        {
            var card = new Panel
            {
                Dock = DockStyle.Fill,
                Margin = new Padding(0, 0, 0, 14),
                Padding = new Padding(20, 14, 20, 16),
                BackColor = UiTheme.Surface
            };
            UiTheme.StyleCard(card, 14);
            var layout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 3,
                Margin = new Padding(0),
                Padding = new Padding(0),
                BackColor = UiTheme.Surface
            };
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            var heading = new Label
            {
                Text = title,
                Font = UiTheme.H3,
                ForeColor = UiTheme.Text,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(0)
            };
            var description = new Label
            {
                Text = subtitle,
                Font = UiTheme.BodySmall,
                ForeColor = UiTheme.TextMuted,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(0, 4, 0, 12)
            };
            content.Dock = DockStyle.Fill;
            content.Margin = new Padding(0);
            layout.Controls.Add(heading, 0, 0);
            layout.Controls.Add(description, 0, 1);
            layout.Controls.Add(content, 0, 2);
            card.Controls.Add(layout);
            return card;
        }

        private static Label FormLabel(string text)
        {
            return new Label { Text = text, Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(0, 0, 8, 0) };
        }

        private async Task RefreshOperationsAsync(bool reportErrors)
        {
            var auth = _auth;
            var generation = _authGeneration;
            if (auth == null || !IsOwner || IsDisposed || _operationsLoading || _savingOperationsControls) return;
            var hostForm = FindForm();
            if (!reportErrors && hostForm != null && hostForm.WindowState == FormWindowState.Minimized) return;
            _operationsLoading = true;
            var requestVersion = ++_operationsRefreshVersion;
            try
            {
                var data = await auth.SendAsync(HttpMethod.Get, "admin/operations", null, true, true);
                if (!IsCurrentAuth(auth, generation) || requestVersion != _operationsRefreshVersion) return;
                ApplyOperationsControls(Object(data, "controls"));

                ApplyOperators(Items(ObjectValue(data, "operators")));

                var live = Object(data, "live");
                _operationsMetrics.Text = "Пользователей: " + Value(live, "users") + "   ·   активных сессий: " + Value(live, "active_sessions") +
                    "   ·   WebSocket: " + Value(live, "connections") + "   ·   подключено устройств: " + Value(live, "connected_devices") +
                    "   ·   приложение на экране: " + Value(live, "foreground_users") + "\r\nНаблюдение: " + LocalTime(Value(live, "observed_at"));
                _liveChart.AddSample((int)LongValue(live, "active_sessions"), (int)LongValue(live, "connections"),
                    (int)LongValue(live, "foreground_users"));

                var backups = Object(data, "backups");
                _backupInfo.Text = "Зашифрованные копии: " + Value(backups, "active_objects") + " активных · " +
                    Value(backups, "staged_objects") + " готовятся · включено аккаунтов: " + Value(backups, "enabled_accounts") +
                    " · объём ciphertext: " + Bytes(Value(backups, "ciphertext_bytes")) + "\r\nПоследняя проверенная копия: " +
                    LocalTime(Value(backups, "last_verified_at")) + ". Расшифровка и восстановление выполняются только доверенным клиентом пользователя.";

                var notice = Object(data, "active_notice");
                _activeNoticeId = Value(notice, "id");
                _noticeInfo.Text = notice == null || string.IsNullOrEmpty(_activeNoticeId)
                    ? "Активного объявления нет."
                    : "Активно: «" + Value(notice, "title") + "» · до " + LocalTime(Value(notice, "expires_at"));
                _operationsInfo.Text = _operationsControlsDirty ? "Есть несохранённые изменения · автообновление их не изменит." :
                    "Обновлено " + DateTime.Now.ToString("HH:mm:ss") + " · выбранный сервер";
            }
            catch (Exception ex)
            {
                if (!IsCurrentAuth(auth, generation)) return;
                if (reportErrors) NoticeError("Не удалось обновить центр управления: " + ex.Message);
                else _operationsInfo.Text = "Live-обновление недоступно: " + ex.Message +
                    (_operationsControlsDirty ? " · изменения не потеряны" : "");
            }
            finally { _operationsLoading = false; }
        }

        private void ApplyOperationsControls(Dictionary<string, object> controls)
        {
            // Re-check after awaiting the response: typing can start while the
            // request is in flight, including the very first refresh.
            if (controls == null) return;
            if (_operationsControlsDirty) return;
            _applyingOperationsControls = true;
            try
            {
                _controlFields[0].Text = Value(controls, "min_android_version");
                _controlFields[1].Text = Value(controls, "min_ios_version");
                _controlFields[2].Text = Value(controls, "min_desktop_version");
                _controlFields[3].Text = Value(controls, "login_ip_limit");
                _controlFields[4].Text = Value(controls, "login_account_limit");
                _registrationOpen.Checked = Bool(controls, "registration_open");
                _automaticProtection.Checked = Bool(controls, "automatic_login_protection");
                _operationsControlsLoaded = true;
                foreach (var box in _controlFields) box.Enabled = true;
                _registrationOpen.Enabled = true;
                _automaticProtection.Enabled = true;
            }
            finally { _applyingOperationsControls = false; }
        }

        private void ApplyOperators(IEnumerable<Dictionary<string, object>> items)
        {
            var selectedId = Value(SelectedOperator(), "user_id");
            DataGridViewRow selected = null;
            _operators.SuspendLayout();
            try
            {
                _operators.Rows.Clear();
                foreach (var item in items)
                {
                    var row = _operators.Rows[_operators.Rows.Add(
                        "@" + Value(item, "username"), Empty(Value(item, "display_name"), "—"),
                        OperatorRole(Value(item, "role")), AdminSecurityPolicy.Label(Empty(Value(item, "auth_policy"), AdminSecurityPolicy.Both)),
                        Bool(item, "two_factor_configured") ? "Настроен" : "Не настроен",
                        Bool(item, "paper_key_configured") ? "Настроен" : "Не настроен")];
                    row.Tag = item;
                    if (Bool(item, "is_current")) row.DefaultCellStyle.ForeColor = UiTheme.Accent;
                    if (selectedId.Length != 0 && Value(item, "user_id") == selectedId) selected = row;
                }
                // Never let an automatic refresh silently choose a different
                // administrator for the next role/reset action.
                _operators.CurrentCell = null;
                _operators.ClearSelection();
                if (selected != null) { _operators.CurrentCell = selected.Cells[0]; selected.Selected = true; }
            }
            finally { _operators.ResumeLayout(); }
        }

        private async Task SaveOperationsControlsAsync()
        {
            if (!IsOwner || _auth == null || _savingOperationsControls) return;
            if (!_operationsControlsLoaded)
            {
                NoticeError("Сначала загрузите настройки сервера. Введённые значения сохранятся на экране.");
                return;
            }
            var auth = _auth;
            var generation = _authGeneration;
            var editRevision = _operationsEditRevision;
            long ipLimit;
            long accountLimit;
            if (!long.TryParse(_controlFields[3].Text.Trim(), out ipLimit) || ipLimit < 1 || ipLimit > 10 ||
                !long.TryParse(_controlFields[4].Text.Trim(), out accountLimit) || accountLimit < 1 || accountLimit > 20)
            {
                NoticeError("Лимит IP должен быть 1–10 в минуту, лимит аккаунта — 1–20 за 5 минут.");
                return;
            }
            var body = new Dictionary<string, object>
            {
                { "min_android_version", _controlFields[0].Text.Trim() },
                { "min_ios_version", _controlFields[1].Text.Trim() },
                { "min_desktop_version", _controlFields[2].Text.Trim() },
                { "registration_open", _registrationOpen.Checked },
                { "automatic_login_protection", _automaticProtection.Checked },
                { "login_ip_limit", ipLimit }, { "login_account_limit", accountLimit },
                { "reason", AdministrativeReason }
            };
            _savingOperationsControls = true;
            try
            {
                await auth.SendAsync(HttpMethod.Post, "admin/operations/controls", body, true, true);
                if (!IsCurrentAuth(auth, generation)) return;
                if (editRevision == _operationsEditRevision) _operationsControlsDirty = false;
                Notice("Версии клиентов и защита входа сохранены." + (_operationsControlsDirty ?
                    " Новые изменения на экране ещё не сохранены." : ""));
            }
            finally { _savingOperationsControls = false; }
            if (!IsCurrentAuth(auth, generation)) return;
            await RefreshOperationsAsync(true);
        }

        private Dictionary<string, object> SelectedOperator()
        {
            return Selected(_operators);
        }

        private async Task ChangeSelectedOperatorAsync(string role)
        {
            if (!IsOwner || _auth == null) return;
            var selected = SelectedOperator();
            if (selected == null) { Notice("Выберите администратора в таблице."); return; }
            if (Bool(selected, "is_current")) { NoticeError("Нельзя изменить собственную роль в активной сессии."); return; }
            var username = "@" + Value(selected, "username");
            var action = role == "revoke" ? "отозвать административную роль" : "назначить роль " + OperatorRole(role);
            if (MessageBox.Show(this, "Для " + username + " будет выполнено действие: " + action +
                ".\n\nАктивные административные сессии пользователя завершатся.", "Изменение роли",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            string enteredReason;
            if (!TextPrompt.TryShow(this, "Причина изменения роли", "Причина для журнала аудита", out enteredReason) ||
                string.IsNullOrWhiteSpace(enteredReason)) return;
            await _auth.SendAsync(HttpMethod.Post, "admin/operators/" + Uri.EscapeDataString(Value(selected, "user_id")),
                new Dictionary<string, object> { { "role", role }, { "reason", enteredReason.Trim() } }, true, true);
            Notice("Роль для " + username + " обновлена.");
            await RefreshOperationsAsync(true);
        }

        private async Task ResetSelectedOperator2FaAsync()
        {
            if (!IsOwner || _auth == null) return;
            var selected = SelectedOperator();
            if (selected == null) { Notice("Выберите администратора в таблице."); return; }
            if (Bool(selected, "is_current")) { NoticeError("Для восстановления собственного входа используйте отдельную процедуру владельца на сервере. Этот сброс предназначен для другого администратора."); return; }
            if (!Bool(selected, "two_factor_configured")) { Notice("У этого администратора аутентификатор не подключён. Бумажный ключ этим действием не сбрасывается."); return; }
            var username = "@" + Value(selected, "username");
            if (MessageBox.Show(this, "Сбросить аутентификатор администратора " + username + "?\n\nВсе его административные сессии и запасные коды будут отозваны. Бумажный ключ сохранится. При следующем входе потребуется заново подключить аутентификатор.",
                "Сброс аутентификатора", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            string enteredReason;
            if (!TextPrompt.TryShow(this, "Причина сброса аутентификатора", "Причина для журнала аудита", out enteredReason) || string.IsNullOrWhiteSpace(enteredReason)) return;
            await _auth.SendAsync(HttpMethod.Post, "admin/operators/" + Uri.EscapeDataString(Value(selected, "user_id")) + "/reset-2fa",
                new Dictionary<string, object> { { "reason", enteredReason.Trim() } }, true, true);
            Notice("Аутентификатор для " + username + " сброшен. Прежний бумажный ключ сохранён; при следующем входе потребуется новая настройка аутентификатора.");
            await RefreshOperationsAsync(true);
        }

        private async Task PublishAnnouncementAsync()
        {
            if (!IsOwner || _auth == null) return;
            var title = _noticeTitle.Text.Trim();
            var bodyText = _noticeBody.Text.Trim();
            if (title.Length == 0 || bodyText.Length == 0) { NoticeError("Укажите заголовок и текст объявления."); return; }
            var severity = _noticeSeverity.SelectedIndex == 2 ? "critical" : _noticeSeverity.SelectedIndex == 1 ? "warning" : "info";
            var expires = DateTimeOffset.UtcNow.AddHours((double)_noticeHours.Value);
            if (MessageBox.Show(this, "Опубликовать сервисное объявление «" + title + "» на " + _noticeHours.Value + " ч.?",
                "Публикация объявления", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            await _auth.SendAsync(HttpMethod.Post, "admin/operations/announcements", new Dictionary<string, object>
            {
                { "title", title }, { "body", bodyText }, { "severity", severity },
                { "expires_at", expires.ToString("o") }, { "reason", AdministrativeReason }
            }, true, true);
            _noticeTitle.Clear();
            _noticeBody.Clear();
            Notice("Сервисное объявление опубликовано в /server-info.");
            await RefreshOperationsAsync(true);
        }

        private async Task DisableAnnouncementAsync()
        {
            if (!IsOwner || _auth == null) return;
            if (string.IsNullOrEmpty(_activeNoticeId)) { Notice("Активного объявления нет."); return; }
            if (MessageBox.Show(this, "Отключить текущее сервисное объявление?", "Отключение объявления",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            await _auth.SendAsync(HttpMethod.Post, "admin/operations/announcements/" + Uri.EscapeDataString(_activeNoticeId) + "/disable",
                new Dictionary<string, object>(), true, true);
            Notice("Сервисное объявление отключено.");
            await RefreshOperationsAsync(true);
        }

        private async Task ExportAuditCsvAsync()
        {
            if (_audit.Rows.Count == 0) { Notice("Сначала загрузите журнал аудита."); return; }
            using (var dialog = new SaveFileDialog
            {
                Title = "Экспорт журнала аудита",
                Filter = "CSV UTF-8 (*.csv)|*.csv",
                FileName = "coco-admin-audit-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".csv",
                AddExtension = true,
                OverwritePrompt = true
            })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                var lines = new List<string> { "Дата,Действие,Инициатор,Цель,Причина" };
                foreach (DataGridViewRow row in _audit.Rows)
                {
                    if (row.IsNewRow) continue;
                    lines.Add(string.Join(",", row.Cells.Cast<DataGridViewCell>().Select(cell => Csv(Convert.ToString(cell.Value) ?? ""))));
                }
                File.WriteAllLines(dialog.FileName, lines, new System.Text.UTF8Encoding(true));
                Notice("Экспортировано записей: " + (lines.Count - 1) + ".\n" + dialog.FileName);
            }
            await Task.FromResult(0);
        }

        private static string Csv(string value)
        {
            return "\"" + value.Replace("\"", "\"\"").Replace("\r", " ").Replace("\n", " ") + "\"";
        }

        private static string OperatorRole(string value)
        {
            return string.Equals(value, "owner", StringComparison.OrdinalIgnoreCase) ? "Owner" :
                string.Equals(value, "moderator", StringComparison.OrdinalIgnoreCase) ? "Moderator" : "Отозвана";
        }

        private async Task CreateDatabaseBackupAsync()
        {
            if (!AllowsLocalMaintenance) { NoticeError("Копирование базы этого ПК недоступно при входе на удалённый сервер."); return; }
            if (LocalOperationStateChanged != null) LocalOperationStateChanged(true);
            try
            {
                var result = await ServerControl.CreateDatabaseBackupAsync();
                MessageBox.Show(this, result.Combined, result.Ok ? "Backup создан" : "Backup не создан", MessageBoxButtons.OK,
                    result.Ok ? MessageBoxIcon.Information : MessageBoxIcon.Error);
            }
            finally { if (LocalOperationStateChanged != null) LocalOperationStateChanged(false); }
        }

        private async Task RestoreDatabaseBackupAsync()
        {
            if (!AllowsLocalMaintenance) { NoticeError("Восстановление базы этого ПК недоступно при входе на удалённый сервер."); return; }
            var auth = _auth;
            using (var dialog = new OpenFileDialog { Title = "Выберите проверенный PostgreSQL backup", Filter = "PostgreSQL custom dump (*.dump)|*.dump", Multiselect = false })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                if (MessageBox.Show(this, "Восстановить PostgreSQL из выбранного backup?\n\nПеред заменой будет создана дополнительная аварийная копия. Сервер перезапустится, текущие сессии прекратятся.",
                    "Опасная операция — восстановление", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                string confirmation;
                if (!TextPrompt.TryShow(this, "Подтверждение восстановления", "Введите точно: RESTORE COCO DATABASE", out confirmation) ||
                    confirmation != "RESTORE COCO DATABASE") return;
                if (!ReferenceEquals(auth, _auth) || !AllowsLocalMaintenance) return;
                if (LocalOperationStateChanged != null) LocalOperationStateChanged(true);
                try
                {
                    var result = await ServerControl.RestoreDatabaseBackupAsync(dialog.FileName, confirmation);
                    MessageBox.Show(this, result.Combined, result.Ok ? "Восстановление завершено" : "Восстановление не выполнено",
                        MessageBoxButtons.OK, result.Ok ? MessageBoxIcon.Information : MessageBoxIcon.Error);
                    if (result.Ok && LocalSessionInvalidated != null) LocalSessionInvalidated();
                }
                finally { if (LocalOperationStateChanged != null) LocalOperationStateChanged(false); }
            }
        }

        private async Task ResetDatabaseAsync()
        {
            if (!AllowsLocalMaintenance) { NoticeError("Сброс базы этого ПК недоступен при входе на удалённый сервер."); return; }
            var auth = _auth;
            if (MessageBox.Show(this,
                "Сначала будет создана резервная копия PostgreSQL. Затем база messenger будет пересоздана пустой и сервер перезапущен.\n\n" +
                "Будут удалены пользователи, чаты, сообщения, жалобы и серверные настройки в PostgreSQL. " +
                "Файлы MinIO и кэш Redis не удаляются. Не начинайте операцию без свободного места для резервной копии.",
                "Опасная операция — только Owner", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            string confirmation;
            if (!TextPrompt.TryShow(this, "Подтверждение сброса",
                "Введите точно: DELETE COCO DATABASE ON THIS PC", out confirmation) ||
                confirmation != "DELETE COCO DATABASE ON THIS PC") return;
            if (!ReferenceEquals(auth, _auth) || !AllowsLocalMaintenance) return;

            if (LocalOperationStateChanged != null) LocalOperationStateChanged(true);
            try
            {
                await auth.SendAsync(HttpMethod.Get, "admin/summary", null, true, true);
                if (!ReferenceEquals(auth, _auth) || !AllowsLocalMaintenance) return;
                var result = await ServerControl.ResetDatabaseAsync(confirmation);
                var output = result.Combined;
                if (output.Length > 5000) output = output.Substring(output.Length - 5000);
                MessageBox.Show(this, output, result.Ok ? "Сброс завершён" : "Сброс не завершён",
                    MessageBoxButtons.OK, result.Ok ? MessageBoxIcon.Information : MessageBoxIcon.Error);
                if (result.Ok && LocalSessionInvalidated != null) LocalSessionInvalidated();
            }
            finally
            {
                if (LocalOperationStateChanged != null) LocalOperationStateChanged(false);
            }
        }

        private async Task RefreshSummaryAsync()
        {
            var auth = _auth;
            var generation = _authGeneration;
            if (auth == null) return;
            Dictionary<string, object> data;
            try { data = await auth.SendAsync(HttpMethod.Get, "admin/summary", null, true, true); }
            catch (ApiException ex)
            {
                // Only the known summary endpoint is a session probe. A 404
                // for an avatar or deleted user must not log the admin out.
                if ((ex.Status == 401 || ex.Status == 404) && IsCurrentAuth(auth, generation) && LocalSessionInvalidated != null)
                    LocalSessionInvalidated();
                throw;
            }
            if (!IsCurrentAuth(auth, generation)) return;
            var storage = Object(data, "storage");
            var values = new[]
            {
                Value(data, "open_reports"), Value(data, "in_progress_reports"),
                Value(data, "unresolved_feedback"), Value(data, "reports_last_24h"),
                Value(data, "active_account_suspensions"), Value(data, "active_installation_suspensions"),
                storage == null ? "—" : Bytes(Value(storage, "used_bytes")),
                string.Equals(auth.AdminRole, "owner", StringComparison.OrdinalIgnoreCase) ? "Owner" : "Moderator"
            };
            for (var index = 0; index < values.Length; index++)
                if (_summaryMetricValues[index] != null) _summaryMetricValues[index].Text = Empty(values[index], "0");
            _summarySessionTitle.Text = "Административная сессия · " +
                (string.Equals(auth.AdminRole, "owner", StringComparison.OrdinalIgnoreCase) ? "полный доступ" : "модерация");
            _summary.Text =
                "Вход на выбранный сервер: " + TokenExpiryText(auth.AccessTokenExpiresAt) + Environment.NewLine + Environment.NewLine +
                "Админ-сессия: до 12 часов бездействия, максимум 12 часов." + Environment.NewLine +
                "После истечения потребуется войти с паролем и выбранным способом подтверждения. " +
                "Способ можно изменить в разделе «Мой вход».";
        }

        private static string TokenExpiryText(DateTimeOffset expiresAt)
        {
            if (expiresAt == default(DateTimeOffset)) return "срок истечения неизвестен";
            var remaining = expiresAt - DateTimeOffset.UtcNow;
            return remaining <= TimeSpan.Zero
                ? "истёк (" + expiresAt.ToLocalTime().ToString("HH:mm:ss dd.MM.yyyy") + ")"
                : "истекает через " + FormatDuration(remaining) + " (" +
                  expiresAt.ToLocalTime().ToString("HH:mm:ss dd.MM.yyyy") + ")";
        }

        private static string FormatDuration(TimeSpan duration)
        {
            if (duration.TotalDays >= 1) return Math.Floor(duration.TotalDays) + " дн.";
            if (duration.TotalHours >= 1) return Math.Floor(duration.TotalHours) + " ч " + duration.Minutes + " мин";
            if (duration.TotalMinutes >= 1) return Math.Max(1, (int)Math.Ceiling(duration.TotalMinutes)) + " мин";
            return Math.Max(1, (int)Math.Ceiling(duration.TotalSeconds)) + " сек";
        }

        private async Task RefreshUsersAsync(bool reset)
        {
            var auth = _auth;
            var generation = _authGeneration;
            var requestVersion = ++_usersRefreshVersion;
            if (auth == null) return;
            if (!reset && string.IsNullOrEmpty(_usersCursor)) reset = true;
            var cursor = reset ? null : _usersCursor;
            var query = new List<string> { "limit=100" };
            if (!string.IsNullOrWhiteSpace(_userSearch.Text))
                query.Add("query=" + Uri.EscapeDataString(_userSearch.Text.Trim()));
            var status = _userStatus.SelectedIndex == 1 ? "active" : _userStatus.SelectedIndex == 2 ? "restricted" : "";
            if (status.Length > 0) query.Add("status=" + status);
            if (!string.IsNullOrEmpty(cursor)) query.Add("cursor=" + Uri.EscapeDataString(cursor));
            var data = await auth.SendAsync(HttpMethod.Get, "admin/users?" + string.Join("&", query), null, true, true);
            if (!IsCurrentAuth(auth, generation) || requestVersion != _usersRefreshVersion) return;
            if (reset) _users.Rows.Clear();
            foreach (var item in Items(data))
            {
                var row = _users.Rows[_users.Rows.Add(
                    Value(item, "reason"), Value(item, "description"), AccountStatusLabel(Value(item, "status")),
                    Value(item, "installation_blocks"))];
                row.Tag = item;
            }
            _usersCursor = Value(data, "next_cursor");
            _userInfo.Text = _users.Rows.Count + " пользователей · сортировка по имени";
        }

        private static string AccountStatusLabel(string status)
        {
            switch (status)
            {
                case "active": return "Активен";
                case "suspended": return "Заблокирован";
                case "read_only": return "Только чтение";
                default: return status;
            }
        }

        private async Task ShowSelectedUserAsync()
        {
            var items = SelectedItems(_users);
            if (items.Count == 0) { Notice("Сначала выберите пользователя."); return; }
            if (items.Count != 1) { NoticeError("Для карточки профиля выберите ровно одного пользователя."); return; }
            var item = items[0];
            var detail = await _auth.SendAsync(HttpMethod.Get,
                "admin/users/" + Uri.EscapeDataString(Value(item, "user_id")), null, true, true);
            using (var dialog = new UserCardDialog(this, _auth, detail))
                dialog.ShowDialog(this);
        }

        private void UpdateUserActionLabels()
        {
            var items = SelectedItems(_users);
            if (_userSuspensionButton != null)
            {
                _userSuspensionButton.Enabled = items.Count > 0;
                _userSuspensionButton.Text = items.Count > 1
                    ? "Изменить ограничения выбранных"
                    : (items.Count == 1 &&
                       string.Equals(Value(items[0], "status"), "suspended", StringComparison.OrdinalIgnoreCase)
                        ? "Восстановить аккаунт"
                        : "Заблокировать аккаунт");
            }
            if (_grantModeratorButton != null) _grantModeratorButton.Enabled = IsOwner && items.Count == 1;
        }

        private async Task DeleteSelectedUserAsync()
        {
            if (!IsOwner) { NoticeError("Удалять аккаунты может только владелец сервера."); return; }
            var items = SelectedItems(_users);
            if (items.Count == 0) { NoticeError("Сначала выберите одного или нескольких пользователей."); return; }
            var auth = _auth;
            if (auth == null) return;
            var targets = new List<Tuple<string, string>>();
            var skipped = 0;
            var errors = new List<string>();
            foreach (var item in items)
            {
                var userId = Value(item, "user_id");
                if (string.IsNullOrWhiteSpace(userId)) { skipped++; continue; }
                try
                {
                    var detail = await auth.SendAsync(HttpMethod.Get,
                        "admin/users/" + Uri.EscapeDataString(userId), null, true, true);
                    var profile = Object(detail, "profile");
                    var username = Value(profile, "username");
                    if (Bool(detail, "is_administrator") ||
                        string.Equals(userId, auth.UserId, StringComparison.OrdinalIgnoreCase))
                    {
                        skipped++;
                        continue;
                    }
                    if (string.IsNullOrWhiteSpace(username)) { skipped++; continue; }
                    targets.Add(Tuple.Create(userId, username));
                }
                catch (Exception ex)
                {
                    errors.Add(userId + ": " + ex.Message);
                }
            }

            if (targets.Count == 0)
            {
                NoticeError("Нет аккаунтов, разрешённых к удалению." +
                    (errors.Count == 0 ? "" : Environment.NewLine + string.Join(Environment.NewLine, errors.Take(5))));
                return;
            }

            string reason;
            if (targets.Count == 1)
            {
                string confirmation;
                if (!DeleteUserDialog.TryShow(this, targets[0].Item2, out confirmation, out reason)) return;
            }
            else
            {
                if (!BulkDeleteDialog.TryShow(this, targets.Select(target => target.Item2).ToArray(), out reason)) return;
            }

            var deleted = 0;
            foreach (var target in targets)
            {
                try
                {
                    await auth.SendAsync(HttpMethod.Post,
                        "admin/users/" + Uri.EscapeDataString(target.Item1) + "/delete",
                        new Dictionary<string, object>
                        {
                            { "confirm_username", target.Item2 },
                            { "reason", reason }
                        }, true, true);
                    deleted++;
                }
                catch (Exception ex)
                {
                    errors.Add("@" + target.Item2 + ": " + ex.Message);
                }
            }
            await RefreshUsersAsync(true);
            await RefreshSummaryAsync();
            var summary = "Удалено аккаунтов: " + deleted + ". Пропущено защищённых: " + skipped + ".";
            if (errors.Count == 0) Notice(summary);
            else NoticeError(summary + Environment.NewLine + string.Join(Environment.NewLine, errors.Take(5)));
        }

        private async Task GrantSelectedModeratorAsync()
        {
            if (!IsOwner || _auth == null) { NoticeError("Назначать серверные роли может только Owner."); return; }
            var targets = SelectedItems(_users);
            if (targets.Count != 1) { Notice("Выберите одного пользователя."); return; }
            var target = targets[0];
            var userId = Value(target, "user_id");
            var username = "@" + Value(target, "username");
            if (string.IsNullOrEmpty(userId)) { NoticeError("У выбранной строки нет ID пользователя."); return; }
            if (MessageBox.Show(this, "Назначить " + username + " роль Moderator?\n\nПользователь сам выберет защиту входа: аутентификатор, бумажный ключ или оба способа.",
                "Назначение модератора", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            string enteredReason;
            if (!TextPrompt.TryShow(this, "Причина назначения", "Причина для журнала аудита", out enteredReason) ||
                string.IsNullOrWhiteSpace(enteredReason)) return;
            await _auth.SendAsync(HttpMethod.Post, "admin/operators/" + Uri.EscapeDataString(userId),
                new Dictionary<string, object> { { "role", "moderator" }, { "reason", enteredReason.Trim() } }, true, true);
            Notice(username + " назначен Moderator. Роль появится в разделе «Управление».");
            await RefreshOperationsAsync(false);
        }

        private async Task ToggleUserSuspensionAsync()
        {
            var items = SelectedItems(_users);
            if (items.Count == 0) { NoticeError("Сначала выберите одного или нескольких пользователей."); return; }
            var auth = _auth;
            if (auth == null) return;
            var targets = new List<Tuple<string, string, bool>>();
            var skipped = 0;
            var errors = new List<string>();
            foreach (var item in items)
            {
                var userId = Value(item, "user_id");
                if (string.IsNullOrWhiteSpace(userId)) { skipped++; continue; }
                try
                {
                    var detail = await auth.SendAsync(HttpMethod.Get,
                        "admin/users/" + Uri.EscapeDataString(userId), null, true, true);
                    if (!Bool(detail, "suspend_allowed")) { skipped++; continue; }
                    var profile = Object(detail, "profile");
                    var username = Value(profile, "username");
                    var moderation = Object(detail, "moderation");
                    var blocked = string.Equals(Value(moderation, "status"), "active", StringComparison.OrdinalIgnoreCase);
                    targets.Add(Tuple.Create(userId, username, blocked));
                }
                catch (Exception ex)
                {
                    errors.Add(userId + ": " + ex.Message);
                }
            }
            if (targets.Count == 0)
            {
                NoticeError("Нет аккаунтов, для которых разрешено изменение ограничений." +
                    (errors.Count == 0 ? "" : Environment.NewLine + string.Join(Environment.NewLine, errors.Take(5))));
                return;
            }

            string reason;
            if (!TextPrompt.TryShow(this, "Причина массового изменения",
                "Укажите причину. Она будет записана в журнал аудита для каждого аккаунта.",
                out reason, "Продолжить")) return;
            var blockCount = targets.Count(target => target.Item3);
            var restoreCount = targets.Count - blockCount;
            if (MessageBox.Show(this,
                "Изменить ограничения для аккаунтов: " + targets.Count + "?\n\n" +
                "Будут заблокированы: " + blockCount + ". Будут восстановлены: " + restoreCount + ".\n" +
                "Причина: " + reason,
                "Массовое изменение ограничений", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            var changed = 0;
            foreach (var target in targets)
            {
                try
                {
                    await auth.SendAsync(HttpMethod.Post, "admin/users/" + Uri.EscapeDataString(target.Item1) + "/suspension",
                        new Dictionary<string, object>
                        {
                            { "blocked", target.Item3 },
                            { "include_installations", false },
                            { "reason", reason }
                        }, true, true);
                    changed++;
                }
                catch (Exception ex)
                {
                    errors.Add("@" + target.Item2 + ": " + ex.Message);
                }
            }
            await RefreshUsersAsync(true);
            await RefreshSummaryAsync();
            var summary = "Изменено ограничений: " + changed + ". Пропущено: " + skipped + ".";
            if (errors.Count == 0) Notice(summary);
            else NoticeError(summary + Environment.NewLine + string.Join(Environment.NewLine, errors.Take(5)));
        }

        private async Task ReviewReportAsync()
        {
            var items = SelectedItems(_reports);
            if (items.Count == 0) { NoticeError("Сначала выберите одну или несколько жалоб."); return; }
            ReviewDialogResult review;
            if (!ReviewDialog.TryShow(this, "Рассмотрение жалоб (выбрано: " + items.Count + ")",
                new[] { "open", "in_progress", "resolved", "dismissed" }, "in_progress", out review)) return;
            var reason = review.DecisionReason;
            if ((review.Status == "resolved" || review.Status == "dismissed") && string.IsNullOrWhiteSpace(reason))
                reason = AdministrativeReason;
            var changed = 0;
            var errors = new List<string>();
            foreach (var item in items)
            {
                try
                {
                    await _auth.SendAsync(HttpMethod.Post, "admin/reports/" + Uri.EscapeDataString(Value(item, "id")),
                        new Dictionary<string, object>
                        {
                            { "status", review.Status },
                            { "note", review.Note },
                            { "decision_reason", reason }
                        }, true, true);
                    changed++;
                }
                catch (Exception ex)
                {
                    errors.Add(Value(item, "id") + ": " + ex.Message);
                }
            }
            await RefreshReportsAsync(true);
            await RefreshSummaryAsync();
            if (errors.Count == 0) Notice("Обработано жалоб: " + changed + ".");
            else NoticeError("Обработано: " + changed + ". Ошибки:" + Environment.NewLine +
                string.Join(Environment.NewLine, errors.Take(5)));
        }

        private async Task RefreshReportsAsync(bool reset)
        {
            var auth = _auth;
            var generation = _authGeneration;
            var requestVersion = ++_reportsRefreshVersion;
            if (auth == null) return;
            if (!reset && string.IsNullOrEmpty(_reportsCursor)) { Notice("Больше жалоб нет."); return; }
            var cursor = reset ? null : _reportsCursor;
            var path = "admin/reports?limit=100" +
                ReportStatusQuery(_reportStatus.SelectedIndex) +
                (string.IsNullOrEmpty(cursor) ? "" : "&cursor=" + Uri.EscapeDataString(cursor));
            var data = await auth.SendAsync(HttpMethod.Get, path, null, true, true);
            if (!IsCurrentAuth(auth, generation) || requestVersion != _reportsRefreshVersion) return;
            if (reset) _reports.Rows.Clear();
            foreach (var item in Items(data))
            {
                var row = _reports.Rows[_reports.Rows.Add(
                    Value(item, "created_at"),
                    ReportPerson(item, "reporter"),
                    ReportPerson(item, "reported"),
                    ReportReasonLabel(Value(item, "reason")),
                    ReviewStatusLabel(Value(item, "status")),
                    Value(item, "description"))];
                row.Tag = item;
            }
            _reportsCursor = Value(data, "next_cursor");
            _reportInfo.Text = _reports.Rows.Count == 0
                ? "По выбранному фильтру жалоб нет."
                : "Показано жалоб: " + _reports.Rows.Count +
                  (string.IsNullOrEmpty(_reportsCursor) ? " · загружены все" : " · доступны дополнительные записи");
        }

        private static string ReportPerson(Dictionary<string, object> item, string prefix)
        {
            var display = Value(item, prefix + "_display_name");
            var username = Value(item, prefix + "_username");
            if (!string.IsNullOrWhiteSpace(display) && !string.IsNullOrWhiteSpace(username))
                return display + " (@" + username + ")";
            if (!string.IsNullOrWhiteSpace(username)) return "@" + username;
            if (!string.IsNullOrWhiteSpace(display)) return display;
            return Value(item, prefix == "reporter" ? "reporter_user_id" : "user_id");
        }

        private static string ReportReasonLabel(string value)
        {
            switch ((value ?? "").Trim().ToLowerInvariant())
            {
                case "harassment": return "Оскорбления / домогательства";
                case "scam": return "Мошенничество";
                case "spam": return "Спам";
                default: return string.IsNullOrWhiteSpace(value) ? "—" : value;
            }
        }

        private static string ReviewStatusLabel(string value)
        {
            switch ((value ?? "").Trim().ToLowerInvariant())
            {
                case "open": return "Открыта";
                case "in_progress": return "В работе";
                case "resolved": return "Решена";
                case "dismissed": return "Отклонена";
                default: return string.IsNullOrWhiteSpace(value) ? "—" : value;
            }
        }

        private async Task RefreshFeedbackAsync(bool reset)
        {
            var auth = _auth;
            var generation = _authGeneration;
            var requestVersion = ++_feedbackRefreshVersion;
            if (auth == null) return;
            if (!reset && string.IsNullOrEmpty(_feedbackCursor)) { Notice("Больше обращений нет."); return; }
            var cursor = reset ? null : _feedbackCursor;
            var path = "admin/feedback?limit=100" +
                FeedbackStatusQuery(_feedbackStatus.SelectedIndex) +
                (string.IsNullOrEmpty(cursor) ? "" : "&cursor=" + Uri.EscapeDataString(cursor));
            var data = await auth.SendAsync(HttpMethod.Get, path, null, true, true);
            if (!IsCurrentAuth(auth, generation) || requestVersion != _feedbackRefreshVersion) return;
            if (reset) _feedback.Rows.Clear();
            foreach (var item in Items(data))
            {
                var row = _feedback.Rows[_feedback.Rows.Add(
                    Value(item, "created_at"), Value(item, "platform"), Value(item, "app_version"),
                    ReviewStatusLabel(Value(item, "status")), Value(item, "description"))];
                row.Tag = item;
            }
            _feedbackCursor = Value(data, "next_cursor");
            _feedbackInfo.Text = _feedback.Rows.Count == 0
                ? "По выбранному фильтру обращений нет."
                : "Показано обращений: " + _feedback.Rows.Count +
                  (string.IsNullOrEmpty(_feedbackCursor) ? " · загружены все" : " · доступны дополнительные записи");
        }

        private static string ReportStatusQuery(int selectedIndex)
        {
            switch (selectedIndex)
            {
                case 1: return "&status=open";
                case 2: return "&status=in_progress";
                case 3: return "&status=resolved";
                case 4: return "&status=dismissed";
                default: return "";
            }
        }

        private static string FeedbackStatusQuery(int selectedIndex)
        {
            switch (selectedIndex)
            {
                case 1: return "&status=open";
                case 2: return "&status=resolved";
                case 3: return "&status=dismissed";
                default: return "";
            }
        }

        private async Task ReviewFeedbackAsync()
        {
            var items = SelectedItems(_feedback);
            if (items.Count == 0) { NoticeError("Сначала выберите одно или несколько обращений."); return; }
            ReviewDialogResult review;
            if (!ReviewDialog.TryShow(this, "Рассмотрение обращений (выбрано: " + items.Count + ")",
                new[] { "open", "resolved", "dismissed" }, "resolved", out review)) return;
            var reason = review.DecisionReason;
            if ((review.Status == "resolved" || review.Status == "dismissed") && string.IsNullOrWhiteSpace(reason))
                reason = AdministrativeReason;
            var changed = 0;
            var errors = new List<string>();
            foreach (var item in items)
            {
                try
                {
                    await _auth.SendAsync(HttpMethod.Post,
                        "admin/feedback/" + Uri.EscapeDataString(Value(item, "id")) + "/review",
                        new Dictionary<string, object>
                        {
                            { "status", review.Status },
                            { "note", review.Note },
                            { "decision_reason", reason }
                        }, true, true);
                    changed++;
                }
                catch (Exception ex)
                {
                    errors.Add(Value(item, "id") + ": " + ex.Message);
                }
            }
            await RefreshFeedbackAsync(true);
            await RefreshSummaryAsync();
            if (errors.Count == 0) Notice("Обработано обращений: " + changed + ".");
            else NoticeError("Обработано: " + changed + ". Ошибки:" + Environment.NewLine +
                string.Join(Environment.NewLine, errors.Take(5)));
        }

        private async Task ShowFeedbackPerformanceAsync()
        {
            var selected = SelectedItems(_feedback);
            if (selected.Count != 1) { Notice("Выберите одно обращение."); return; }
            var session = _auth;
            if (session == null) return;
            var detail = await session.SendAsync(HttpMethod.Get,
                "admin/feedback/" + Uri.EscapeDataString(Value(selected[0], "id")), null, true, true);
            if (IsDisposed || !ReferenceEquals(session, _auth)) return;
            var snapshot = PerformanceDiagnosticsPanel.Field(detail, "performance") as Dictionary<string, object>;
            if (snapshot == null) { Notice("В этом обращении нет диагностики производительности."); return; }
            using (var dialog = new Form())
            using (var viewer = new PerformanceDiagnosticsPanel())
            {
                dialog.Text = "Диагностика обращения";
                dialog.ClientSize = new Size(1000, 650); dialog.StartPosition = FormStartPosition.CenterParent;
                dialog.Controls.Add(viewer); viewer.ShowReportSnapshot(snapshot);
                dialog.ShowDialog(this);
            }
        }

        private async Task ShowFeedbackScreenshotAsync()
        {
            var selected = SelectedItems(_feedback);
            if (selected.Count == 0) { NoticeError("Сначала выберите обращение."); return; }
            if (selected.Count != 1) { NoticeError("Для скриншота выберите ровно одно обращение."); return; }
            var item = selected[0];
            var detail = await _auth.SendAsync(HttpMethod.Get,
                "admin/feedback/" + Uri.EscapeDataString(Value(item, "id")), null, true, true);
            var screenshots = Items(ObjectValue(detail, "screenshots")).ToList();
            if (screenshots.Count == 0) { Notice("В этом обращении нет скриншотов."); return; }
            var choices = screenshots.Select(s => Value(s, "index") + " · " + Value(s, "mime_type") +
                " · " + Bytes(Value(s, "byte_size"))).ToArray();
            int index;
            if (!ChoiceDialog.TryShow(this, "Выберите скриншот", choices, out index)) return;
            var bytes = await _auth.GetBytesAsync("admin/feedback/" + Uri.EscapeDataString(Value(item, "id")) +
                "/screenshots/" + Uri.EscapeDataString(Value(screenshots[index], "index")));
            using (var stream = new System.IO.MemoryStream(bytes))
            using (var source = Image.FromStream(stream))
            using (var dialog = new Form())
            using (var picture = new PictureBox())
            {
                dialog.Text = "Скриншот обращения";
                dialog.StartPosition = FormStartPosition.CenterParent;
                dialog.ClientSize = new Size(900, 650);
                dialog.BackColor = UiTheme.Background;
                picture.Dock = DockStyle.Fill;
                picture.SizeMode = PictureBoxSizeMode.Zoom;
                picture.Image = new Bitmap(source);
                dialog.Controls.Add(picture);
                dialog.ShowDialog(this);
                picture.Image.Dispose();
            }
        }

        private async Task RefreshAuditAsync(bool reset)
        {
            var auth = _auth;
            var generation = _authGeneration;
            var requestVersion = ++_auditRefreshVersion;
            if (!IsOwner || auth == null) return;
            if (!reset && string.IsNullOrEmpty(_auditCursor)) { Notice("Больше записей в журнале нет."); return; }
            var cursor = reset ? null : _auditCursor;
            var path = "admin/audit?limit=100" +
                (string.IsNullOrEmpty(cursor) ? "" : "&cursor=" + Uri.EscapeDataString(cursor));
            var data = await auth.SendAsync(HttpMethod.Get, path, null, true, true);
            if (!IsCurrentAuth(auth, generation) || requestVersion != _auditRefreshVersion) return;
            if (reset) _audit.Rows.Clear();
            foreach (var item in Items(data))
            {
                var row = _audit.Rows[_audit.Rows.Add(
                    Value(item, "created_at"), Value(item, "action"), Value(item, "actor"),
                    Value(item, "target"), Value(item, "reason"))];
                row.Tag = item;
            }
            _auditCursor = Value(data, "next_cursor");
        }

        private async Task RefreshStorageAsync()
        {
            var auth = _auth;
            var generation = _authGeneration;
            var requestVersion = ++_storageRefreshVersion;
            if (!IsOwner || auth == null) return;
            var storage = await auth.SendAsync(HttpMethod.Get, "admin/storage", null, true, true);
            if (!IsCurrentAuth(auth, generation) || requestVersion != _storageRefreshVersion) return;
            _storageInfo.Text = "Использовано: " + Bytes(LongValue(storage, "used_bytes")) +
                " · резерв: " + Bytes(LongValue(storage, "reserved_bytes")) +
                " · бюджет: " + Bytes(LongValue(storage, "budget_bytes")) +
                " · предупреждение: " + (Bool(storage, "warning") ? "да" : "нет");
            var policy = await auth.SendAsync(HttpMethod.Get, "admin/server-policy", null, true, true);
            if (!IsCurrentAuth(auth, generation) || requestVersion != _storageRefreshVersion) return;
            ApplyServerPolicy(policy);
        }

        private void ApplyServerPolicy(Dictionary<string, object> policy)
        {
            if (_policyDirty || policy == null) return;
            _applyingPolicy = true;
            try
            {
                for (var i = 0; i < _policyNames.Length; i++) { _policyFields[i].Text = Value(policy, _policyNames[i]); _policyFields[i].Enabled = true; }
                _policyLoaded = true;
            }
            finally { _applyingPolicy = false; }
        }

        private bool IsCurrentAuth(AdminAuth auth, int generation)
        {
            return !IsDisposed && generation == _authGeneration && ReferenceEquals(auth, _auth);
        }

        private async Task PreviewCleanupAsync()
        {
            var plan = await _auth.SendAsync(HttpMethod.Post, "admin/storage/preview", null, true, true);
            _storagePlanId = Value(plan, "plan_id");
            _storagePlanBytes = LongValue(plan, "bytes");
            var items = Items(ObjectValue(plan, "items")).ToList();
            if (string.IsNullOrEmpty(_storagePlanId)) throw new InvalidOperationException("Сервер не вернул план очистки.");
            Notice("Предварительный просмотр: " + items.Count + " файлов, " + Bytes(_storagePlanBytes) +
                ". План действует 10 минут. Нажмите «Выполнить очистку» для подтверждения.");
        }

        private async Task RunCleanupAsync()
        {
            if (string.IsNullOrEmpty(_storagePlanId)) { Notice("Сначала создайте предварительный просмотр."); return; }
            if (MessageBox.Show(this, "Удалить найденные потерянные вложения (" + Bytes(_storagePlanBytes) + ")?",
                "Подтверждение очистки", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            var result = await _auth.SendAsync(HttpMethod.Post, "admin/storage/cleanup",
                new Dictionary<string, object> { { "plan_id", _storagePlanId } }, true, true);
            _storagePlanId = null;
            Notice("Очистка завершена. Удалено: " + Value(result, "deleted") +
                " · пропущено: " + Value(result, "skipped") + " · ошибок: " + Value(result, "failed"));
            await RefreshStorageAsync();
        }

        private async Task SavePolicyAsync()
        {
            if (!IsOwner || _auth == null || !_policyLoaded) throw new InvalidOperationException("Сначала загрузите правила выбранного сервера.");
            var auth = _auth;
            var generation = _authGeneration;
            var editRevision = _policyEditRevision;
            var values = new Dictionary<string, object>();
            for (var i = 0; i < _policyNames.Length; i++)
            {
                var value = _policyFields[i].Text.Trim();
                if (i >= 5)
                {
                    long number;
                    if (!long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out number))
                        throw new InvalidOperationException("Поля размера должны содержать целые числа байт.");
                    values.Add(_policyNames[i], number);
                }
                else values.Add(_policyNames[i], value);
            }
            if (MessageBox.Show(this, "Сохранить правила сервера? Часть полей видна пользователям.",
                "Подтверждение", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            if (!IsCurrentAuth(auth, generation)) return;
            await auth.SendAsync(HttpMethod.Post, "admin/server-policy", values, true, true);
            if (!IsCurrentAuth(auth, generation)) return;
            if (_policyEditRevision == editRevision) _policyDirty = false;
            Notice("Правила сервера сохранены." + (_policyDirty ? " Новые изменения на экране ещё не сохранены." : ""));
        }

        private Panel NewPage(string title)
        {
            var page = new Panel
            {
                Text = title,
                BackColor = UiTheme.Background,
                ForeColor = UiTheme.Text,
                Padding = new Padding(24, 16, 24, 24)
            };
            return page;
        }

        private static FlowLayoutPanel Toolbar()
        {
            return new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 56,
                MinimumSize = new Size(0, 56),
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                WrapContents = true,
                AutoScroll = false,
                Padding = new Padding(0, 6, 0, 10),
                BackColor = UiTheme.Background
            };
        }

        private static void ConfigureStatusFilter(ComboBox filter, string[] items)
        {
            filter.DropDownStyle = ComboBoxStyle.DropDownList;
            filter.Width = 150;
            filter.Margin = new Padding(0, 2, 8, 2);
            filter.Items.AddRange(items);
            filter.SelectedIndex = 0;
            UiTheme.StyleInput(filter);
        }

        private static void ConfigureListInfo(Label label, string text)
        {
            label.Text = text;
            label.Dock = DockStyle.Bottom;
            label.Height = 34;
            label.Padding = new Padding(4, 8, 0, 0);
            label.ForeColor = UiTheme.TextMuted;
            label.Font = UiTheme.BodySmall;
        }

        private static Button ActionButton(string text, Func<Task> action, int width)
        {
            var button = new Button
            {
                Text = text,
                Width = width,
                MinimumSize = new Size(width, 36),
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                Height = 36,
                Margin = new Padding(0, 2, 8, 2),
                FlatStyle = FlatStyle.Flat,
                BackColor = UiTheme.SurfaceRaised,
                ForeColor = UiTheme.Text,
                UseVisualStyleBackColor = false,
                Cursor = Cursors.Hand
            };
            var kind = text == "Обновить" || text.StartsWith("Рассмотреть") ||
                       text.StartsWith("Сохранить") || text == "Загрузить ещё"
                ? UiButtonKind.Primary
                : text.StartsWith("Удалить") || text.StartsWith("Сброс") ||
                  text.StartsWith("Выполнить") ? UiButtonKind.Danger : UiButtonKind.Secondary;
            UiTheme.StyleButton(button, kind);
            button.Padding = new Padding(10, 0, 10, 0);
            button.Click += async (s, e) =>
            {
                button.Enabled = false;
                try { await action(); }
                catch (Exception ex) { NoticeError(ex); }
                finally { if (!button.IsDisposed) button.Enabled = true; }
            };
            return button;
        }

        private static DataGridView CreateGrid(params string[] columns)
        {
            var grid = new DataGridView
            {
                Dock = DockStyle.Fill,
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
                GridColor = Color.FromArgb(36, 44, 60),
                EnableHeadersVisualStyles = false,
                CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal,
                ColumnHeadersHeight = 38,
                Font = new Font("Segoe UI", 9f)
            };
            foreach (var column in columns)
                grid.Columns.Add(new DataGridViewTextBoxColumn
                {
                    HeaderText = column,
                    ReadOnly = true,
                    SortMode = DataGridViewColumnSortMode.NotSortable
                });
            grid.ColumnHeadersDefaultCellStyle.BackColor = UiTheme.Header;
            grid.ColumnHeadersDefaultCellStyle.ForeColor = Color.FromArgb(180, 194, 215);
            grid.ColumnHeadersDefaultCellStyle.Font = new Font("Segoe UI Semibold", 8.5f);
            grid.ColumnHeadersDefaultCellStyle.Padding = new Padding(8, 0, 4, 0);
            grid.DefaultCellStyle.BackColor = UiTheme.Surface;
            grid.DefaultCellStyle.ForeColor = UiTheme.TextSecondary;
            grid.DefaultCellStyle.SelectionBackColor = Color.FromArgb(44, 58, 84);
            grid.DefaultCellStyle.SelectionForeColor = Color.White;
            grid.DefaultCellStyle.Padding = new Padding(8, 0, 4, 0);
            grid.AlternatingRowsDefaultCellStyle.BackColor = Color.FromArgb(21, 26, 36);
            grid.RowTemplate.Height = 36;
            UiTheme.StyleGrid(grid);
            return grid;
        }

        private static Dictionary<string, object> Selected(DataGridView grid)
        {
            return grid.SelectedRows.Count == 0 ? null : grid.SelectedRows[0].Tag as Dictionary<string, object>;
        }

        private static List<Dictionary<string, object>> SelectedItems(DataGridView grid)
        {
            var items = new List<Dictionary<string, object>>();
            if (grid == null) return items;
            foreach (DataGridViewRow row in grid.SelectedRows)
            {
                var item = row.Tag as Dictionary<string, object>;
                if (item != null) items.Add(item);
            }
            return items;
        }

        private static IEnumerable<Dictionary<string, object>> Items(Dictionary<string, object> envelope)
        {
            object value;
            if (envelope == null || !envelope.TryGetValue("items", out value)) yield break;
            foreach (var item in Items(value)) yield return item;
        }

        private static IEnumerable<Dictionary<string, object>> Items(object value)
        {
            var items = value as IEnumerable;
            if (items == null) yield break;
            foreach (var item in items)
            {
                var dict = item as Dictionary<string, object>;
                if (dict != null) yield return dict;
            }
        }

        private static Dictionary<string, object> Object(Dictionary<string, object> value, string key)
        {
            object item;
            if (value != null && value.TryGetValue(key, out item)) return item as Dictionary<string, object>;
            return null;
        }

        private static object ObjectValue(Dictionary<string, object> value, string key)
        {
            object item;
            return value != null && value.TryGetValue(key, out item) ? item : null;
        }

        private static string Value(Dictionary<string, object> value, string key)
        {
            object item;
            return value != null && value.TryGetValue(key, out item) && item != null
                ? Convert.ToString(item, CultureInfo.InvariantCulture)
                : "";
        }

        private static string Empty(string value, string fallback)
        {
            return string.IsNullOrWhiteSpace(value) ? fallback : value;
        }

        private static string LocalTime(string value)
        {
            DateTimeOffset time;
            return DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out time)
                ? time.ToLocalTime().ToString("dd.MM.yyyy HH:mm")
                : "—";
        }

        private static long LongValue(Dictionary<string, object> value, string key)
        {
            long result;
            return long.TryParse(Value(value, key), NumberStyles.Integer, CultureInfo.InvariantCulture, out result) ? result : 0;
        }

        private static bool Bool(Dictionary<string, object> value, string key)
        {
            bool result;
            return bool.TryParse(Value(value, key), out result) && result;
        }

        private static string Bytes(string value)
        {
            long number;
            if (!long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out number)) return "—";
            return Bytes(number);
        }

        private static string Bytes(long number)
        {
            var size = (double)Math.Max(number, 0);
            var units = new[] { "Б", "КБ", "МБ", "ГБ", "ТБ" };
            var unit = 0;
            while (size >= 1024 && unit < units.Length - 1) { size /= 1024; unit++; }
            return size.ToString(size >= 10 || unit == 0 ? "0" : "0.0", CultureInfo.InvariantCulture) + " " + units[unit];
        }

        private static void Notice(string message)
        {
            OperationResultDialog.Show(null, "UNYRI Admin Console", message, "", true);
        }

        private static void NoticeError(string message)
        {
            OperationResultDialog.Show(null, "UNYRI Admin Console",
                string.IsNullOrWhiteSpace(message) ? "Не удалось выполнить операцию." : message.Trim(), "", false);
        }

        private static void NoticeError(Exception error)
        {
            if (error == null)
            {
                NoticeError("Не удалось выполнить операцию.");
                return;
            }
            var api = error as ApiException;
            if (api == null)
            {
                OperationResultDialog.Show(null, "UNYRI Admin Console",
                    string.IsNullOrWhiteSpace(error.Message) ? "Не удалось выполнить операцию." : error.Message.Trim(),
                    error.ToString(), false);
                return;
            }
            var details = "HTTP: " + api.Status + Environment.NewLine +
                          "Код: " + (string.IsNullOrWhiteSpace(api.Code) ? "http" : api.Code);
            if (!string.IsNullOrWhiteSpace(api.ResponseBody))
                details += Environment.NewLine + Environment.NewLine + api.ResponseBody.Trim();
            OperationResultDialog.Show(null, "UNYRI Admin Console", api.Message, details, false);
        }

        private static void StyleDialog(Form dialog)
        {
            UiTheme.ApplyDialog(dialog);
        }

        private static void StyleInput(Control control)
        {
            UiTheme.StyleInput(control);
        }

        private static Button DialogButton(string text, int left, int top, int width)
        {
            var button = new Button
            {
                Text = text,
                Left = left,
                Top = top,
                Width = width,
                Height = 40
            };
            UiTheme.StyleButton(button, UiButtonKind.Primary);
            return button;
        }

        private static void StyleSecondaryButton(Button button)
        {
            UiTheme.StyleButton(button, UiButtonKind.Secondary);
        }

        private sealed class SegmentButton : Button
        {
            public bool Selected;

            protected override void OnPaint(PaintEventArgs e)
            {
                base.OnPaint(e);
                if (!Selected) return;
                var thickness = Math.Max(2, Height / 18);
                using (var pen = new Pen(UiTheme.Accent, thickness))
                    e.Graphics.DrawLine(pen, 10, Height - thickness, Width - 10, Height - thickness);
            }
        }

        private sealed class LiveMetricsChart : Control
        {
            private readonly Queue<Tuple<int, int, int>> _samples = new Queue<Tuple<int, int, int>>();

            public LiveMetricsChart()
            {
                DoubleBuffered = true;
                BackColor = UiTheme.SurfaceRaised;
                ForeColor = UiTheme.TextMuted;
                Font = UiTheme.BodySmall;
                MinimumSize = new Size(200, 90);
                UiTheme.Round(this, 10);
            }

            public void AddSample(int sessions, int connections, int foreground)
            {
                _samples.Enqueue(Tuple.Create(Math.Max(0, sessions), Math.Max(0, connections), Math.Max(0, foreground)));
                while (_samples.Count > 48) _samples.Dequeue();
                Invalidate();
            }

            public void Clear()
            {
                _samples.Clear();
                Invalidate();
            }

            protected override void OnPaint(PaintEventArgs e)
            {
                base.OnPaint(e);
                e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                var plot = new Rectangle(14, 22, Math.Max(1, Width - 28), Math.Max(1, Height - 34));
                using (var gridPen = new Pen(UiTheme.BorderSubtle))
                    for (var i = 0; i <= 3; i++)
                    {
                        var y = plot.Top + plot.Height * i / 3;
                        e.Graphics.DrawLine(gridPen, plot.Left, y, plot.Right, y);
                    }
                using (var sessionBrush = new SolidBrush(UiTheme.Info)) e.Graphics.DrawString("Сессии", Font, sessionBrush, 14, 3);
                using (var connectionBrush = new SolidBrush(UiTheme.Accent)) e.Graphics.DrawString("Подключения", Font, connectionBrush, 80, 3);
                using (var foregroundBrush = new SolidBrush(UiTheme.Success)) e.Graphics.DrawString("На экране", Font, foregroundBrush, 176, 3);
                if (_samples.Count < 2)
                {
                    using (var brush = new SolidBrush(UiTheme.TextMuted))
                        e.Graphics.DrawString("График заполнится после нескольких обновлений", Font, brush, plot.Left + 6, plot.Top + 12);
                    return;
                }
                var values = _samples.ToArray();
                var maximum = Math.Max(1, values.Max(value => Math.Max(value.Item1, Math.Max(value.Item2, value.Item3))));
                DrawSeries(e.Graphics, plot, values.Select(value => value.Item1).ToArray(), maximum, UiTheme.Info);
                DrawSeries(e.Graphics, plot, values.Select(value => value.Item2).ToArray(), maximum, UiTheme.Accent);
                DrawSeries(e.Graphics, plot, values.Select(value => value.Item3).ToArray(), maximum, UiTheme.Success);
            }

            private static void DrawSeries(Graphics graphics, Rectangle plot, int[] values, int maximum, Color color)
            {
                if (values.Length < 2) return;
                var points = new PointF[values.Length];
                for (var i = 0; i < values.Length; i++)
                {
                    var x = plot.Left + plot.Width * i / (float)(values.Length - 1);
                    var y = plot.Bottom - plot.Height * values[i] / (float)maximum;
                    points[i] = new PointF(x, y);
                }
                using (var pen = new Pen(color, 2f)) graphics.DrawLines(pen, points);
            }
        }

        private sealed class NoteView
        {
            public Panel Card;
            public Label Body;
            public string Text;
        }

        private sealed class UserCardDialog : Form
        {
            private readonly AdminAuth _auth;
            private readonly Control _ownerControl;
            private readonly ToolTip _tips = new ToolTip();
            private readonly List<NoteView> _noteViews = new List<NoteView>();
            private readonly List<Dictionary<string, object>> _timelineItems = new List<Dictionary<string, object>>();
            private readonly Label[] _accountValues = new Label[9];
            private readonly Label[] _deviceFields = new Label[5];
            private readonly SegmentButton[] _segments = new SegmentButton[6];
            private readonly Panel[] _pages = new Panel[6];
            private readonly PictureBox _avatarPicture = new PictureBox();
            private readonly Label _avatarInitials = new Label();
            private readonly Label _displayNameLabel = new Label();
            private readonly Label _usernameLabel = new Label();
            private readonly Label _accountChip = new Label();
            private readonly Label _devicesChip = new Label();
            private readonly Label _restrictedChip = new Label();
            private readonly Label _adminChip = new Label();
            private readonly Label _securityTitle = new Label();
            private readonly Label _securitySummary = new Label();
            private readonly Label _footerHint = new Label();
            private readonly Label _devicesSummary = new Label();
            private readonly Label _deviceHint = new Label();
            private readonly Label _historySummary = new Label();
            private readonly Label _historyState = new Label();
            private readonly Label _historyFooter = new Label();
            private readonly Label _notesSummary = new Label();
            private readonly Label _notesState = new Label();
            private readonly Label _notesFooter = new Label();
            private readonly Label[] _moderationValues = new Label[6];
            private readonly TextBox _bioBox = new TextBox();
            private readonly TextBox _deviceSearch = new TextBox();
            private readonly ComboBox _deviceStatusFilter = new ComboBox();
            private readonly DataGridView _devices = CreateGrid("Платформа", "Устройство", "Версия", "ОС", "Последняя активность", "Доступ");
            private readonly DataGridView _timeline = CreateGrid("Дата", "Событие", "Причина");
            private readonly DataGridView _securityEvents = CreateGrid("Дата", "Событие", "Устройство", "Подробности");
            private readonly FlowLayoutPanel _notesList = new FlowLayoutPanel();
            private readonly Panel _deviceGridCard = new Panel();
            private readonly Panel _deviceEmpty = new Panel();
            private readonly Panel _deviceDetailCard = new Panel();
            private readonly Panel _historyGridCard = new Panel();
            private readonly Panel _historyStateHost = new Panel();
            private readonly Panel _securityGridCard = new Panel();
            private readonly Panel _securityStateHost = new Panel();
            private readonly Label _securityEventsSummary = new Label();
            private readonly Label _securityEventsState = new Label();
            private readonly Label _securityEventsFooter = new Label();
            private readonly Label _readOnlyState = new Label();
            private readonly Label _noticeState = new Label();
            private readonly Label _profileModerationState = new Label();
            private readonly Label _diagnosticsState = new Label();
            private readonly Panel _notesStateHost = new Panel();
            private Button _photoButton;
            private Button _suspendButton;
            private Button _closeButton;
            private Button _logoutDeviceButton;
            private Button _logoutAllDevicesButton;
            private Button _restrictionButton;
            private Button _refreshProfileButton;
            private Button _copyHandleButton;
            private Button _recoveryCodeButton;
            private Button _historyRefreshButton;
            private Button _historyMoreButton;
            private Button _notesRefreshButton;
            private Button _notesMoreButton;
            private Button _addNoteButton;
            private Button _securityRefreshButton;
            private Button _securityMoreButton;
            private Button _sendNoticeButton;
            private Button _readOnlyButton;
            private Button _moderateProfileButton;
            private Button _exportDiagnosticsButton;
            private FlowLayoutPanel _footerActions;
            private TableLayoutPanel _root;
            private TableLayoutPanel _headerLayout;
            private TableLayoutPanel _deviceFieldsTable;
            private TableLayoutPanel _deviceDetailLayout;
            private RowStyle _devicesDetailRow;
            private RowStyle _devicesSummaryRow;
            private RowStyle _devicesGapRow;
            private Panel _headerBand;
            private Panel _securityPanel;

            private string _userId;
            private string _username;
            private bool _hasAvatar;
            private bool _suspendAllowed;
            private bool _isAdministrator;
            private Dictionary<string, object> _profile;
            private Dictionary<string, object> _moderation;
            private Dictionary<string, object> _security;
            private readonly List<Dictionary<string, object>> _securityEventItems = new List<Dictionary<string, object>>();
            private List<Dictionary<string, object>> _deviceItems = new List<Dictionary<string, object>>();
            private float _scale = 1f;
            private bool _ready;
            private bool _compact;
            private int _tabIndex;
            private string _timelineCursor;
            private string _notesCursor;
            private string _securityEventsCursor;
            private bool _timelineLoaded;
            private bool _timelineLoading;
            private bool _timelineHasMore;
            private bool _notesLoaded;
            private bool _notesLoading;
            private bool _notesHasMore;
            private bool _securityEventsLoaded;
            private bool _securityEventsLoading;
            private bool _securityEventsHasMore;
            private bool _inNotesLayout;
            private string _lastDeviceSelection;

            public UserCardDialog(IWin32Window owner, AdminAuth auth, Dictionary<string, object> detail)
            {
                _auth = auth;
                _ownerControl = owner as Control;
                ApplyDetail(detail);
                SuspendLayout();

                Text = "Профиль · @" + _username;
                StartPosition = FormStartPosition.CenterParent;
                FormBorderStyle = FormBorderStyle.Sizable;
                ShowInTaskbar = false;
                MaximizeBox = true;
                MinimizeBox = false;
                KeyPreview = true;
                ClientSize = new Size(1080, 720);
                MinimumSize = new Size(840, 440);
                _tips.AutoPopDelay = 15000;
                _tips.InitialDelay = 350;
                _tips.ReshowDelay = 80;
                _tips.ShowAlways = true;

                _root = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    BackColor = UiTheme.Background,
                    ColumnCount = 1,
                    RowCount = 4,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                _root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                _root.RowStyles.Add(new RowStyle(SizeType.Absolute, 124));
                _root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
                _root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                _root.RowStyles.Add(new RowStyle(SizeType.Absolute, 68));
                _root.Controls.Add(BuildHeaderBand(), 0, 0);
                _root.Controls.Add(BuildSegmentBar(), 0, 1);
                _root.Controls.Add(BuildContentHost(), 0, 2);
                _root.Controls.Add(BuildActionFooter(), 0, 3);
                Controls.Add(_root);
                UiTheme.ApplyForm(this);
                AcceptButton = null;
                CancelButton = _closeButton;
                KeyDown += OnDialogKeyDown;
                AutoScaleMode = AutoScaleMode.Dpi;
                AutoScaleDimensions = new SizeF(96f, 96f);
                ResumeLayout(true);
                SwitchTab(0);
                Shown += (s, e) =>
                {
                    SyncListWidths();
                    if (_tabIndex == 1) ActiveControl = _devices; else ActiveControl = _closeButton;
                };
                Shown += async (s, e) => { await LoadAvatarAsync(); };
            }

            protected override void OnLoad(EventArgs e)
            {
                base.OnLoad(e);
                _ready = true;
                _scale = DeviceDpi / 96f;
                ApplyDpiMetrics();
                ClampToWorkingArea();
                ApplyResponsiveMode();
                UpdateProfileState();
                RefreshDevicesView();
                SyncListWidths();
                UpdateFooterState();
            }

            protected override void OnDpiChanged(DpiChangedEventArgs e)
            {
                base.OnDpiChanged(e);
                if (!_ready) return;
                _scale = e.DeviceDpiNew / 96f;
                ApplyDpiMetrics();
                ApplyResponsiveMode();
                SyncListWidths();
            }

            protected override void OnResize(EventArgs e)
            {
                base.OnResize(e);
                if (!_ready || _root == null) return;
                ApplyResponsiveMode();
                SyncListWidths();
            }

            protected override void Dispose(bool disposing)
            {
                if (disposing)
                {
                    try
                    {
                        if (_avatarPicture.Image != null)
                        {
                            _avatarPicture.Image.Dispose();
                            _avatarPicture.Image = null;
                        }
                    }
                    catch (Exception) { }
                    try { _tips.Dispose(); } catch (Exception) { }
                }
                base.Dispose(disposing);
            }

            private int ScaleInt(int logical)
            {
                return (int)Math.Round(logical * (_scale <= 0 ? 1f : _scale));
            }

            private static Label MakeCaption(string text)
            {
                return new Label
                {
                    Text = text,
                    Dock = DockStyle.Fill,
                    ForeColor = UiTheme.TextMuted,
                    Font = UiTheme.BodySmall,
                    TextAlign = ContentAlignment.MiddleLeft,
                    AutoEllipsis = true,
                    Padding = new Padding(0, 0, 8, 0)
                };
            }

            private static Label MakeValueLabel()
            {
                return new Label
                {
                    Text = "",
                    Dock = DockStyle.Fill,
                    ForeColor = UiTheme.Text,
                    Font = UiTheme.Body,
                    TextAlign = ContentAlignment.MiddleLeft,
                    AutoEllipsis = true,
                    Padding = new Padding(0, 0, 4, 0)
                };
            }

            private static Label MakeSectionTitle(string text)
            {
                return new Label
                {
                    Text = text,
                    Dock = DockStyle.Top,
                    Height = 26,
                    Font = UiTheme.Semibold,
                    ForeColor = UiTheme.TextSecondary,
                    TextAlign = ContentAlignment.MiddleLeft,
                    AutoEllipsis = true
                };
            }

            private static TableLayoutPanel MakeFields(int rows, int captionWidth, int rowHeight)
            {
                var table = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 2,
                    RowCount = rows,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, captionWidth));
                table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                for (var i = 0; i < rows; i++) table.RowStyles.Add(new RowStyle(SizeType.Absolute, rowHeight));
                return table;
            }

            private static void AddFieldRow(TableLayoutPanel table, int row, string caption, Control value)
            {
                table.Controls.Add(MakeCaption(caption), 0, row);
                table.Controls.Add(value, 1, row);
            }

            private static Panel MakeCard()
            {
                var card = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(20, 16, 20, 16) };
                UiTheme.StyleCard(card, 12);
                return card;
            }

            private static void StyleChipBase(Label chip)
            {
                chip.AutoSize = true;
                chip.Font = UiTheme.Semibold;
                chip.Padding = new Padding(8, 3, 8, 3);
                chip.Margin = new Padding(0, 0, 8, 0);
                chip.TextAlign = ContentAlignment.MiddleCenter;
                chip.BackColor = UiTheme.SurfaceRaised;
                chip.ForeColor = UiTheme.TextSecondary;
                UiTheme.Round(chip, 11);
            }

            private static Color Blend(Color background, Color accent, float amount)
            {
                if (amount < 0) amount = 0;
                if (amount > 1) amount = 1;
                return Color.FromArgb(
                    (int)Math.Round(background.R + (accent.R - background.R) * amount),
                    (int)Math.Round(background.G + (accent.G - background.G) * amount),
                    (int)Math.Round(background.B + (accent.B - background.B) * amount));
            }

            private static void SetChip(Label chip, string text, Color foreground, Color accent)
            {
                chip.Text = text;
                chip.ForeColor = foreground;
                chip.BackColor = Blend(UiTheme.Surface, accent, 0.22f);
            }

            private Button MakeButton(string text, UiButtonKind kind, Func<Task> action)
            {
                var button = new Button
                {
                    Text = text,
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlatStyle = FlatStyle.Flat,
                    UseVisualStyleBackColor = false,
                    Cursor = Cursors.Hand,
                    Margin = new Padding(0, 0, 8, 0)
                };
                UiTheme.StyleButton(button, kind);
                button.Padding = new Padding(14, 8, 14, 8);
                if (action != null)
                {
                    button.Click += async (s, e) =>
                    {
                        button.Enabled = false;
                        try { await action(); }
                        catch (Exception ex) { NoticeError(ex); }
                        finally
                        {
                            if (!button.IsDisposed)
                            {
                                button.Enabled = true;
                                RefreshManagedButtons();
                            }
                        }
                    };
                }
                return button;
            }

            private Control BuildHeaderBand()
            {
                _headerBand = new Panel
                {
                    Dock = DockStyle.Fill,
                    BackColor = UiTheme.Header,
                    Margin = new Padding(0),
                    Padding = new Padding(24, 10, 20, 8)
                };
                _headerBand.Paint += (s, e) =>
                {
                    using (var pen = new Pen(UiTheme.BorderSubtle))
                        e.Graphics.DrawLine(pen, 0, _headerBand.Height - 1, _headerBand.Width, _headerBand.Height - 1);
                };

                _avatarInitials.Text = Initials();
                _avatarInitials.Dock = DockStyle.Fill;
                _avatarInitials.TextAlign = ContentAlignment.MiddleCenter;
                _avatarInitials.Font = new Font("Segoe UI Semibold", 20f);
                _avatarInitials.ForeColor = UiTheme.Accent;
                _avatarInitials.BackColor = UiTheme.AccentSoft;
                _avatarInitials.Cursor = _hasAvatar ? Cursors.Hand : Cursors.Default;
                _avatarInitials.Click += (s, e) => BeginOpenAvatar();
                _avatarPicture.Dock = DockStyle.Fill;
                _avatarPicture.SizeMode = PictureBoxSizeMode.Zoom;
                _avatarPicture.BackColor = UiTheme.AccentSoft;
                _avatarPicture.Cursor = _hasAvatar ? Cursors.Hand : Cursors.Default;
                _avatarPicture.Click += (s, e) => BeginOpenAvatar();
                _avatarPicture.Visible = false;
                var avatarFrame = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(1), BackColor = UiTheme.AccentSoft };
                avatarFrame.Controls.Add(_avatarPicture);
                avatarFrame.Controls.Add(_avatarInitials);
                var avatarHost = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(0, 4, 16, 4), BackColor = UiTheme.Header };
                avatarHost.Controls.Add(avatarFrame);

                _photoButton = MakeButton("Открыть фото", UiButtonKind.Secondary, OpenAvatarAsync);
                _photoButton.Margin = new Padding(0, 0, 8, 0);

                var identity = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(0, 0, 0, 0), BackColor = UiTheme.Header };
                _displayNameLabel.Dock = DockStyle.Top;
                _displayNameLabel.Height = 32;
                _displayNameLabel.Font = UiTheme.Title;
                _displayNameLabel.ForeColor = UiTheme.Text;
                _displayNameLabel.AutoEllipsis = true;
                _displayNameLabel.Text = DisplayName();
                _usernameLabel.Dock = DockStyle.Top;
                _usernameLabel.Height = 20;
                _usernameLabel.Font = UiTheme.BodySmall;
                _usernameLabel.ForeColor = UiTheme.TextMuted;
                _usernameLabel.AutoEllipsis = true;
                _usernameLabel.Text = HeaderMeta();
                var chips = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    WrapContents = false,
                    AutoScroll = false,
                    FlowDirection = FlowDirection.LeftToRight,
                    Margin = new Padding(0),
                    Padding = new Padding(0, 5, 0, 0),
                    BackColor = UiTheme.Header
                };
                StyleChipBase(_accountChip);
                StyleChipBase(_devicesChip);
                StyleChipBase(_restrictedChip);
                StyleChipBase(_adminChip);
                _restrictedChip.Visible = false;
                _adminChip.Visible = _isAdministrator;
                chips.Controls.Add(_accountChip);
                chips.Controls.Add(_devicesChip);
                chips.Controls.Add(_restrictedChip);
                chips.Controls.Add(_adminChip);
                identity.Controls.Add(chips);
                identity.Controls.Add(_usernameLabel);
                identity.Controls.Add(_displayNameLabel);

                _refreshProfileButton = MakeButton("Обновить", UiButtonKind.Secondary, RefreshDetailAsync);
                _copyHandleButton = MakeButton("Копировать @имя", UiButtonKind.Ghost, CopyUsernameAsync);
                _refreshProfileButton.Margin = new Padding(0, 0, 8, 0);
                _copyHandleButton.Margin = new Padding(0);
                var quickActions = new FlowLayoutPanel
                {
                    Dock = DockStyle.Bottom,
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlowDirection = FlowDirection.LeftToRight,
                    WrapContents = false,
                    BackColor = UiTheme.Surface,
                    Margin = new Padding(0),
                    Padding = new Padding(0, 2, 0, 0)
                };
                quickActions.Controls.Add(_refreshProfileButton);
                quickActions.Controls.Add(_copyHandleButton);

                _securityTitle.Dock = DockStyle.Top;
                _securityTitle.Height = 22;
                _securityTitle.Font = UiTheme.Semibold;
                _securityTitle.ForeColor = UiTheme.Text;
                _securityTitle.TextAlign = ContentAlignment.MiddleLeft;
                _securitySummary.Dock = DockStyle.Top;
                _securitySummary.Height = 22;
                _securitySummary.Font = UiTheme.BodySmall;
                _securitySummary.ForeColor = UiTheme.TextMuted;
                _securitySummary.TextAlign = ContentAlignment.MiddleLeft;
                _securitySummary.AutoEllipsis = true;
                _securityPanel = new Panel
                {
                    Dock = DockStyle.Fill,
                    Margin = new Padding(14, 0, 0, 0),
                    Padding = new Padding(14, 8, 14, 6)
                };
                UiTheme.StyleCard(_securityPanel, 12);
                _securityPanel.Controls.Add(quickActions);
                _securityPanel.Controls.Add(_securitySummary);
                _securityPanel.Controls.Add(_securityTitle);

                _headerLayout = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 3,
                    RowCount = 1,
                    Margin = new Padding(0),
                    Padding = new Padding(0),
                    BackColor = UiTheme.Header
                };
                _headerLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 72));
                _headerLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                _headerLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 390));
                _headerLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                _headerLayout.Controls.Add(avatarHost, 0, 0);
                _headerLayout.Controls.Add(identity, 1, 0);
                _headerLayout.Controls.Add(_securityPanel, 2, 0);
                _headerBand.Controls.Add(_headerLayout);
                return _headerBand;
            }

            private Control BuildSegmentBar()
            {
                var bar = new Panel
                {
                    Dock = DockStyle.Fill,
                    BackColor = UiTheme.Background,
                    Margin = new Padding(0),
                    Padding = new Padding(24, 9, 24, 9)
                };
                var flow = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    FlowDirection = FlowDirection.LeftToRight,
                    WrapContents = false,
                    AutoScroll = false,
                    BackColor = UiTheme.Background,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                var names = new[] { "Профиль", "Устройства", "Безопасность", "История", "Заметки", "Действия" };
                for (var i = 0; i < names.Length; i++)
                {
                    var index = i;
                    var button = new SegmentButton
                    {
                        Text = names[i],
                        AutoSize = true,
                        AutoSizeMode = AutoSizeMode.GrowAndShrink,
                        FlatStyle = FlatStyle.Flat,
                        UseVisualStyleBackColor = false,
                        Cursor = Cursors.Hand,
                        Font = UiTheme.Semibold,
                        Margin = new Padding(0, 0, 6, 0),
                        Padding = new Padding(16, 7, 16, 7)
                    };
                    button.FlatAppearance.BorderSize = 1;
                    UiTheme.Round(button, 9);
                    button.Click += (s, e) => SwitchTab(index);
                    _segments[i] = button;
                    flow.Controls.Add(button);
                }
                bar.Controls.Add(flow);
                return bar;
            }

            private Control BuildContentHost()
            {
                var host = new Panel
                {
                    Dock = DockStyle.Fill,
                    BackColor = UiTheme.Background,
                    Margin = new Padding(0),
                    Padding = new Padding(24, 0, 24, 14)
                };
                _pages[0] = BuildProfilePage();
                _pages[1] = BuildDevicesPage();
                _pages[2] = BuildSecurityPage();
                _pages[3] = BuildHistoryPage();
                _pages[4] = BuildNotesPage();
                _pages[5] = BuildActionsPage();
                for (var i = 0; i < _pages.Length; i++)
                {
                    _pages[i].Dock = DockStyle.Fill;
                    _pages[i].Visible = false;
                    host.Controls.Add(_pages[i]);
                }
                return host;
            }

            private Control BuildActionFooter()
            {
                var footer = new Panel
                {
                    Dock = DockStyle.Fill,
                    BackColor = UiTheme.Surface,
                    Margin = new Padding(0),
                    Padding = new Padding(24, 10, 20, 10)
                };
                footer.Paint += (s, e) =>
                {
                    using (var pen = new Pen(UiTheme.BorderSubtle))
                        e.Graphics.DrawLine(pen, 0, 0, footer.Width, 0);
                };
                _footerActions = new FlowLayoutPanel
                {
                    Dock = DockStyle.Right,
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlowDirection = FlowDirection.LeftToRight,
                    WrapContents = false,
                    BackColor = UiTheme.Surface,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                _footerHint.Dock = DockStyle.Fill;
                _footerHint.Font = UiTheme.BodySmall;
                _footerHint.ForeColor = UiTheme.TextMuted;
                _footerHint.TextAlign = ContentAlignment.MiddleLeft;
                _footerHint.AutoEllipsis = true;
                _footerHint.Padding = new Padding(0, 0, 12, 0);

                _suspendButton = MakeButton("Заблокировать аккаунт", UiButtonKind.Danger, ToggleAccountSuspensionAsync);
                _closeButton = MakeButton("Закрыть", UiButtonKind.Secondary, null);
                _closeButton.DialogResult = DialogResult.Cancel;
                _closeButton.Click += (s, e) => Close();
                _logoutDeviceButton = MakeButton("Завершить сеанс", UiButtonKind.Secondary, LogoutSelectedDeviceAsync);
                _logoutAllDevicesButton = MakeButton("Завершить все активные", UiButtonKind.Danger, LogoutAllDevicesAsync);
                _restrictionButton = MakeButton("Запретить вход", UiButtonKind.Secondary, ToggleInstallationRestrictionAsync);
                _historyRefreshButton = MakeButton("Обновить", UiButtonKind.Secondary, LoadTimelineFirstPageAsync);
                _historyMoreButton = MakeButton("Ещё", UiButtonKind.Secondary, LoadTimelineNextPageAsync);
                _notesRefreshButton = MakeButton("Обновить", UiButtonKind.Secondary, LoadNotesFirstPageAsync);
                _notesMoreButton = MakeButton("Ещё", UiButtonKind.Secondary, LoadNotesNextPageAsync);
                _addNoteButton = MakeButton("Добавить заметку", UiButtonKind.Primary, AddNoteAsync);
                _recoveryCodeButton = MakeButton("Код восстановления", UiButtonKind.Secondary, IssueRecoveryCodeAsync);
                _securityRefreshButton = MakeButton("Обновить", UiButtonKind.Secondary, LoadSecurityFirstPageAsync);
                _securityMoreButton = MakeButton("Ещё", UiButtonKind.Secondary, LoadSecurityNextPageAsync);
                _footerActions.Controls.Add(_suspendButton);
                _footerActions.Controls.Add(_photoButton);
                _footerActions.Controls.Add(_recoveryCodeButton);
                _footerActions.Controls.Add(_logoutDeviceButton);
                _footerActions.Controls.Add(_logoutAllDevicesButton);
                _footerActions.Controls.Add(_restrictionButton);
                _footerActions.Controls.Add(_addNoteButton);
                _footerActions.Controls.Add(_notesRefreshButton);
                _footerActions.Controls.Add(_historyRefreshButton);
                _footerActions.Controls.Add(_securityRefreshButton);
                _footerActions.Controls.Add(_notesMoreButton);
                _footerActions.Controls.Add(_historyMoreButton);
                _footerActions.Controls.Add(_securityMoreButton);
                _footerActions.Controls.Add(_closeButton);
                footer.Controls.Add(_footerHint);
                footer.Controls.Add(_footerActions);
                return footer;
            }

            private Panel BuildProfilePage()
            {
                var page = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, AutoScroll = true, Margin = new Padding(0), Padding = new Padding(0) };
                var card = MakeCard();
                card.Dock = DockStyle.Top;
                card.Height = 324;
                card.Padding = new Padding(20, 16, 20, 16);
                var title = MakeSectionTitle("Профиль пользователя");

                _bioBox.Multiline = true;
                _bioBox.ReadOnly = true;
                _bioBox.ScrollBars = ScrollBars.Vertical;
                _bioBox.BorderStyle = BorderStyle.None;
                _bioBox.BackColor = UiTheme.SurfaceRaised;
                _bioBox.ForeColor = UiTheme.Text;
                _bioBox.Font = UiTheme.Body;
                _bioBox.TabStop = false;
                _bioBox.Dock = DockStyle.Fill;

                _moderationValues[0] = MakeValueLabel();

                var idRow = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.SurfaceRaised };
                var idValue = MakeValueLabel();
                idValue.Font = UiTheme.Mono;
                idValue.Text = _userId;
                _accountValues[0] = idValue;
                var copy = new Button { Text = "Копировать", AutoSize = false, Width = 96, Height = 28, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = false, Cursor = Cursors.Hand, Margin = new Padding(0) };
                UiTheme.StyleButton(copy, UiButtonKind.Secondary);
                copy.Padding = new Padding(8, 0, 8, 0);
                copy.Width = copy.GetPreferredSize(Size.Empty).Width;
                copy.AutoSize = true;
                copy.AutoSizeMode = AutoSizeMode.GrowAndShrink;
                copy.Anchor = AnchorStyles.Right;
                copy.Dock = DockStyle.Right;
                copy.Click += async (s, e) => { await CopyUserIdAsync(); };
                idRow.Controls.Add(idValue);
                idRow.Controls.Add(copy);

                var leftTable = MakeFields(5, 132, 34);
                leftTable.Dock = DockStyle.Fill;
                _accountValues[7] = MakeValueLabel();
                _accountValues[8] = MakeValueLabel();
                AddFieldRow(leftTable, 0, "Имя", _accountValues[7]);
                AddFieldRow(leftTable, 1, "Username", _accountValues[8]);
                AddFieldRow(leftTable, 2, "О себе", _bioBox);
                AddFieldRow(leftTable, 3, "ID профиля", idRow);
                _accountValues[6] = MakeValueLabel();
                AddFieldRow(leftTable, 4, "Создан", _accountValues[6]);
                leftTable.RowStyles[2].Height = 86;

                var rightTable = MakeFields(6, 154, 36);
                rightTable.Dock = DockStyle.Fill;
                AddFieldRow(rightTable, 0, "Состояние", _moderationValues[0]);
                _accountValues[1] = MakeValueLabel();
                AddFieldRow(rightTable, 1, "Ограничение до", _accountValues[1]);
                _accountValues[2] = MakeValueLabel();
                AddFieldRow(rightTable, 2, "Причина", _accountValues[2]);
                _accountValues[3] = MakeValueLabel();
                AddFieldRow(rightTable, 3, "Жалобы", _accountValues[3]);
                _accountValues[4] = MakeValueLabel();
                AddFieldRow(rightTable, 4, "Устройства", _accountValues[4]);
                _accountValues[5] = MakeValueLabel();
                AddFieldRow(rightTable, 5, "Заметки", _accountValues[5]);

                var leftTitle = MakeSectionTitle("Основные данные");
                var rightTitle = MakeSectionTitle("Модерация и доступ");
                var leftHost = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(0, 4, 18, 0), BackColor = UiTheme.Surface };
                var rightHost = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(18, 4, 0, 0), BackColor = UiTheme.Surface };
                leftHost.Controls.Add(leftTable);
                leftHost.Controls.Add(leftTitle);
                rightHost.Controls.Add(rightTable);
                rightHost.Controls.Add(rightTitle);
                rightHost.Paint += (s, e) =>
                {
                    using (var pen = new Pen(UiTheme.BorderSubtle)) e.Graphics.DrawLine(pen, 0, 8, 0, rightHost.Height - 8);
                };
                var split = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1, Margin = new Padding(0), Padding = new Padding(0) };
                split.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 54));
                split.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 46));
                split.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                split.Controls.Add(leftHost, 0, 0);
                split.Controls.Add(rightHost, 1, 0);
                card.Controls.Add(split);
                card.Controls.Add(title);
                page.Controls.Add(card);
                return page;
            }

            private Panel BuildDevicesPage()
            {
                var page = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                var layout = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 4,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                _devicesSummaryRow = new RowStyle(SizeType.Absolute, 44);
                layout.RowStyles.Add(_devicesSummaryRow);
                layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                _devicesGapRow = new RowStyle(SizeType.Absolute, 10);
                layout.RowStyles.Add(_devicesGapRow);
                _devicesDetailRow = new RowStyle(SizeType.Absolute, 164);
                layout.RowStyles.Add(_devicesDetailRow);

                _devicesSummary.Dock = DockStyle.Fill;
                _devicesSummary.Font = UiTheme.BodySmall;
                _devicesSummary.ForeColor = UiTheme.TextSecondary;
                _devicesSummary.TextAlign = ContentAlignment.MiddleLeft;
                _devicesSummary.AutoEllipsis = true;

                _deviceSearch.Width = 220;
                _deviceSearch.Height = 32;
                _deviceSearch.Margin = new Padding(0, 5, 8, 5);
                UiTheme.StyleInput(_deviceSearch);
                UiTheme.SetPlaceholder(_deviceSearch, "Поиск устройства");
                _deviceSearch.TextChanged += (s, e) => { if (_ready) RefreshDevicesView(); };
                _deviceStatusFilter.Width = 154;
                _deviceStatusFilter.Margin = new Padding(0, 5, 0, 5);
                UiTheme.StyleInput(_deviceStatusFilter);
                _deviceStatusFilter.Items.AddRange(new object[] { "Все статусы", "Активные", "Ограниченные", "Завершённые" });
                _deviceStatusFilter.SelectedIndex = 0;
                _deviceStatusFilter.SelectedIndexChanged += (s, e) => { if (_ready) RefreshDevicesView(); };
                var deviceFilters = new FlowLayoutPanel
                {
                    Dock = DockStyle.Right,
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlowDirection = FlowDirection.LeftToRight,
                    WrapContents = false,
                    BackColor = UiTheme.Background,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                deviceFilters.Controls.Add(_deviceSearch);
                deviceFilters.Controls.Add(_deviceStatusFilter);
                var devicesToolbar = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                devicesToolbar.Controls.Add(_devicesSummary);
                devicesToolbar.Controls.Add(deviceFilters);

                _deviceGridCard.Dock = DockStyle.Fill;
                _deviceGridCard.Margin = new Padding(0);
                _deviceGridCard.Padding = new Padding(1);
                UiTheme.StyleCard(_deviceGridCard, 12);
                ConfigureDevicesGrid();
                _deviceGridCard.Controls.Add(_devices);

                _deviceEmpty.Dock = DockStyle.Fill;
                _deviceEmpty.Margin = new Padding(0);
                _deviceEmpty.Visible = false;
                UiTheme.StyleCard(_deviceEmpty, 12);
                BuildEmptyState(_deviceEmpty, "Устройств нет", "Пользователь ещё не входил ни с одного устройства.\nЗдесь появятся его сеансы и ограничения.");

                var gridHost = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                gridHost.Controls.Add(_deviceGridCard);
                gridHost.Controls.Add(_deviceEmpty);

                _deviceDetailCard.Dock = DockStyle.Fill;
                _deviceDetailCard.Margin = new Padding(0);
                _deviceDetailCard.Padding = new Padding(16, 10, 16, 10);
                UiTheme.StyleCard(_deviceDetailCard, 12);
                var detailLayout = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 2,
                    RowCount = 2,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                detailLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                detailLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 0));
                detailLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                detailLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 22));
                _deviceDetailLayout = detailLayout;
                _deviceFieldsTable = MakeFields(5, 156, 24);
                var captions = new[] { "Устройство", "Платформа", "Версия / ОС", "Последняя активность", "Доступ" };
                for (var i = 0; i < captions.Length; i++)
                {
                    _deviceFields[i] = MakeValueLabel();
                    AddFieldRow(_deviceFieldsTable, i, captions[i], _deviceFields[i]);
                }
                _deviceHint.Dock = DockStyle.Fill;
                _deviceHint.Font = UiTheme.BodySmall;
                _deviceHint.ForeColor = UiTheme.TextMuted;
                _deviceHint.TextAlign = ContentAlignment.MiddleLeft;
                _deviceHint.AutoEllipsis = true;
                detailLayout.Controls.Add(_deviceFieldsTable, 0, 0);
                detailLayout.Controls.Add(_deviceHint, 0, 1);
                detailLayout.SetColumnSpan(_deviceHint, 2);
                _deviceDetailCard.Controls.Add(detailLayout);

                layout.Controls.Add(devicesToolbar, 0, 0);
                layout.Controls.Add(gridHost, 0, 1);
                layout.Controls.Add(new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) }, 0, 2);
                layout.Controls.Add(_deviceDetailCard, 0, 3);
                page.Controls.Add(layout);
                return page;
            }

            private Panel BuildSecurityPage()
            {
                var page = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                var layout = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 3,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 22));

                _securityEventsSummary.Dock = DockStyle.Fill;
                _securityEventsSummary.Font = UiTheme.BodySmall;
                _securityEventsSummary.ForeColor = UiTheme.TextSecondary;
                _securityEventsSummary.TextAlign = ContentAlignment.MiddleLeft;
                _securityEventsSummary.AutoEllipsis = true;
                _securityEventsSummary.Text = "Журнал безопасности";
                _securityEventsFooter.Dock = DockStyle.Fill;
                _securityEventsFooter.Font = UiTheme.BodySmall;
                _securityEventsFooter.ForeColor = UiTheme.TextMuted;
                _securityEventsFooter.TextAlign = ContentAlignment.MiddleLeft;
                _securityEventsFooter.AutoEllipsis = true;

                _securityGridCard.Dock = DockStyle.Fill;
                _securityGridCard.Margin = new Padding(0);
                _securityGridCard.Padding = new Padding(1);
                UiTheme.StyleCard(_securityGridCard, 12);
                ConfigureSecurityGrid();
                _securityGridCard.Controls.Add(_securityEvents);

                _securityEventsState.Dock = DockStyle.Fill;
                _securityEventsState.TextAlign = ContentAlignment.MiddleCenter;
                _securityEventsState.Font = UiTheme.Body;
                _securityEventsState.ForeColor = UiTheme.TextMuted;
                _securityEventsState.Padding = new Padding(30);
                _securityStateHost.Dock = DockStyle.Fill;
                _securityStateHost.Margin = new Padding(0);
                UiTheme.StyleCard(_securityStateHost, 12);
                _securityStateHost.Controls.Add(_securityEventsState);

                var host = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                host.Controls.Add(_securityGridCard);
                host.Controls.Add(_securityStateHost);
                _securityStateHost.Visible = false;
                layout.Controls.Add(_securityEventsSummary, 0, 0);
                layout.Controls.Add(host, 0, 1);
                layout.Controls.Add(_securityEventsFooter, 0, 2);
                page.Controls.Add(layout);
                return page;
            }

            private Panel BuildActionsPage()
            {
                var page = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, AutoScroll = true, Margin = new Padding(0) };
                var layout = new TableLayoutPanel
                {
                    Dock = DockStyle.Top,
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    ColumnCount = 2,
                    RowCount = 2,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
                layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 176));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 176));

                _sendNoticeButton = MakeButton("Отправить уведомление", UiButtonKind.Primary, SendNoticeAsync);
                _readOnlyButton = MakeButton("Включить только чтение", UiButtonKind.Secondary, ToggleReadOnlyAsync);
                _moderateProfileButton = MakeButton("Очистить профиль", UiButtonKind.Danger, ModerateProfileAsync);
                _exportDiagnosticsButton = MakeButton("Сохранить JSON", UiButtonKind.Secondary, ExportDiagnosticsAsync);

                layout.Controls.Add(ActionCard("Системное уведомление",
                    "Отправляет пользователю отдельное уведомление от администрации и записывает операцию в аудит.",
                    _noticeState, _sendNoticeButton, new Padding(0, 0, 8, 8)), 0, 0);
                layout.Controls.Add(ActionCard("Режим «Только чтение»",
                    "Пользователь сохраняет вход и чтение истории, но сервер блокирует сообщения, звонки и изменение данных.",
                    _readOnlyState, _readOnlyButton, new Padding(8, 0, 0, 8)), 1, 0);
                layout.Controls.Add(ActionCard("Модерация профиля",
                    "Удаление фото, сброс отображаемого имени и очистка описания выполняются только с обязательной причиной.",
                    _profileModerationState, _moderateProfileButton, new Padding(0, 8, 8, 0)), 0, 1);
                layout.Controls.Add(ActionCard("Экспорт диагностики",
                    "Сохраняет JSON без переписки, паролей, ключей и токенов: устройства, сеансы, push, безопасность и аудит.",
                    _diagnosticsState, _exportDiagnosticsButton, new Padding(8, 8, 0, 0)), 1, 1);
                page.Controls.Add(layout);
                return page;
            }

            private Panel ActionCard(string title, string description, Label state, Button action, Padding margin)
            {
                var card = MakeCard();
                card.Dock = DockStyle.Fill;
                card.Margin = margin;
                card.Padding = new Padding(18, 14, 18, 14);
                var table = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 4,
                    Margin = new Padding(0),
                    Padding = new Padding(0),
                    BackColor = UiTheme.Surface
                };
                table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                table.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                var heading = MakeSectionTitle(title);
                heading.Dock = DockStyle.Fill;
                heading.Margin = new Padding(0, 0, 0, 5);
                var text = new Label
                {
                    Text = description,
                    Dock = DockStyle.Fill,
                    Font = UiTheme.BodySmall,
                    ForeColor = UiTheme.TextSecondary,
                    AutoEllipsis = true,
                    TextAlign = ContentAlignment.TopLeft,
                    Margin = new Padding(0, 0, 0, 6)
                };
                state.Dock = DockStyle.Fill;
                state.AutoSize = true;
                state.Font = UiTheme.BodySmall;
                state.ForeColor = UiTheme.TextMuted;
                state.Margin = new Padding(0, 0, 0, 8);
                var buttons = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlowDirection = FlowDirection.LeftToRight,
                    WrapContents = false,
                    BackColor = UiTheme.Surface,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                buttons.Controls.Add(action);
                table.Controls.Add(heading, 0, 0);
                table.Controls.Add(text, 0, 1);
                table.Controls.Add(state, 0, 2);
                table.Controls.Add(buttons, 0, 3);
                card.Controls.Add(table);
                return card;
            }

            private Panel BuildHistoryPage()
            {
                var page = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                var layout = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 3,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 22));
                _historySummary.Dock = DockStyle.Fill;
                _historySummary.Font = UiTheme.BodySmall;
                _historySummary.ForeColor = UiTheme.TextSecondary;
                _historySummary.TextAlign = ContentAlignment.MiddleLeft;
                _historySummary.AutoEllipsis = true;
                _historySummary.Text = "История модерации";
                _historyFooter.Dock = DockStyle.Fill;
                _historyFooter.Font = UiTheme.BodySmall;
                _historyFooter.ForeColor = UiTheme.TextMuted;
                _historyFooter.TextAlign = ContentAlignment.MiddleLeft;
                _historyFooter.AutoEllipsis = true;

                _historyGridCard.Dock = DockStyle.Fill;
                _historyGridCard.Margin = new Padding(0);
                _historyGridCard.Padding = new Padding(1);
                UiTheme.StyleCard(_historyGridCard, 12);
                ConfigureTimelineGrid();
                _historyGridCard.Controls.Add(_timeline);

                _historyState.Dock = DockStyle.Fill;
                _historyState.TextAlign = ContentAlignment.MiddleCenter;
                _historyState.Font = UiTheme.Body;
                _historyState.ForeColor = UiTheme.TextMuted;
                _historyState.Padding = new Padding(30);
                _historyState.Visible = false;

                _historyStateHost.Dock = DockStyle.Fill;
                _historyStateHost.Margin = new Padding(0);
                UiTheme.StyleCard(_historyStateHost, 12);
                _historyStateHost.Controls.Add(_historyState);

                var host = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                host.Controls.Add(_historyGridCard);
                host.Controls.Add(_historyStateHost);
                _historyStateHost.Visible = false;

                layout.Controls.Add(_historySummary, 0, 0);
                layout.Controls.Add(host, 0, 1);
                layout.Controls.Add(_historyFooter, 0, 2);
                page.Controls.Add(layout);
                return page;
            }

            private Panel BuildNotesPage()
            {
                var page = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                var layout = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 3,
                    Margin = new Padding(0),
                    Padding = new Padding(0)
                };
                layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 22));
                _notesSummary.Dock = DockStyle.Fill;
                _notesSummary.Font = UiTheme.BodySmall;
                _notesSummary.ForeColor = UiTheme.TextSecondary;
                _notesSummary.TextAlign = ContentAlignment.MiddleLeft;
                _notesSummary.AutoEllipsis = true;
                _notesSummary.Text = "Внутренние заметки";
                _notesFooter.Dock = DockStyle.Fill;
                _notesFooter.Font = UiTheme.BodySmall;
                _notesFooter.ForeColor = UiTheme.TextMuted;
                _notesFooter.TextAlign = ContentAlignment.MiddleLeft;
                _notesFooter.AutoEllipsis = true;

                _notesList.Dock = DockStyle.Fill;
                _notesList.FlowDirection = FlowDirection.TopDown;
                _notesList.WrapContents = false;
                _notesList.AutoScroll = true;
                _notesList.BackColor = UiTheme.Background;
                _notesList.Margin = new Padding(0);
                _notesList.Padding = new Padding(0, 2, 0, 2);
                _notesList.Resize += (s, e) => RelayoutNotes();

                _notesState.Dock = DockStyle.Fill;
                _notesState.TextAlign = ContentAlignment.MiddleCenter;
                _notesState.Font = UiTheme.Body;
                _notesState.ForeColor = UiTheme.TextMuted;
                _notesState.Padding = new Padding(30);
                _notesState.Visible = false;

                _notesStateHost.Dock = DockStyle.Fill;
                _notesStateHost.Margin = new Padding(0);
                UiTheme.StyleCard(_notesStateHost, 12);
                _notesStateHost.Controls.Add(_notesState);

                var host = new Panel { Dock = DockStyle.Fill, BackColor = UiTheme.Background, Margin = new Padding(0) };
                host.Controls.Add(_notesList);
                host.Controls.Add(_notesStateHost);
                _notesStateHost.Visible = false;

                layout.Controls.Add(_notesSummary, 0, 0);
                layout.Controls.Add(host, 0, 1);
                layout.Controls.Add(_notesFooter, 0, 2);
                page.Controls.Add(layout);
                return page;
            }

            private static void BuildEmptyState(Panel host, string title, string description)
            {
                var block = new Panel { Dock = DockStyle.Fill, AutoScroll = true, BackColor = host.BackColor, Padding = new Padding(24, 12, 24, 16) };
                var heading = new Label
                {
                    Dock = DockStyle.Top,
                    Height = 40,
                    Text = title,
                    Font = UiTheme.Title,
                    ForeColor = UiTheme.TextSecondary,
                    TextAlign = ContentAlignment.MiddleCenter,
                    AutoEllipsis = true
                };
                var text = new Label
                {
                    Dock = DockStyle.Top,
                    Height = 70,
                    Text = description,
                    Font = UiTheme.BodySmall,
                    ForeColor = UiTheme.TextMuted,
                    TextAlign = ContentAlignment.TopCenter
                };
                block.Controls.Add(text);
                block.Controls.Add(heading);
                host.Controls.Add(block);
            }

            private void ConfigureDevicesGrid()
            {
                _devices.Dock = DockStyle.Fill;
                _devices.MultiSelect = false;
                _devices.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
                _devices.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
                _devices.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.AutoSize;
                _devices.ScrollBars = ScrollBars.Both;
                _devices.BorderStyle = BorderStyle.None;
                _devices.ReadOnly = true;
                _devices.DefaultCellStyle.SelectionBackColor = UiTheme.AccentSoft;
                _devices.DefaultCellStyle.SelectionForeColor = UiTheme.Text;
                var weights = new[] { 52f, 88f, 48f, 78f, 95f, 72f };
                for (var i = 0; i < _devices.Columns.Count && i < weights.Length; i++)
                    _devices.Columns[i].FillWeight = weights[i];
                _devices.SelectionChanged += (s, e) => { if (_ready) UpdateDeviceActions(); };
                _devices.CellFormatting += DevicesCellFormatting;
            }

            private void ConfigureTimelineGrid()
            {
                _timeline.Dock = DockStyle.Fill;
                _timeline.MultiSelect = false;
                _timeline.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
                _timeline.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
                _timeline.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.AutoSize;
                _timeline.ScrollBars = ScrollBars.Both;
                _timeline.BorderStyle = BorderStyle.None;
                _timeline.ReadOnly = true;
                _timeline.DefaultCellStyle.SelectionBackColor = UiTheme.AccentSoft;
                _timeline.DefaultCellStyle.SelectionForeColor = UiTheme.Text;
                var weights = new[] { 18f, 30f, 52f };
                for (var i = 0; i < _timeline.Columns.Count && i < weights.Length; i++)
                    _timeline.Columns[i].FillWeight = weights[i];
            }

            private void ConfigureSecurityGrid()
            {
                _securityEvents.Dock = DockStyle.Fill;
                _securityEvents.MultiSelect = false;
                _securityEvents.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
                _securityEvents.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
                _securityEvents.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.AutoSize;
                _securityEvents.ScrollBars = ScrollBars.Both;
                _securityEvents.BorderStyle = BorderStyle.None;
                _securityEvents.ReadOnly = true;
                _securityEvents.DefaultCellStyle.SelectionBackColor = UiTheme.AccentSoft;
                _securityEvents.DefaultCellStyle.SelectionForeColor = UiTheme.Text;
                var weights = new[] { 20f, 24f, 24f, 48f };
                for (var i = 0; i < _securityEvents.Columns.Count && i < weights.Length; i++)
                    _securityEvents.Columns[i].FillWeight = weights[i];
            }

            private void DevicesCellFormatting(object sender, DataGridViewCellFormattingEventArgs e)
            {
                if (e.RowIndex < 0 || e.ColumnIndex != 5) return;
                var text = Convert.ToString(e.Value) ?? "";
                if (text == "Активно") e.CellStyle.ForeColor = UiTheme.Success;
                else if (text == "Вход запрещён") e.CellStyle.ForeColor = UiTheme.Warning;
                else e.CellStyle.ForeColor = UiTheme.TextMuted;
            }

            private void ApplyDpiMetrics()
            {
                _devices.RowTemplate.Height = ScaleInt(40);
                _timeline.RowTemplate.Height = ScaleInt(40);
                _securityEvents.RowTemplate.Height = ScaleInt(40);
                _devices.ColumnHeadersDefaultCellStyle.Padding = new Padding(ScaleInt(10), 0, ScaleInt(8), 0);
                _devices.DefaultCellStyle.Padding = new Padding(ScaleInt(10), 0, ScaleInt(8), 0);
                _timeline.ColumnHeadersDefaultCellStyle.Padding = new Padding(ScaleInt(10), 0, ScaleInt(8), 0);
                _timeline.DefaultCellStyle.Padding = new Padding(ScaleInt(10), 0, ScaleInt(8), 0);
                _securityEvents.ColumnHeadersDefaultCellStyle.Padding = new Padding(ScaleInt(10), 0, ScaleInt(8), 0);
                _securityEvents.DefaultCellStyle.Padding = new Padding(ScaleInt(10), 0, ScaleInt(8), 0);
                var deviceMin = new[] { 100, 150, 80, 120, 168, 124 };
                for (var i = 0; i < _devices.Columns.Count && i < deviceMin.Length; i++)
                    _devices.Columns[i].MinimumWidth = ScaleInt(deviceMin[i]);
                var timelineMin = new[] { 140, 190, 260 };
                for (var i = 0; i < _timeline.Columns.Count && i < timelineMin.Length; i++)
                    _timeline.Columns[i].MinimumWidth = ScaleInt(timelineMin[i]);
                var securityMin = new[] { 140, 160, 180, 260 };
                for (var i = 0; i < _securityEvents.Columns.Count && i < securityMin.Length; i++)
                    _securityEvents.Columns[i].MinimumWidth = ScaleInt(securityMin[i]);
                _devices.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
                _devices.ColumnHeadersHeight = ScaleInt(44);
                _timeline.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
                _timeline.ColumnHeadersHeight = ScaleInt(44);
                _securityEvents.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
                _securityEvents.ColumnHeadersHeight = ScaleInt(44);
                _deviceFieldsTable.ColumnStyles[0].Width = ScaleInt(156);
                for (var i = 0; i < _deviceFieldsTable.RowStyles.Count; i++)
                    _deviceFieldsTable.RowStyles[i].Height = ScaleInt(24);
                MinimumSize = new Size(ScaleInt(840), ScaleInt(440));
            }

            private void ClampToWorkingArea()
            {
                var screen = Screen.FromControl(this);
                if (_ownerControl != null && _ownerControl.IsHandleCreated)
                {
                    try { screen = Screen.FromRectangle(_ownerControl.RectangleToScreen(_ownerControl.ClientRectangle)); }
                    catch (Exception) { }
                }
                if (screen == null) return;
                var working = screen.WorkingArea;
                var chromeW = Math.Max(0, Width - ClientSize.Width);
                var chromeH = Math.Max(0, Height - ClientSize.Height);
                var margin = Math.Max(8, ScaleInt(16));
                var maxW = Math.Max(ScaleInt(700), working.Width - chromeW - margin * 2);
                var maxH = Math.Max(ScaleInt(400), working.Height - chromeH - margin * 2);
                var width = Math.Min(ClientSize.Width, maxW);
                var height = Math.Min(ClientSize.Height, maxH);
                ClientSize = new Size(width, height);
                if (_ownerControl != null && _ownerControl.IsHandleCreated)
                {
                    try
                    {
                        var bounds = _ownerControl.RectangleToScreen(_ownerControl.ClientRectangle);
                        Location = new Point(bounds.Left + (bounds.Width - Width) / 2,
                            bounds.Top + (bounds.Height - Height) / 2);
                    }
                    catch (Exception) { }
                }
            }

            private void ApplyResponsiveMode()
            {
                _compact = ClientSize.Width < ScaleInt(920) || ClientSize.Height < ScaleInt(600);
                var tiny = _compact && ClientSize.Height < ScaleInt(520);
                _root.RowStyles[0].Height = ScaleInt(_compact ? 96 : 124);
                _root.RowStyles[1].Height = ScaleInt(_compact ? 60 : 64);
                _root.RowStyles[3].Height = ScaleInt(_compact ? 64 : 72);
                _headerBand.Padding = _compact ? new Padding(ScaleInt(14), ScaleInt(6), ScaleInt(12), ScaleInt(6)) : new Padding(ScaleInt(20), ScaleInt(10), ScaleInt(16), ScaleInt(8));
                if (_headerLayout != null)
                    _headerLayout.ColumnStyles[2].Width = _compact ? 0 : ScaleInt(390);
                if (_securityPanel != null) _securityPanel.Visible = !_compact;
                _usernameLabel.Visible = !_compact;
                _devicesSummaryRow.Height = ScaleInt(_compact ? 40 : 44);
                _devicesSummary.Visible = !_compact;
                _deviceSearch.Width = ScaleInt(_compact ? 170 : 220);
                _deviceStatusFilter.Width = ScaleInt(_compact ? 132 : 154);
                _devicesGapRow.Height = ScaleInt(tiny ? 4 : (_compact ? 6 : 10));
                _devicesDetailRow.Height = ScaleInt(tiny ? 112 : (_compact ? 132 : 164));
                for (var i = 0; i < _deviceFieldsTable.RowStyles.Count; i++)
                    _deviceFieldsTable.RowStyles[i].Height = ScaleInt(_compact ? 0 : 24);
                if (_compact) _deviceFieldsTable.RowStyles[0].Height = ScaleInt(24);
                _deviceHint.Visible = !tiny;
                if (_deviceDetailLayout != null)
                    _deviceDetailLayout.RowStyles[1].Height = ScaleInt(tiny ? 0 : 22);
                _footerHint.Text = "";
            }

            private void OnDialogKeyDown(object sender, KeyEventArgs e)
            {
                if (e.KeyCode != Keys.Escape || _tabIndex != 0) return;
                Close();
                e.Handled = true;
            }

            private void BeginOpenAvatar()
            {
                if (!_hasAvatar) return;
                var task = OpenAvatarAsync();
                task.ContinueWith(t => { var ignored = t.Exception; }, TaskContinuationOptions.OnlyOnFaulted);
            }

            private void ApplyDetail(Dictionary<string, object> detail)
            {
                _profile = Object(detail, "profile");
                _moderation = Object(detail, "moderation");
                _security = Object(detail, "security");
                _deviceItems = Items(ObjectValue(detail, "devices")).ToList();
                _userId = Value(_profile, "user_id");
                _username = Value(_profile, "username");
                if (string.IsNullOrWhiteSpace(_username)) _username = _userId;
                _hasAvatar = Bool(_profile, "has_avatar");
                _suspendAllowed = Bool(detail, "suspend_allowed");
                _isAdministrator = Bool(detail, "is_administrator");
            }

            private string DisplayName()
            {
                var name = Value(_profile, "display_name");
                if (!string.IsNullOrWhiteSpace(name)) return name;
                if (!string.IsNullOrWhiteSpace(_username) && _username != _userId) return "@" + _username;
                return string.IsNullOrWhiteSpace(_userId) ? "Пользователь" : _userId;
            }

            private string HeaderMeta()
            {
                var parts = new List<string>();
                parts.Add("@" + _username);
                var created = LocalTime(Value(_profile, "created_at"));
                if (!string.IsNullOrEmpty(created) && created != "—") parts.Add("создан " + created);
                return string.Join(" · ", parts);
            }

            private string Initials()
            {
                var name = Value(_profile, "display_name");
                if (string.IsNullOrWhiteSpace(name)) name = _username;
                if (string.IsNullOrWhiteSpace(name)) return "?";
                var clean = name.Trim();
                if (clean.StartsWith("@", StringComparison.Ordinal)) clean = clean.Substring(1);
                if (clean.Length == 0) return "?";
                if (clean.Length == 1) return clean.ToUpperInvariant();
                return (clean.Substring(0, 1) + clean.Substring(1, 1)).ToUpperInvariant();
            }

            private bool IsSuspended()
            {
                return string.Equals(Value(_moderation, "status"), "suspended", StringComparison.OrdinalIgnoreCase);
            }

            private bool IsReadOnly()
            {
                return Bool(_moderation, "read_only");
            }

            private int CountRestrictedDevices()
            {
                var count = 0;
                foreach (var device in _deviceItems)
                    if (Bool(device, "restriction_active")) count++;
                return count;
            }

            private void SwitchTab(int index)
            {
                if (index < 0 || index >= _pages.Length) return;
                _tabIndex = index;
                for (var i = 0; i < _pages.Length; i++)
                {
                    _pages[i].Visible = i == index;
                    StyleSegment(_segments[i], i == index);
                }
                _suspendButton.Visible = index == 0;
                _photoButton.Visible = index == 0;
                _recoveryCodeButton.Visible = index == 0 && _auth != null &&
                    string.Equals(_auth.AdminRole, "owner", StringComparison.OrdinalIgnoreCase) && !_isAdministrator;
                _logoutDeviceButton.Visible = index == 1;
                _logoutAllDevicesButton.Visible = index == 1;
                _restrictionButton.Visible = index == 1;
                _securityRefreshButton.Visible = index == 2;
                _addNoteButton.Visible = index == 4;
                _notesRefreshButton.Visible = index == 4;
                _historyRefreshButton.Visible = index == 3;
                _historyMoreButton.Visible = false;
                _notesMoreButton.Visible = false;
                _securityMoreButton.Visible = false;
                UpdateFooterState();
                if (index == 2 && !_securityEventsLoaded && !_securityEventsLoading) BeginLoadSecurity();
                if (index == 3 && !_timelineLoaded && !_timelineLoading) BeginLoadTimeline();
                if (index == 4 && !_notesLoaded && !_notesLoading) BeginLoadNotes();
            }

            private static void StyleSegment(SegmentButton button, bool selected)
            {
                button.Selected = selected;
                button.BackColor = selected ? UiTheme.AccentSoft : UiTheme.Background;
                button.ForeColor = selected ? UiTheme.Text : UiTheme.TextSecondary;
                button.FlatAppearance.BorderSize = selected ? 1 : 0;
                button.FlatAppearance.BorderColor = selected ? UiTheme.Accent : UiTheme.Background;
                button.FlatAppearance.MouseOverBackColor = selected ? UiTheme.AccentSoft : UiTheme.SurfaceHover;
                button.FlatAppearance.MouseDownBackColor = UiTheme.AccentSoft;
                button.Invalidate();
            }

            private void UpdateFooterState()
            {
                _closeButton.Visible = true;
                switch (_tabIndex)
                {
                    case 0:
                        _footerHint.Text = _suspendAllowed
                            ? "Действия модерации применяются сразу и записываются в журнал аудита."
                            : "Нет прав на блокировку аккаунта: администратор или текущий пользователь.";
                        break;
                    case 1:
                        UpdateDeviceActions();
                        break;
                    case 2:
                        _footerHint.Text = _securityEventsLoading ? "Загрузка журнала безопасности…" :
                            _securityEventsLoaded ? "Журнал безопасности: " + _securityEventItems.Count + " событий." :
                            "Журнал безопасности загружается по запросу.";
                        break;
                    case 3:
                        _footerHint.Text = _timelineLoading ? "Загрузка истории…" :
                            _timelineLoaded ? "История модерации: " + _timelineItems.Count + " событий." :
                            "История модерации загружается по запросу.";
                        break;
                    case 4:
                        _footerHint.Text = _notesLoading ? "Загрузка заметок…" :
                            _notesLoaded ? "Внутренние заметки: " + _noteViews.Count + "." :
                            "Внутренние заметки видны только администрации.";
                        break;
                    case 5:
                        _footerHint.Text = "Административные действия требуют причины и записываются в аудит.";
                        break;
                }
                UpdateTabActionStates();
            }

            private void RefreshManagedButtons()
            {
                UpdateDeviceActions();
                UpdateTabActionStates();
                UpdateSuspendButton();
            }

            private void UpdateTabActionStates()
            {
                _securityMoreButton.Visible = _tabIndex == 2 && _securityEventsHasMore;
                _securityMoreButton.Enabled = _securityEventsHasMore && !_securityEventsLoading;
                _securityRefreshButton.Enabled = !_securityEventsLoading;
                _historyMoreButton.Visible = _tabIndex == 3 && _timelineHasMore;
                _historyMoreButton.Enabled = _timelineHasMore && !_timelineLoading;
                _historyRefreshButton.Enabled = !_timelineLoading;
                _notesMoreButton.Visible = _tabIndex == 4 && _notesHasMore;
                _notesMoreButton.Enabled = _notesHasMore && !_notesLoading;
                _notesRefreshButton.Enabled = !_notesLoading;
                _addNoteButton.Enabled = !_notesLoading && _auth != null;
            }

            private void UpdateSuspendButton()
            {
                if (_suspendButton == null) return;
                if (!_suspendAllowed)
                {
                    _suspendButton.Text = "Ограничение недоступно";
                    _suspendButton.Enabled = false;
                    UiTheme.StyleButton(_suspendButton, UiButtonKind.Secondary);
                }
                else if (IsSuspended())
                {
                    _suspendButton.Text = "Восстановить аккаунт";
                    _suspendButton.Enabled = true;
                    UiTheme.StyleButton(_suspendButton, UiButtonKind.Primary);
                }
                else
                {
                    _suspendButton.Text = "Заблокировать аккаунт";
                    _suspendButton.Enabled = true;
                    UiTheme.StyleButton(_suspendButton, UiButtonKind.Danger);
                }
                _suspendButton.Padding = new Padding(14, 8, 14, 8);
            }

            private void UpdateProfileState()
            {
                if (_displayNameLabel != null) _displayNameLabel.Text = DisplayName();
                if (_usernameLabel != null) _usernameLabel.Text = HeaderMeta();
                if (Text != "Профиль · @" + _username) Text = "Профиль · @" + _username;
                _avatarInitials.Text = Initials();
                _photoButton.Enabled = _hasAvatar;

                var restricted = CountRestrictedDevices();
                var suspended = IsSuspended();
                var readOnly = IsReadOnly();
                SetChip(_accountChip, suspended ? "Ограничен" : readOnly ? "Только чтение" : "Активен",
                    suspended ? UiTheme.Warning : readOnly ? UiTheme.Info : UiTheme.Success,
                    suspended ? UiTheme.Warning : readOnly ? UiTheme.Info : UiTheme.Success);
                SetChip(_devicesChip, "Устройств: " + _deviceItems.Count, UiTheme.TextSecondary, UiTheme.TextSecondary);
                _restrictedChip.Visible = restricted > 0 && !suspended;
                if (restricted > 0 && !suspended)
                    SetChip(_restrictedChip, "Ограничено устройств: " + restricted, UiTheme.Danger, UiTheme.Danger);
                _adminChip.Visible = _isAdministrator;
                if (_isAdministrator) SetChip(_adminChip, "Администратор", UiTheme.Accent, UiTheme.Accent);

                var until = Value(_moderation, "until");
                var reason = Value(_moderation, "reason");
                if (_moderationValues[0] != null)
                {
                    _moderationValues[0].Text = suspended ? "Ограничен" : readOnly ? "Только чтение" : "Активен";
                    _moderationValues[0].ForeColor = suspended ? UiTheme.Warning : readOnly ? UiTheme.Info : UiTheme.Success;
                }
                if (_accountValues[1] != null)
                    _accountValues[1].Text = suspended ? (string.IsNullOrWhiteSpace(until) ? "Бессрочно" : LocalTime(until)) : "—";
                if (_accountValues[2] != null)
                    _accountValues[2].Text = suspended ? Empty(reason, "Не указана") : "—";
                if (_accountValues[3] != null)
                {
                    var total = Value(_moderation, "reports_total");
                    var open = Value(_moderation, "reports_open");
                    _accountValues[3].Text = total == "0" && open == "0"
                        ? "Жалоб нет"
                        : Empty(total, "0") + " всего · " + Empty(open, "0") + " открыто";
                }
                if (_accountValues[4] != null)
                {
                    _accountValues[4].Text = _deviceItems.Count + " всего · " + restricted + " ограничено";
                    _accountValues[4].ForeColor = restricted > 0 ? UiTheme.Warning : UiTheme.Text;
                }
                if (_accountValues[5] != null) _accountValues[5].Text = Empty(Value(_moderation, "notes"), "0");
                if (_accountValues[0] != null) _accountValues[0].Text = _userId;
                if (_accountValues[6] != null) _accountValues[6].Text = LocalTime(Value(_profile, "created_at"));
                if (_accountValues[7] != null) _accountValues[7].Text = DisplayName();
                if (_accountValues[8] != null) _accountValues[8].Text = "@" + _username;
                var bio = Value(_profile, "bio");
                _bioBox.Text = string.IsNullOrWhiteSpace(bio) ? "Не заполнено" : bio;
                _bioBox.ForeColor = string.IsNullOrWhiteSpace(bio) ? UiTheme.TextMuted : UiTheme.Text;
                _bioBox.ScrollBars = ScrollBars.Vertical;

                var activeSessions = CountActiveDevices();
                var openReports = Empty(Value(_moderation, "reports_open"), "0");
                var requiresAttention = suspended || readOnly || restricted > 0 || openReports != "0";
                var riskScore = Value(_security, "risk_score");
                var riskLevel = Value(_security, "risk_level");
                if (!string.IsNullOrEmpty(riskScore))
                {
                    _securityTitle.Text = "Риск " + riskScore + "/100 · " +
                        (riskLevel == "high" ? "высокий" : riskLevel == "watch" ? "нужна проверка" : "норма");
                    _securityTitle.ForeColor = riskLevel == "high" ? UiTheme.Danger : riskLevel == "watch" ? UiTheme.Warning : UiTheme.Success;
                    var reasons = new List<string>();
                    object rawReasons;
                    if (_security != null && _security.TryGetValue("reasons", out rawReasons) && rawReasons is IEnumerable)
                        foreach (var item in (IEnumerable)rawReasons) if (item != null) reasons.Add(Convert.ToString(item));
                    _securitySummary.Text = reasons.Count > 0 ? string.Join(" · ", reasons.Take(2)) :
                        activeSessions + " активных сеансов · критических сигналов нет";
                }
                else
                {
                    _securityTitle.Text = suspended ? "Доступ ограничен" : requiresAttention ? "Требует внимания" : "Безопасность в норме";
                    _securityTitle.ForeColor = suspended ? UiTheme.Danger : requiresAttention ? UiTheme.Warning : UiTheme.Success;
                    _securitySummary.Text = activeSessions + " активных сеансов · " + restricted +
                        " ограничено · " + openReports + " открытых жалоб";
                }
                _recoveryCodeButton.Enabled = _auth != null && !_isAdministrator;
                if (_readOnlyState != null)
                {
                    var readOnlyUntil = Value(_moderation, "read_only_until");
                    var readOnlyReason = Value(_moderation, "read_only_reason");
                    _readOnlyState.Text = readOnly
                        ? "Включён" + (string.IsNullOrWhiteSpace(readOnlyUntil) ? " бессрочно" : " до " + LocalTime(readOnlyUntil)) +
                          " · " + Empty(readOnlyReason, "причина не указана")
                        : "Сейчас отключён";
                    _readOnlyState.ForeColor = readOnly ? UiTheme.Warning : UiTheme.Success;
                }
                if (_readOnlyButton != null)
                {
                    _readOnlyButton.Text = readOnly ? "Отключить только чтение" : "Включить только чтение";
                    UiTheme.StyleButton(_readOnlyButton, readOnly ? UiButtonKind.Primary : UiButtonKind.Secondary);
                    _readOnlyButton.Enabled = _auth != null && _suspendAllowed;
                }
                if (_noticeState != null) _noticeState.Text = "Получатель: @" + _username + " · push и серверный журнал";
                if (_profileModerationState != null)
                    _profileModerationState.Text = _suspendAllowed ? "Доступно для обычного аккаунта" : "Недоступно для администратора или текущего аккаунта";
                if (_diagnosticsState != null) _diagnosticsState.Text = "Формат: coco-admin-user-diagnostics-v1";
                if (_sendNoticeButton != null) _sendNoticeButton.Enabled = _auth != null;
                if (_moderateProfileButton != null) _moderateProfileButton.Enabled = _auth != null && _suspendAllowed;
                if (_exportDiagnosticsButton != null) _exportDiagnosticsButton.Enabled = _auth != null;
                UpdateSuspendButton();
                UpdateFooterState();
            }

            private void BeginLoadTimeline()
            {
                var task = LoadTimelineAsync(true);
                task.ContinueWith(t => { var ignored = t.Exception; }, TaskContinuationOptions.OnlyOnFaulted);
            }

            private void BeginLoadSecurity()
            {
                var task = LoadSecurityAsync(true);
                task.ContinueWith(t => { var ignored = t.Exception; }, TaskContinuationOptions.OnlyOnFaulted);
            }

            private async Task LoadSecurityFirstPageAsync()
            {
                await LoadSecurityAsync(true);
            }

            private async Task LoadSecurityNextPageAsync()
            {
                await LoadSecurityAsync(false);
            }

            private async Task LoadSecurityAsync(bool reset)
            {
                if (_securityEventsLoading) return;
                if (_auth == null)
                {
                    ShowSecurityState("Нет активной административной сессии.\nЖурнал безопасности недоступен.", "Повторите вход в админ-консоль.", true);
                    return;
                }
                if (!reset && !_securityEventsHasMore) return;
                _securityEventsLoading = true;
                UpdateTabActionStates();
                try
                {
                    if (reset)
                    {
                        _securityEventsCursor = null;
                        _securityEventsHasMore = false;
                        _securityEventItems.Clear();
                        _securityEvents.Rows.Clear();
                    }
                    ShowSecurityState(reset ? "Загрузка журнала безопасности…" : "Загрузка следующей страницы…", "");
                    var path = "admin/users/" + Uri.EscapeDataString(_userId) + "/security-events?limit=50";
                    if (!reset && !string.IsNullOrEmpty(_securityEventsCursor))
                        path += "&cursor=" + Uri.EscapeDataString(_securityEventsCursor);
                    var data = await _auth.SendAsync(HttpMethod.Get, path, null, true, true);
                    foreach (var item in Items(data))
                    {
                        _securityEventItems.Add(item);
                        var metadata = Object(item, "metadata");
                        var detail = SecurityEventDetail(metadata);
                        var device = Empty(Value(item, "subject_device_id"), Empty(Value(item, "actor_device_id"), "—"));
                        var row = _securityEvents.Rows[_securityEvents.Rows.Add(
                            LocalTime(Value(item, "created_at")),
                            SecurityEventLabel(Value(item, "event_type")),
                            device,
                            detail)];
                        row.Tag = item;
                        row.Cells[3].ToolTipText = detail;
                    }
                    _securityEventsCursor = Value(data, "next_cursor");
                    _securityEventsHasMore = !string.IsNullOrEmpty(_securityEventsCursor);
                    _securityEventsLoaded = true;
                    if (_securityEventItems.Count == 0)
                        ShowSecurityState("Событий безопасности пока нет.", "");
                    else HideSecurityState();
                    _securityEventsSummary.Text = "Журнал безопасности · показано событий: " + _securityEventItems.Count;
                    _securityEventsFooter.Text = _securityEventsHasMore
                        ? "Показаны последние 50 событий · нажмите «Ещё», чтобы загрузить глубже"
                        : "Загружены все доступные события";
                    SetStatus("Журнал безопасности: " + _securityEventItems.Count + " событий.");
                }
                catch (Exception ex)
                {
                    if (_securityEventItems.Count == 0)
                        ShowSecurityState("Не удалось загрузить журнал.\n" + LimitText(ex.Message), ex.Message, true);
                    _securityEventsFooter.Text = "Ошибка загрузки журнала безопасности.";
                    SetStatus("Ошибка загрузки журнала: " + ex.Message, true);
                }
                finally
                {
                    _securityEventsLoading = false;
                    UpdateTabActionStates();
                    UpdateFooterState();
                }
            }

            private void BeginLoadNotes()
            {
                var task = LoadNotesAsync(true);
                task.ContinueWith(t => { var ignored = t.Exception; }, TaskContinuationOptions.OnlyOnFaulted);
            }

            private async Task LoadTimelineFirstPageAsync()
            {
                await LoadTimelineAsync(true);
            }

            private async Task LoadTimelineNextPageAsync()
            {
                await LoadTimelineAsync(false);
            }

            private async Task LoadTimelineAsync(bool reset)
            {
                if (_timelineLoading) return;
                if (_auth == null)
                {
                    ShowHistoryState("Нет активной административной сессии.\nИстория недоступна.", "Повторить вход в админ-консоль.", true);
                    return;
                }
                if (!reset && !_timelineHasMore) return;
                _timelineLoading = true;
                UpdateTabActionStates();
                try
                {
                    if (reset)
                    {
                        _timelineCursor = null;
                        _timelineHasMore = false;
                        _timelineItems.Clear();
                        _timeline.Rows.Clear();
                    }
                    ShowHistoryState(reset ? "Загрузка истории…" : "Загрузка следующей страницы…", "");
                    var path = "admin/users/" + Uri.EscapeDataString(_userId) + "/timeline?limit=50";
                    if (!reset && !string.IsNullOrEmpty(_timelineCursor))
                        path += "&cursor=" + Uri.EscapeDataString(_timelineCursor);
                    var data = await _auth.SendAsync(HttpMethod.Get, path, null, true, true);
                    foreach (var item in Items(data))
                    {
                        _timelineItems.Add(item);
                        var row = _timeline.Rows[_timeline.Rows.Add(
                            LocalTime(Value(item, "occurred_at")),
                            TimelineKindLabel(Value(item, "kind")),
                            TimelineReasonText(item))];
                        row.Tag = item;
                        row.Cells[2].ToolTipText = TimelineTooltip(item);
                    }
                    _timelineCursor = Value(data, "next_cursor");
                    _timelineHasMore = !string.IsNullOrEmpty(_timelineCursor);
                    _timelineLoaded = true;
                    if (_timelineItems.Count == 0)
                    {
                        ShowHistoryState("Событий пока нет.\nДействия администрации появятся здесь.", "");
                    }
                    else
                    {
                        HideHistoryState();
                    }
                    _historySummary.Text = "История модерации · показано событий: " + _timelineItems.Count;
                    _historyFooter.Text = _timelineHasMore ? "Показаны последние 50 событий · нажмите «Ещё», чтобы загрузить глубже" : "Загружены все доступные события";
                    SetStatus("История модерации: " + _timelineItems.Count + " событий.");
                }
                catch (Exception ex)
                {
                    if (_timelineItems.Count == 0)
                        ShowHistoryState("Не удалось загрузить историю.\n" + LimitText(ex.Message), ex.Message, true);
                    _historyFooter.Text = "Ошибка загрузки истории.";
                    SetStatus("Ошибка загрузки истории: " + ex.Message, true);
                }
                finally
                {
                    _timelineLoading = false;
                    UpdateTabActionStates();
                    UpdateFooterState();
                }
            }

            private async Task LoadNotesFirstPageAsync()
            {
                await LoadNotesAsync(true);
            }

            private async Task LoadNotesNextPageAsync()
            {
                await LoadNotesAsync(false);
            }

            private async Task LoadNotesAsync(bool reset)
            {
                if (_notesLoading) return;
                if (_auth == null)
                {
                    ShowNotesState("Нет активной административной сессии.\nЗаметки недоступны.", "Повторить вход в админ-консоль.", true);
                    return;
                }
                if (!reset && !_notesHasMore) return;
                _notesLoading = true;
                UpdateTabActionStates();
                try
                {
                    if (reset)
                    {
                        _notesCursor = null;
                        _notesHasMore = false;
                        _noteViews.Clear();
                        _notesList.Controls.Clear();
                    }
                    if (_noteViews.Count == 0) ShowNotesState(reset ? "Загрузка заметок…" : "Загрузка следующей страницы…", "");
                    var path = "admin/users/" + Uri.EscapeDataString(_userId) + "/notes?limit=50";
                    if (!reset && !string.IsNullOrEmpty(_notesCursor))
                        path += "&cursor=" + Uri.EscapeDataString(_notesCursor);
                    var data = await _auth.SendAsync(HttpMethod.Get, path, null, true, true);
                    foreach (var item in Items(data))
                    {
                        var view = CreateNoteView(item);
                        _noteViews.Add(view);
                        _notesList.Controls.Add(view.Card);
                    }
                    _notesCursor = Value(data, "next_cursor");
                    _notesHasMore = !string.IsNullOrEmpty(_notesCursor);
                    _notesLoaded = true;
                    if (_noteViews.Count == 0)
                    {
                        ShowNotesState("Заметок нет.\nВнутренние заметки видны только администрации.", "");
                    }
                    else
                    {
                        HideNotesState();
                        RelayoutNotes();
                    }
                    _notesSummary.Text = "Внутренние заметки · всего: " + _noteViews.Count;
                    _notesFooter.Text = _notesHasMore ? "Показаны последние 50 заметок · нажмите «Ещё», чтобы загрузить глубже" : "Загружены все доступные заметки";
                    SetStatus("Внутренние заметки: " + _noteViews.Count + ".");
                }
                catch (Exception ex)
                {
                    if (_noteViews.Count == 0)
                        ShowNotesState("Не удалось загрузить заметки.\n" + LimitText(ex.Message), ex.Message, true);
                    _notesFooter.Text = "Ошибка загрузки заметок.";
                    SetStatus("Ошибка загрузки заметок: " + ex.Message, true);
                }
                finally
                {
                    _notesLoading = false;
                    UpdateTabActionStates();
                    UpdateFooterState();
                }
            }

            private NoteView CreateNoteView(Dictionary<string, object> note)
            {
                var view = new NoteView();
                view.Text = Value(note, "body");
                var author = Value(note, "author_user_id");
                var meta = new Label
                {
                    Dock = DockStyle.Top,
                    Height = ScaleInt(20),
                    Font = UiTheme.BodySmall,
                    ForeColor = UiTheme.TextMuted,
                    TextAlign = ContentAlignment.MiddleLeft,
                    AutoEllipsis = true,
                    Text = "Автор: " + (string.IsNullOrWhiteSpace(author) ? "—" : author) + " · " + LocalTime(Value(note, "created_at"))
                };
                var body = new Label
                {
                    Dock = DockStyle.Fill,
                    Font = UiTheme.Body,
                    ForeColor = UiTheme.Text,
                    TextAlign = ContentAlignment.TopLeft,
                    AutoSize = false,
                    Text = string.IsNullOrWhiteSpace(view.Text) ? "—" : view.Text
                };
                var padding = ScaleInt(14);
                var card = new Panel
                {
                    Width = ListContentWidth(_notesList),
                    BackColor = UiTheme.Surface,
                    Margin = new Padding(0, 0, 0, ScaleInt(8)),
                    Padding = new Padding(padding, padding, padding, padding)
                };
                UiTheme.StyleCard(card, 10);
                card.Controls.Add(body);
                card.Controls.Add(meta);
                _tips.SetToolTip(meta, "ID автора: " + (string.IsNullOrWhiteSpace(author) ? "неизвестен" : author));
                _tips.SetToolTip(body, view.Text);
                var bodyWidth = Math.Max(ScaleInt(120), card.Width - padding * 2);
                var bodyHeight = MeasureWrappedHeight(body.Text, body.Font, bodyWidth);
                card.Height = padding * 2 + ScaleInt(20) + bodyHeight;
                view.Card = card;
                view.Body = body;
                return view;
            }

            private static int MeasureWrappedHeight(string text, Font font, int width)
            {
                if (string.IsNullOrEmpty(text)) return font.Height;
                var measured = TextRenderer.MeasureText(text, font, new Size(Math.Max(40, width), int.MaxValue),
                    TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix);
                return Math.Max(font.Height, measured.Height);
            }

            private void RelayoutNotes()
            {
                if (_inNotesLayout || _notesList == null || _noteViews.Count == 0) return;
                _inNotesLayout = true;
                try
                {
                    var width = ListContentWidth(_notesList);
                    var padding = ScaleInt(14);
                    foreach (var view in _noteViews)
                    {
                        if (view.Card == null || view.Card.IsDisposed) continue;
                        view.Card.Width = width;
                        var bodyWidth = Math.Max(ScaleInt(120), width - padding * 2);
                        var bodyHeight = MeasureWrappedHeight(view.Text, view.Body.Font, bodyWidth);
                        view.Card.Height = padding * 2 + ScaleInt(20) + bodyHeight;
                    }
                }
                finally
                {
                    _inNotesLayout = false;
                }
            }

            private void ShowHistoryState(string text, string tooltip, bool error = false)
            {
                _historyState.Text = text;
                _historyState.ForeColor = error ? UiTheme.Danger : UiTheme.TextMuted;
                _historyState.Visible = true;
                _tips.SetToolTip(_historyState, string.IsNullOrEmpty(tooltip) ? text : tooltip);
                _historyStateHost.Visible = true;
                _historyGridCard.Visible = false;
            }

            private void ShowSecurityState(string text, string tooltip, bool error = false)
            {
                _securityEventsState.Text = text;
                _securityEventsState.ForeColor = error ? UiTheme.Danger : UiTheme.TextMuted;
                _tips.SetToolTip(_securityEventsState, string.IsNullOrEmpty(tooltip) ? text : tooltip);
                _securityStateHost.Visible = true;
                _securityGridCard.Visible = false;
            }

            private void HideSecurityState()
            {
                _securityStateHost.Visible = false;
                _securityGridCard.Visible = true;
            }

            private void HideHistoryState()
            {
                _historyState.Visible = false;
                _historyStateHost.Visible = false;
                _historyGridCard.Visible = true;
            }

            private void ShowNotesState(string text, string tooltip, bool error = false)
            {
                _notesState.Text = text;
                _notesState.ForeColor = error ? UiTheme.Danger : UiTheme.TextMuted;
                _notesState.Visible = true;
                _tips.SetToolTip(_notesState, string.IsNullOrEmpty(tooltip) ? text : tooltip);
                _notesStateHost.Visible = true;
                _notesList.Visible = false;
            }

            private void HideNotesState()
            {
                _notesState.Visible = false;
                _notesStateHost.Visible = false;
                _notesList.Visible = true;
            }

            private void SyncListWidths()
            {
                RelayoutNotes();
            }

            private static int ListContentWidth(FlowLayoutPanel list)
            {
                if (list == null) return 600;
                var width = list.ClientSize.Width - list.Padding.Horizontal - SystemInformation.VerticalScrollBarWidth - 4;
                return Math.Max(240, width);
            }

            private void RefreshDevicesView()
            {
                var previous = _lastDeviceSelection;
                _devices.Rows.Clear();
                var shown = 0;
                foreach (var device in _deviceItems)
                {
                    if (!DeviceMatchesFilter(device)) continue;
                    shown++;
                    var row = _devices.Rows[_devices.Rows.Add(
                        PlatformLabel(Value(device, "platform")),
                        Empty(Value(device, "display_name"), "Устройство"),
                        Empty(Value(device, "app_version"), "—"),
                        Empty(Value(device, "os_version"), "—"),
                        LocalTime(Value(device, "last_active_at")),
                        DeviceStatus(device))];
                    row.Tag = device;
                    row.Cells[1].ToolTipText = DeviceTooltip(device);
                    row.Cells[5].ToolTipText = DeviceTooltip(device);
                }
                var any = shown > 0;
                _deviceGridCard.Visible = any;
                _deviceEmpty.Visible = !any;
                _deviceDetailCard.Visible = any;
                _devicesSummary.Text = "Показано: " + shown + " из " + _deviceItems.Count + " · активно: " + CountActiveDevices() +
                    " · ограничено: " + CountRestrictedDevices();
                if (any)
                {
                    DataGridViewRow selected = null;
                    foreach (DataGridViewRow row in _devices.Rows)
                    {
                        var device = row.Tag as Dictionary<string, object>;
                        if (device != null && previous != null && Value(device, "id") == previous)
                        {
                            selected = row;
                            break;
                        }
                    }
                    if (selected == null) selected = _devices.Rows[0];
                    _devices.CurrentCell = selected.Cells[0];
                    selected.Selected = true;
                    _lastDeviceSelection = Value(selected.Tag as Dictionary<string, object>, "id");
                }
                UpdateDeviceActions();
            }

            private bool DeviceMatchesFilter(Dictionary<string, object> device)
            {
                var query = (_deviceSearch.Text ?? "").Trim();
                if (query.Length > 0)
                {
                    var haystack = string.Join(" ", new[]
                    {
                        Value(device, "display_name"), Value(device, "platform"), Value(device, "app_version"),
                        Value(device, "os_version"), Value(device, "installation_id"), Value(device, "id")
                    });
                    if (haystack.IndexOf(query, StringComparison.CurrentCultureIgnoreCase) < 0) return false;
                }
                var filter = _deviceStatusFilter.SelectedIndex;
                if (filter == 1) return !Bool(device, "revoked") && !Bool(device, "restriction_active");
                if (filter == 2) return Bool(device, "restriction_active");
                if (filter == 3) return Bool(device, "revoked");
                return true;
            }

            private int CountActiveDevices()
            {
                var count = 0;
                foreach (var device in _deviceItems)
                    if (!Bool(device, "revoked")) count++;
                return count;
            }

            private int CountManageableActiveDevices()
            {
                var count = 0;
                foreach (var device in _deviceItems)
                    if (!Bool(device, "revoked") && !Bool(device, "is_administrator_device")) count++;
                return count;
            }

            private void UpdateDeviceActions()
            {
                if (!_ready) return;
                var device = SelectedDevice();
                var hasDevice = device != null;
                var isProtected = hasDevice && Bool(device, "is_administrator_device");
                var revoked = hasDevice && Bool(device, "revoked");
                var hasInstallation = hasDevice && !string.IsNullOrWhiteSpace(Value(device, "installation_id"));
                _logoutDeviceButton.Enabled = hasDevice && !revoked && !isProtected;
                _logoutAllDevicesButton.Enabled = CountManageableActiveDevices() > 0;
                _restrictionButton.Enabled = hasDevice && !isProtected && hasInstallation;
                _logoutDeviceButton.Text = revoked ? "Сеанс уже завершён" : "Завершить выбранный";
                _logoutAllDevicesButton.Text = CountManageableActiveDevices() > 0
                    ? "Завершить все активные · " + CountManageableActiveDevices()
                    : "Нет активных сеансов";
                _restrictionButton.Text = hasDevice && Bool(device, "restriction_active") ? "Разрешить вход" : "Запретить вход";

                if (!hasDevice)
                {
                    SetDeviceFields("—", "—", "—", "—", "—");
                    _deviceHint.Text = "Выберите устройство в таблице — здесь появятся детали и действия.";
                    if (_tabIndex == 1) _footerHint.Text = "Выберите устройство, чтобы завершить сеанс или ограничить вход.";
                    return;
                }
                var restrictionActive = Bool(device, "restriction_active");
                var restrictionUntil = Value(device, "restriction_until");
                var restrictionReason = Value(device, "restriction_reason");
                var version = Empty(Value(device, "app_version"), "—");
                var os = Empty(Value(device, "os_version"), "—");
                var status = DeviceStatus(device);
                if (restrictionActive)
                    status += string.IsNullOrWhiteSpace(restrictionUntil) ? " · бессрочно" : " до " + LocalTime(restrictionUntil);
                SetDeviceFields(
                    Empty(Value(device, "display_name"), "Устройство"),
                    PlatformLabel(Value(device, "platform")),
                    version + " / " + os,
                    LocalTime(Value(device, "last_active_at")),
                    status);
                if (restrictionActive && !string.IsNullOrWhiteSpace(restrictionReason))
                    _tips.SetToolTip(_deviceFields[4], "Причина ограничения: " + restrictionReason);
                else
                    _tips.SetToolTip(_deviceFields[4], status);
                if (isProtected)
                    _deviceHint.Text = "Устройство администратора: действия недоступны.";
                else if (revoked)
                    _deviceHint.Text = "Сеанс завершён. Запрет на будущий вход настраивается отдельно.";
                else if (restrictionActive)
                    _deviceHint.Text = "Вход с этой установки запрещён. Активные сеансы уже отозваны.";
                else if (!hasInstallation)
                    _deviceHint.Text = "У установки нет идентификатора — ограничение входа недоступно.";
                else
                    _deviceHint.Text = "Сеанс активен. Действия применяются с подтверждением.";
                if (_tabIndex == 1)
                {
                    var name = Empty(Value(device, "display_name"), PlatformLabel(Value(device, "platform")));
                    _footerHint.Text = "Выбрано: " + name + " · " + status + (isProtected ? " · устройство администратора" : "");
                }
            }

            private void SetDeviceFields(string name, string platform, string version, string lastActive, string access)
            {
                if (_deviceFields[0] == null) return;
                _deviceFields[0].Text = name;
                _deviceFields[1].Text = platform;
                _deviceFields[2].Text = version;
                _deviceFields[3].Text = lastActive;
                _deviceFields[4].Text = access;
                _deviceFields[4].ForeColor = access.StartsWith("Активно", StringComparison.Ordinal) ? UiTheme.Success :
                    access.StartsWith("Вход запрещён", StringComparison.Ordinal) ? UiTheme.Warning : UiTheme.Text;
            }

            private Dictionary<string, object> SelectedDevice()
            {
                if (_devices.SelectedRows.Count == 0) return null;
                return _devices.SelectedRows[0].Tag as Dictionary<string, object>;
            }

            private async Task LogoutSelectedDeviceAsync()
            {
                var device = SelectedDevice();
                if (device == null) { Notice("Сначала выберите устройство в таблице."); return; }
                if (Bool(device, "revoked")) { Notice("Сеанс этого устройства уже завершён."); return; }
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                var displayName = Empty(Value(device, "display_name"), PlatformLabel(Value(device, "platform")));
                if (MessageBox.Show(this,
                    "Завершить сеанс «" + displayName + "»?" + Environment.NewLine + Environment.NewLine +
                    "Устройство будет немедленно отключено и его текущие сеансы отзовутся. Запрет на будущий вход не устанавливается.",
                    "Выход с выбранного устройства", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                await _auth.SendAsync(HttpMethod.Post,
                    "admin/devices/" + Uri.EscapeDataString(Value(device, "id")) + "/logout",
                    new Dictionary<string, object> { { "reason", AdministrativeReason } }, true, true);
                device["revoked"] = true;
                device["last_active_at"] = DateTimeOffset.UtcNow.ToString("o");
                _lastDeviceSelection = Value(device, "id");
                RefreshDevicesView();
                UpdateProfileState();
                SetStatus("Сеанс устройства «" + displayName + "» завершён.");
                Notice("Сеанс устройства «" + displayName + "» завершён. Повторный вход не запрещён.");
            }

            private async Task LogoutAllDevicesAsync()
            {
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                var targets = _deviceItems
                    .Where(device => !Bool(device, "revoked") && !Bool(device, "is_administrator_device"))
                    .ToList();
                if (targets.Count == 0)
                {
                    Notice("У пользователя нет активных сеансов, доступных для завершения.");
                    return;
                }
                var names = targets.Take(4)
                    .Select(device => "• " + Empty(Value(device, "display_name"), PlatformLabel(Value(device, "platform"))))
                    .ToList();
                if (targets.Count > 4) names.Add("• и ещё " + (targets.Count - 4));
                var message = "Завершить все активные сеансы пользователя (" + targets.Count + ")?" +
                    Environment.NewLine + Environment.NewLine + string.Join(Environment.NewLine, names) +
                    Environment.NewLine + Environment.NewLine +
                    "Устройства будут отключены. Повторный вход останется разрешён — постоянные ограничения настраиваются отдельно.";
                if (MessageBox.Show(this, message, "Завершение всех сеансов",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;

                var completed = 0;
                var errors = new List<string>();
                foreach (var device in targets)
                {
                    try
                    {
                        await _auth.SendAsync(HttpMethod.Post,
                            "admin/devices/" + Uri.EscapeDataString(Value(device, "id")) + "/logout",
                            new Dictionary<string, object> { { "reason", AdministrativeReason } }, true, true);
                        device["revoked"] = true;
                        device["last_active_at"] = DateTimeOffset.UtcNow.ToString("o");
                        completed++;
                    }
                    catch (Exception ex)
                    {
                        errors.Add(Empty(Value(device, "display_name"), Value(device, "id")) + ": " + LimitText(ex.Message));
                    }
                }
                RefreshDevicesView();
                UpdateProfileState();
                var result = "Завершено сеансов: " + completed + " из " + targets.Count + ".";
                if (errors.Count > 0)
                {
                    result += Environment.NewLine + Environment.NewLine + "Не удалось:" + Environment.NewLine +
                        string.Join(Environment.NewLine, errors.Take(5));
                    SetStatus("Часть сеансов не завершена: " + completed + " из " + targets.Count + ".", true);
                    NoticeError(result);
                }
                else
                {
                    SetStatus(result);
                    Notice(result + " Повторный вход не запрещён.");
                }
            }

            private async Task ToggleInstallationRestrictionAsync()
            {
                var device = SelectedDevice();
                if (device == null) { Notice("Сначала выберите устройство в таблице."); return; }
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                var installationId = Value(device, "installation_id");
                if (string.IsNullOrEmpty(installationId)) throw new InvalidOperationException("Установка не имеет идентификатора.");
                var blocked = !Bool(device, "restriction_active");
                var platform = PlatformLabel(Value(device, "platform"));
                var prompt = blocked
                    ? "Запретить вход с этой установки? Если установка привязана к нескольким аккаунтам, ограничение затронет их все."
                    : "Снять запрет на вход с этой установки?";
                if (MessageBox.Show(this, prompt, blocked ? "Ограничение входа" : "Восстановление входа",
                    MessageBoxButtons.YesNo, blocked ? MessageBoxIcon.Warning : MessageBoxIcon.Question) != DialogResult.Yes) return;
                await _auth.SendAsync(HttpMethod.Post,
                    "admin/installations/" + Uri.EscapeDataString(installationId) + "/suspension",
                    new Dictionary<string, object>
                    {
                        { "blocked", blocked },
                        { "reason", AdministrativeReason }
                    }, true, true);
                foreach (var current in _deviceItems)
                {
                    if (!string.Equals(Value(current, "installation_id"), installationId, StringComparison.Ordinal)) continue;
                    current["restriction_active"] = blocked;
                    current["restriction_reason"] = blocked ? AdministrativeReason : "";
                    if (blocked) current["restriction_until"] = "";
                }
                RefreshDevicesView();
                UpdateProfileState();
                var message = blocked
                    ? "Вход с установки " + platform + " запрещён. Активные сеансы установки отозваны."
                    : "Вход с установки " + platform + " снова разрешён.";
                SetStatus(message);
                Notice(message);
            }

            private async Task ToggleAccountSuspensionAsync()
            {
                if (!_suspendAllowed) { Notice("Ограничение недоступно: нельзя заблокировать администратора или собственный аккаунт."); return; }
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                var blocked = !IsSuspended();
                string reason;
                bool includeInstallations;
                DateTimeOffset? until;
                if (!SuspensionDialog.TryShow(this, blocked, out reason, out includeInstallations, out until)) return;
                var body = new Dictionary<string, object>
                {
                    { "blocked", blocked },
                    { "include_installations", includeInstallations },
                    { "reason", reason }
                };
                if (blocked && until.HasValue) body["until"] = until.Value.ToUniversalTime().ToString("o");
                await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/suspension", body, true, true);
                var refreshed = false;
                try
                {
                    await RefreshDetailAsync();
                    refreshed = true;
                }
                catch (Exception)
                {
                    ApplyLocalSuspension(blocked, includeInstallations, reason, until);
                }
                UpdateProfileState();
                var restricted = CountRestrictedDevices();
                string message;
                if (blocked)
                {
                    message = until.HasValue
                        ? "Аккаунт ограничен до " + until.Value.LocalDateTime.ToString("dd.MM.yyyy HH:mm") + "."
                        : "Аккаунт ограничен бессрочно.";
                }
                else
                {
                    message = restricted > 0
                        ? "Доступ к аккаунту восстановлен. Ограничения на устройствах сохранены: " + restricted + ". Снимите их на вкладке «Устройства»."
                        : "Доступ к аккаунту восстановлен. Ограничений на устройствах нет.";
                }
                if (!refreshed) message += " Локальные данные обновлены, перезагрузите карточку для сверки.";
                SetStatus(message);
                Notice(message);
            }

            private void ApplyLocalSuspension(bool blocked, bool includeInstallations, string reason, DateTimeOffset? until)
            {
                if (_moderation == null) _moderation = new Dictionary<string, object>();
                _moderation["status"] = blocked ? "suspended" : "active";
                _moderation["until"] = blocked && until.HasValue ? until.Value.ToString("o") : "";
                _moderation["reason"] = blocked ? reason : "";
                if (!includeInstallations || _deviceItems == null) return;
                foreach (var device in _deviceItems)
                {
                    device["restriction_active"] = blocked;
                    device["restriction_reason"] = blocked ? reason : "";
                    device["restriction_until"] = blocked && until.HasValue ? until.Value.ToString("o") : "";
                }
                RefreshDevicesView();
            }

            private async Task RefreshDetailAsync()
            {
                if (_auth == null) return;
                var detail = await _auth.SendAsync(HttpMethod.Get,
                    "admin/users/" + Uri.EscapeDataString(_userId), null, true, true);
                ApplyDetail(detail);
                RefreshDevicesView();
                UpdateProfileState();
                SetStatus("Карточка пользователя обновлена.");
            }

            private Task CopyUserIdAsync()
            {
                try
                {
                    Clipboard.SetText(_userId);
                    Notice("ID профиля скопирован в буфер обмена.");
                    SetStatus("ID профиля скопирован.");
                }
                catch (Exception ex)
                {
                    NoticeError("Не удалось скопировать ID: " + ex.Message);
                }
                return Task.FromResult(0);
            }

            private Task CopyUsernameAsync()
            {
                try
                {
                    Clipboard.SetText("@" + _username);
                    Notice("Username @" + _username + " скопирован в буфер обмена.");
                    SetStatus("Username скопирован.");
                }
                catch (Exception ex)
                {
                    NoticeError("Не удалось скопировать username: " + ex.Message);
                }
                return Task.FromResult(0);
            }

            private async Task IssueRecoveryCodeAsync()
            {
                if (_auth == null || !string.Equals(_auth.AdminRole, "owner", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("Код восстановления может создать только владелец сервера.");
                if (_isAdministrator)
                    throw new InvalidOperationException("Для администраторов используется отдельная процедура восстановления доступа.");
                if (MessageBox.Show(this,
                    "Создать одноразовый код восстановления пароля для @" + _username + "?\n\n" +
                    "Предыдущий recovery-код пользователя перестанет действовать. Новый код будет действовать 1 час и не меняет пароль сам по себе.",
                    "Аварийное восстановление", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                string enteredReason;
                if (!TextPrompt.TryShow(this, "Причина восстановления", "Причина для журнала аудита", out enteredReason) ||
                    string.IsNullOrWhiteSpace(enteredReason)) return;
                var result = await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/recovery-code",
                    new Dictionary<string, object> { { "reason", enteredReason.Trim() } }, true, true);
                var code = Value(result, "code");
                if (string.IsNullOrEmpty(code)) throw new InvalidOperationException("Сервер не вернул recovery-код.");
                Clipboard.SetText(code);
                SetStatus("Одноразовый recovery-код создан и скопирован.");
                Notice("Одноразовый код восстановления для @" + _username + ":\n\n" + code +
                    "\n\nДействует до " + LocalTime(Value(result, "expires_at")) + ". Код уже скопирован в буфер обмена. Передайте его пользователю защищённым каналом.");
            }

            private async Task LoadAvatarAsync()
            {
                if (!_hasAvatar)
                {
                    _avatarPicture.Visible = false;
                    _avatarInitials.Visible = true;
                    return;
                }
                try
                {
                    if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                    SetAvatar(await _auth.GetUserAvatarBytesAsync(_userId));
                }
                catch (Exception ex)
                {
                    _avatarPicture.Visible = false;
                    _avatarInitials.Visible = true;
                    _avatarInitials.ForeColor = UiTheme.Warning;
                    _tips.SetToolTip(_avatarInitials, "Фото не загрузилось: " + ex.Message);
                }
            }

            private void SetAvatar(byte[] bytes)
            {
                using (var stream = new MemoryStream(bytes))
                using (var source = Image.FromStream(stream))
                {
                    var previous = _avatarPicture.Image;
                    _avatarPicture.Image = new Bitmap(source);
                    if (previous != null) previous.Dispose();
                }
                _avatarPicture.Visible = true;
                _avatarInitials.Visible = false;
            }

            private async Task OpenAvatarAsync()
            {
                if (!_hasAvatar) { Notice("У пользователя нет фотографии профиля."); return; }
                if (_avatarPicture.Image == null) await LoadAvatarAsync();
                if (_avatarPicture.Image == null) { NoticeError("Фотография профиля не загружена. Проверьте соединение с сервером."); return; }
                using (var dialog = new Form())
                using (var picture = new PictureBox())
                {
                    dialog.SuspendLayout();
                    dialog.Text = "Фото профиля · @" + _username;
                    dialog.StartPosition = FormStartPosition.CenterParent;
                    dialog.FormBorderStyle = FormBorderStyle.Sizable;
                    dialog.ShowInTaskbar = false;
                    dialog.BackColor = UiTheme.Background;
                    dialog.ForeColor = UiTheme.Text;
                    dialog.KeyPreview = true;
                    dialog.KeyDown += (s, e) => { if (e.KeyCode == Keys.Escape) dialog.Close(); };
                    picture.Dock = DockStyle.Fill;
                    picture.SizeMode = PictureBoxSizeMode.Zoom;
                    picture.Image = new Bitmap(_avatarPicture.Image);
                    dialog.Controls.Add(picture);
                    dialog.ClientSize = new Size(720, 720);
                    dialog.MinimumSize = new Size(360, 360);
                    dialog.AutoScaleMode = AutoScaleMode.Dpi;
                    dialog.AutoScaleDimensions = new SizeF(96f, 96f);
                    dialog.ResumeLayout(true);
                    try
                    {
                        var screen = Screen.FromControl(dialog);
                        if (screen != null)
                        {
                            var maxW = (int)(screen.WorkingArea.Width * 0.8);
                            var maxH = (int)(screen.WorkingArea.Height * 0.8);
                            if (dialog.Width > maxW || dialog.Height > maxH)
                                dialog.ClientSize = new Size(Math.Min(dialog.ClientSize.Width, maxW - 20),
                                    Math.Min(dialog.ClientSize.Height, maxH - 60));
                        }
                    }
                    catch (Exception) { }
                    dialog.Shown += (s, e) => WindowTheme.ApplyDarkTitleBar(dialog);
                    dialog.ShowDialog(this);
                    picture.Image.Dispose();
                }
            }

            private async Task SendNoticeAsync()
            {
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                UserNoticeResult notice;
                if (!UserNoticeDialog.TryShow(this, _username, out notice)) return;
                var result = await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/notice",
                    new Dictionary<string, object>
                    {
                        { "title", notice.Title }, { "body", notice.Body },
                        { "severity", notice.Severity }, { "reason", notice.Reason }
                    }, true, true);
                var queued = Value(result, "push_queued");
                var push = !Bool(result, "push_enabled") ? "Push отключён; уведомление сохранено." :
                    string.IsNullOrEmpty(queued) ? "Сервер не сообщил результат постановки в очередь." :
                    queued == "0" ? "Нет устройств с действующим push-токеном; уведомление сохранено." :
                    "В очереди устройств: " + queued + ". Доставка ещё не подтверждена.";
                _noticeState.Text = "Уведомление сохранено · " + DateTime.Now.ToString("HH:mm");
                SetStatus(push);
                Notice("Уведомление для @" + _username + " сохранено и записано в аудит. " + push);
                _securityEventsLoaded = false;
            }

            private async Task ToggleReadOnlyAsync()
            {
                if (!_suspendAllowed) throw new InvalidOperationException("Режим недоступен для администратора или текущего аккаунта.");
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                var enable = !IsReadOnly();
                string reason;
                DateTimeOffset? until;
                if (!ReadOnlyDialog.TryShow(this, enable, out reason, out until)) return;
                var body = new Dictionary<string, object> { { "enabled", enable }, { "reason", reason } };
                if (enable && until.HasValue) body["until"] = until.Value.ToUniversalTime().ToString("o");
                await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/read-only", body, true, true);
                _moderation["read_only"] = enable;
                _moderation["read_only_until"] = enable && until.HasValue ? until.Value.ToString("o") : "";
                _moderation["read_only_reason"] = enable ? reason : "";
                UpdateProfileState();
                _timelineLoaded = false;
                var refreshed = await RefreshAfterMutationAsync();
                var message = enable ? "Режим «Только чтение» включён." : "Режим «Только чтение» отключён.";
                if (!refreshed) message += " Изменение сохранено, но карточку не удалось обновить. Нажмите «Обновить».";
                SetStatus(message);
                Notice(message);
            }

            private async Task ModerateProfileAsync()
            {
                if (!_suspendAllowed) throw new InvalidOperationException("Модерация профиля недоступна для администратора или текущего аккаунта.");
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                ProfileModerationResult moderation;
                if (!ProfileModerationDialog.TryShow(this, _username, _hasAvatar,
                    !string.IsNullOrWhiteSpace(Value(_profile, "bio")), out moderation)) return;
                await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/profile-moderation",
                    new Dictionary<string, object>
                    {
                        { "remove_avatar", moderation.RemoveAvatar },
                        { "clear_display_name", moderation.ClearDisplayName },
                        { "clear_bio", moderation.ClearBio },
                        { "reason", moderation.Reason }
                    }, true, true);
                if (moderation.ClearDisplayName) _profile["display_name"] = _username;
                if (moderation.ClearBio) _profile["bio"] = "";
                if (moderation.RemoveAvatar) { _hasAvatar = false; _profile["has_avatar"] = false; }
                UpdateProfileState();
                _timelineLoaded = false;
                var refreshed = await RefreshAfterMutationAsync();
                if (!_hasAvatar)
                {
                    var previous = _avatarPicture.Image;
                    _avatarPicture.Image = null;
                    if (previous != null) previous.Dispose();
                    _avatarPicture.Visible = false;
                    _avatarInitials.Visible = true;
                }
                _timelineLoaded = false;
                SetStatus("Профиль очищен согласно выбранным действиям.");
                Notice("Модерация профиля завершена и записана в аудит." +
                    (refreshed ? "" : " Карточку не удалось обновить. Нажмите «Обновить»."));
            }

            private async Task<bool> RefreshAfterMutationAsync()
            {
                try { await RefreshDetailAsync(); return true; }
                catch (Exception) { return false; } // The mutation has already succeeded.
            }

            private async Task ExportDiagnosticsAsync()
            {
                if (_auth == null) throw new InvalidOperationException("Нет активной административной сессии.");
                string reason;
                if (!TextPrompt.TryShow(this, "Экспорт диагностики", "Причина для журнала аудита", out reason, "Подготовить экспорт") ||
                    string.IsNullOrWhiteSpace(reason)) return;
                var data = await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/diagnostics",
                    new Dictionary<string, object> { { "reason", reason.Trim() } }, true, true);
                using (var dialog = new SaveFileDialog())
                {
                    dialog.Title = "Сохранить диагностику аккаунта";
                    dialog.Filter = "JSON (*.json)|*.json";
                    dialog.AddExtension = true;
                    dialog.DefaultExt = "json";
                    dialog.FileName = "coco-user-" + SafeFilePart(_username) + "-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".json";
                    if (dialog.ShowDialog(this) != DialogResult.OK) return;
                    var json = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 }.Serialize(data);
                    File.WriteAllText(dialog.FileName, json, new UTF8Encoding(false));
                    _diagnosticsState.Text = "Сохранено " + DateTime.Now.ToString("dd.MM.yyyy HH:mm");
                    SetStatus("Диагностика сохранена: " + dialog.FileName);
                    Notice("Диагностика аккаунта сохранена. Переписка, пароли, ключи и токены в экспорт не входят.");
                }
                _timelineLoaded = false;
            }

            private static string SafeFilePart(string value)
            {
                var invalid = Path.GetInvalidFileNameChars();
                var result = new string((value ?? "user").Select(ch => invalid.Contains(ch) ? '_' : ch).ToArray()).Trim();
                return string.IsNullOrWhiteSpace(result) ? "user" : result;
            }

            private async Task AddNoteAsync()
            {
                if (_auth == null) { NoticeError("Нет активной административной сессии."); return; }
                if (MessageBox.Show(this,
                    "Добавить внутреннюю заметку к профилю @" + _username + "?" + Environment.NewLine + Environment.NewLine +
                    "Заметка видна только администраторам и записывается в журнал аудита.",
                    "Заметки администрации", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                string note;
                if (!TextPrompt.TryShow(this, "Новая внутренняя заметка", "Текст заметки", out note, "Сохранить заметку")) return;
                if (string.IsNullOrWhiteSpace(note)) { Notice("Заметка не добавлена: пустой текст."); return; }
                await _auth.SendAsync(HttpMethod.Post,
                    "admin/users/" + Uri.EscapeDataString(_userId) + "/notes",
                    new Dictionary<string, object> { { "body", note.Trim() } }, true, true);
                SetStatus("Внутренняя заметка добавлена.");
                Notice("Внутренняя заметка добавлена.");
                await LoadNotesAsync(true);
            }

            private void SetStatus(string text, bool error = false)
            {
                _footerHint.Text = text;
                _footerHint.ForeColor = error ? UiTheme.Danger : UiTheme.TextMuted;
            }

            private static string LimitText(string text)
            {
                if (string.IsNullOrEmpty(text)) return "";
                return text.Length <= 160 ? text : text.Substring(0, 157) + "…";
            }

            private static string TimelineKindLabel(string kind)
            {
                switch ((kind ?? "").Trim().ToLowerInvariant())
                {
                    case "report_received": return "Получена жалоба";
                    case "report_open": return "Жалоба открыта";
                    case "report_started": return "Жалоба в работе";
                    case "report_resolved": return "Жалоба решена";
                    case "report_dismissed": return "Жалоба отклонена";
                    case "account_suspended": return "Аккаунт ограничен";
                    case "account_restored": return "Аккаунт восстановлен";
                    case "device_suspended": return "Вход с устройства запрещён";
                    case "device_restored": return "Вход с устройства разрешён";
                    case "device_session_ended": return "Сеанс устройства завершён";
                    case "notice_sent": return "Отправлено системное уведомление";
                    case "diagnostics_exported": return "Экспортирована диагностика";
                    case "profile_moderated": return "Профиль очищен администрацией";
                    case "read_only_enabled": return "Включено только чтение";
                    case "read_only_disabled": return "Отключено только чтение";
                    case "account_deleted": return "Аккаунт удалён";
                    case "note_added": return "Добавлена заметка";
                    case "other": return "Другое событие";
                    default: return string.IsNullOrWhiteSpace(kind) ? "Событие" : kind;
                }
            }

            private static string SecurityEventLabel(string eventType)
            {
                switch ((eventType ?? "").Trim().ToLowerInvariant())
                {
                    case "new_device": return "Новое устройство";
                    case "device_revoked": return "Устройство отозвано";
                    case "all_other_devices_revoked": return "Другие устройства отозваны";
                    case "admin_notice": return "Уведомление администрации";
                    default: return string.IsNullOrWhiteSpace(eventType) ? "Событие безопасности" : eventType;
                }
            }

            private static string SecurityEventDetail(Dictionary<string, object> metadata)
            {
                if (metadata == null || metadata.Count == 0) return "—";
                var parts = new List<string>();
                foreach (var key in new[] { "title", "severity", "device_name", "platform", "reason" })
                {
                    var value = Value(metadata, key);
                    if (!string.IsNullOrWhiteSpace(value)) parts.Add(key + ": " + value);
                }
                if (parts.Count > 0) return LimitText(string.Join(" · ", parts));
                try { return LimitText(new JavaScriptSerializer().Serialize(metadata)); }
                catch (Exception) { return "Техническое событие"; }
            }

            private static string TimelineReasonText(Dictionary<string, object> item)
            {
                var reason = Value(item, "reason");
                if (!string.IsNullOrWhiteSpace(reason)) return reason;
                var detail = Object(item, "detail");
                if (detail != null)
                {
                    var status = Value(detail, "status");
                    if (!string.IsNullOrWhiteSpace(status)) return ReviewStatusLabel(status);
                }
                return "—";
            }

            private static string TimelineTooltip(Dictionary<string, object> item)
            {
                var lines = new List<string>();
                lines.Add(TimelineKindLabel(Value(item, "kind")));
                lines.Add("Время: " + LocalTime(Value(item, "occurred_at")));
                var actor = Value(item, "actor");
                lines.Add("Инициатор: " + (string.IsNullOrWhiteSpace(actor) ? "система / пользователь" : actor));
                var reason = Value(item, "reason");
                if (!string.IsNullOrWhiteSpace(reason)) lines.Add("Причина: " + reason);
                var detail = Object(item, "detail");
                if (detail != null)
                {
                    var reportId = Value(detail, "report_id");
                    var installationId = Value(detail, "installation_id");
                    var noteId = Value(detail, "note_id");
                    var status = Value(detail, "status");
                    if (!string.IsNullOrWhiteSpace(reportId)) lines.Add("Жалоба: " + reportId);
                    if (!string.IsNullOrWhiteSpace(installationId)) lines.Add("Установка: " + installationId);
                    if (!string.IsNullOrWhiteSpace(noteId)) lines.Add("Заметка: " + noteId);
                    if (!string.IsNullOrWhiteSpace(status)) lines.Add("Статус: " + ReviewStatusLabel(status));
                }
                var reference = Value(item, "ref");
                if (!string.IsNullOrWhiteSpace(reference)) lines.Add("Ссылка: " + reference);
                return string.Join(Environment.NewLine, lines.ToArray());
            }

            private static string DeviceTooltip(Dictionary<string, object> device)
            {
                var lines = new List<string>();
                lines.Add("Устройство: " + Empty(Value(device, "display_name"), "—"));
                lines.Add("Платформа: " + PlatformLabel(Value(device, "platform")));
                lines.Add("Версия приложения: " + Empty(Value(device, "app_version"), "—"));
                lines.Add("Операционная система: " + Empty(Value(device, "os_version"), "—"));
                lines.Add("Устройство ID: " + Value(device, "id"));
                lines.Add("Установка ID: " + Empty(Value(device, "installation_id"), "—"));
                lines.Add("Последняя активность: " + LocalTime(Value(device, "last_active_at")));
                lines.Add("Добавлено: " + LocalTime(Value(device, "created_at")));
                lines.Add("Статус: " + DeviceStatus(device));
                if (Bool(device, "restriction_active"))
                {
                    lines.Add("Ограничение до: " + (string.IsNullOrWhiteSpace(Value(device, "restriction_until")) ? "бессрочно" : LocalTime(Value(device, "restriction_until"))));
                    var reason = Value(device, "restriction_reason");
                    if (!string.IsNullOrWhiteSpace(reason)) lines.Add("Причина ограничения: " + reason);
                }
                if (Bool(device, "is_administrator_device")) lines.Add("Устройство принадлежит администратору: действия недоступны.");
                return string.Join(Environment.NewLine, lines.ToArray());
            }

            private static string Empty(string value, string fallback)
            {
                return string.IsNullOrWhiteSpace(value) ? fallback : value;
            }

            private static string PlatformLabel(string platform)
            {
                if (string.Equals(platform, "android", StringComparison.OrdinalIgnoreCase)) return "Android";
                if (string.Equals(platform, "ios", StringComparison.OrdinalIgnoreCase)) return "iOS";
                if (string.Equals(platform, "desktop", StringComparison.OrdinalIgnoreCase)) return "Компьютер";
                if (string.Equals(platform, "web", StringComparison.OrdinalIgnoreCase)) return "Веб";
                return Empty(platform, "Неизвестно");
            }

            private static string DeviceStatus(Dictionary<string, object> device)
            {
                if (Bool(device, "revoked")) return "Сеанс завершён";
                return Bool(device, "restriction_active") ? "Вход запрещён" : "Активно";
            }

            private static string LocalTime(string value)
            {
                DateTimeOffset time;
                return DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out time)
                    ? time.LocalDateTime.ToString("dd.MM.yyyy HH:mm")
                    : Empty(value, "—");
            }
        }
        private sealed class ReviewDialogResult
        {
            public string Status;
            public string Note;
            public string DecisionReason;
        }

        private sealed class ReviewDialog : Form
        {
            private readonly ComboBox _status = new ComboBox();
            private readonly TextBox _note = new TextBox();
            private readonly TextBox _reason = new TextBox();
            private bool _confirmed;

            private sealed class StatusOption
            {
                public readonly string Value;
                private readonly string _label;

                public StatusOption(string value)
                {
                    Value = value;
                    _label = value == "open" ? "Открыта" :
                             value == "in_progress" ? "В работе" :
                             value == "resolved" ? "Решена" :
                             value == "dismissed" ? "Отклонена" : value;
                }

                public override string ToString() { return _label; }
            }

            private ReviewDialog(string title, string[] statuses, string selected)
            {
                Text = title;
                ClientSize = new Size(600, 430);
                StyleDialog(this);
                var root = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 7,
                    Padding = new Padding(22, 16, 22, 12),
                    Margin = new Padding(0),
                    BackColor = UiTheme.Background
                };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
                _status.DropDownStyle = ComboBoxStyle.DropDownList;
                var options = statuses.Select(status => new StatusOption(status)).ToArray();
                _status.Items.AddRange(options.Cast<object>().ToArray());
                _status.SelectedItem = options.FirstOrDefault(option => option.Value == selected);
                if (_status.SelectedIndex < 0 && _status.Items.Count > 0) _status.SelectedIndex = 0;
                _status.Dock = DockStyle.Fill;
                _status.Margin = new Padding(0, 0, 0, 4);
                StyleInput(_status);
                _note.Multiline = true;
                _note.ScrollBars = ScrollBars.Vertical;
                _note.Dock = DockStyle.Fill;
                StyleInput(_note);
                UiTheme.SetPlaceholder(_note, "Краткая внутренняя заметка для команды");
                _reason.Multiline = true;
                _reason.ScrollBars = ScrollBars.Vertical;
                _reason.Dock = DockStyle.Fill;
                StyleInput(_reason);
                UiTheme.SetPlaceholder(_reason, "Основание решения для журнала аудита");
                root.Controls.Add(DialogFieldLabel("Решение"), 0, 0);
                root.Controls.Add(_status, 0, 1);
                root.Controls.Add(DialogFieldLabel("Заметка для команды · необязательно"), 0, 2);
                root.Controls.Add(_note, 0, 3);
                root.Controls.Add(DialogFieldLabel("Причина решения · необязательно"), 0, 4);
                root.Controls.Add(_reason, 0, 5);
                var footer = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    FlowDirection = FlowDirection.RightToLeft,
                    WrapContents = false,
                    Margin = new Padding(0),
                    Padding = new Padding(0, 10, 0, 0),
                    BackColor = UiTheme.Background
                };
                var save = new Button { Text = "Сохранить решение", Width = 178, Height = 40, Margin = new Padding(8, 0, 0, 0) };
                UiTheme.StyleButton(save, UiButtonKind.Primary);
                save.Click += (s, e) => { _confirmed = true; DialogResult = DialogResult.OK; Close(); };
                var cancel = new Button
                {
                    Text = "Отмена",
                    DialogResult = DialogResult.Cancel,
                    Width = 110,
                    Height = 40,
                    Margin = new Padding(8, 0, 0, 0)
                };
                StyleSecondaryButton(cancel);
                footer.Controls.Add(save);
                footer.Controls.Add(cancel);
                root.Controls.Add(footer, 0, 6);
                Controls.Add(root);
                AcceptButton = save;
                CancelButton = cancel;
            }

            private static Label DialogFieldLabel(string text)
            {
                return new Label
                {
                    Text = text,
                    Dock = DockStyle.Fill,
                    ForeColor = UiTheme.TextSecondary,
                    Font = UiTheme.Semibold,
                    TextAlign = ContentAlignment.MiddleLeft
                };
            }

            public static bool TryShow(IWin32Window owner, string title, string[] statuses, string selected, out ReviewDialogResult result)
            {
                using (var dialog = new ReviewDialog(title, statuses, selected))
                {
                    if (dialog.ShowDialog(owner) != DialogResult.OK || !dialog._confirmed)
                    {
                        result = null;
                        return false;
                    }
                    var selectedStatus = dialog._status.SelectedItem as StatusOption;
                    if (selectedStatus == null)
                    {
                        result = null;
                        return false;
                    }
                    var status = selectedStatus.Value;
                    // The owner may apply a decision without typing a comment;
                    // the caller supplies the default audit reason when needed.
                    result = new ReviewDialogResult
                    {
                        Status = status,
                        Note = dialog._note.Text,
                        DecisionReason = dialog._reason.Text.Trim()
                    };
                    return true;
                }
            }
        }

        private sealed class UserNoticeResult
        {
            public string Title;
            public string Body;
            public string Severity;
            public string Reason;
        }

        private sealed class UserNoticeDialog : Form
        {
            private readonly TextBox _title = new TextBox();
            private readonly TextBox _body = new TextBox();
            private readonly ComboBox _severity = new ComboBox();
            private readonly TextBox _reason = new TextBox();
            private readonly Label _validation = new Label();
            private bool _confirmed;

            private UserNoticeDialog(string username)
            {
                Text = "Системное уведомление · @" + username;
                StyleDialog(this);
                ClientSize = new Size(680, 500);
                var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 8, Padding = new Padding(20, 16, 20, 12) };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 132));
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 122));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 86));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                var description = new Label { Text = "Пользователь получит отдельное уведомление. Заголовок, важность и причина сохраняются в аудите.", Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary, TextAlign = ContentAlignment.TopLeft };
                root.Controls.Add(description, 0, 0); root.SetColumnSpan(description, 2);
                ConfigureField(_title, false, 120); ConfigureField(_body, true, 2000); ConfigureField(_reason, true, 2000);
                _severity.DropDownStyle = ComboBoxStyle.DropDownList;
                _severity.Items.AddRange(new object[] { "Информация", "Предупреждение", "Критическое" });
                _severity.SelectedIndex = 0; _severity.Dock = DockStyle.Fill; StyleInput(_severity);
                root.Controls.Add(Caption("Заголовок"), 0, 1); root.Controls.Add(_title, 1, 1);
                root.Controls.Add(Caption("Сообщение"), 0, 2); root.Controls.Add(_body, 1, 2);
                root.Controls.Add(Caption("Важность"), 0, 3); root.Controls.Add(_severity, 1, 3);
                var reasonCaption = Caption("Причина для аудита"); reasonCaption.Dock = DockStyle.Fill;
                root.Controls.Add(reasonCaption, 0, 4); root.SetColumnSpan(reasonCaption, 2);
                root.Controls.Add(_reason, 0, 5); root.SetColumnSpan(_reason, 2);
                _validation.Dock = DockStyle.Fill; _validation.ForeColor = UiTheme.Danger; _validation.Font = UiTheme.BodySmall;
                root.Controls.Add(_validation, 0, 6); root.SetColumnSpan(_validation, 2);
                var footer = DialogFooter("Отправить", UiButtonKind.Primary, () =>
                {
                    if (string.IsNullOrWhiteSpace(_title.Text) || string.IsNullOrWhiteSpace(_body.Text) || string.IsNullOrWhiteSpace(_reason.Text))
                    { _validation.Text = "Заполните заголовок, сообщение и причину."; return; }
                    _confirmed = true; Close();
                }, this);
                root.Controls.Add(footer, 0, 7); root.SetColumnSpan(footer, 2);
                Controls.Add(root);
            }

            private static void ConfigureField(TextBox field, bool multiline, int maxLength)
            {
                field.Dock = DockStyle.Fill; field.Multiline = multiline; field.MaxLength = maxLength;
                if (multiline) field.ScrollBars = ScrollBars.Vertical;
                StyleInput(field);
            }

            private static Label Caption(string text)
            {
                return new Label { Text = text, Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary, TextAlign = ContentAlignment.MiddleLeft };
            }

            public static bool TryShow(IWin32Window owner, string username, out UserNoticeResult result)
            {
                using (var dialog = new UserNoticeDialog(username))
                {
                    dialog.ShowDialog(owner);
                    result = new UserNoticeResult
                    {
                        Title = dialog._title.Text.Trim(), Body = dialog._body.Text.Trim(),
                        Severity = dialog._severity.SelectedIndex == 2 ? "critical" : dialog._severity.SelectedIndex == 1 ? "warning" : "info",
                        Reason = dialog._reason.Text.Trim()
                    };
                    return dialog._confirmed;
                }
            }
        }

        private sealed class ProfileModerationResult
        {
            public bool RemoveAvatar;
            public bool ClearDisplayName;
            public bool ClearBio;
            public string Reason;
        }

        private sealed class ProfileModerationDialog : Form
        {
            private readonly CheckBox _avatar = new CheckBox();
            private readonly CheckBox _name = new CheckBox();
            private readonly CheckBox _bio = new CheckBox();
            private readonly TextBox _reason = new TextBox();
            private readonly Label _validation = new Label();
            private bool _confirmed;

            private ProfileModerationDialog(string username, bool hasAvatar, bool hasBio)
            {
                Text = "Модерация профиля · @" + username;
                StyleDialog(this);
                ClientSize = new Size(620, 390);
                var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 8, Padding = new Padding(20, 16, 20, 12) };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 54));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 82));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                root.Controls.Add(new Label { Text = "Выбранные данные будут очищены сразу. Операция необратима и обязательно записывается в аудит.", Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary }, 0, 0);
                ConfigureCheck(_avatar, "Удалить фотографию профиля", hasAvatar);
                ConfigureCheck(_name, "Сбросить отображаемое имя до username", true);
                ConfigureCheck(_bio, "Очистить описание «О себе»", hasBio);
                root.Controls.Add(_avatar, 0, 1); root.Controls.Add(_name, 0, 2); root.Controls.Add(_bio, 0, 3);
                root.Controls.Add(new Label { Text = "Причина для журнала аудита", Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary }, 0, 4);
                _reason.Dock = DockStyle.Fill; _reason.Multiline = true; _reason.ScrollBars = ScrollBars.Vertical; _reason.MaxLength = 2000; StyleInput(_reason);
                root.Controls.Add(_reason, 0, 5);
                _validation.Dock = DockStyle.Fill; _validation.ForeColor = UiTheme.Danger; _validation.Font = UiTheme.BodySmall; root.Controls.Add(_validation, 0, 6);
                root.Controls.Add(DialogFooter("Очистить выбранное", UiButtonKind.Danger, () =>
                {
                    if (!_avatar.Checked && !_name.Checked && !_bio.Checked) { _validation.Text = "Выберите хотя бы одно действие."; return; }
                    if (string.IsNullOrWhiteSpace(_reason.Text)) { _validation.Text = "Укажите причину для аудита."; return; }
                    _confirmed = true; Close();
                }, this), 0, 7);
                Controls.Add(root);
            }

            private static void ConfigureCheck(CheckBox check, string text, bool enabled)
            {
                check.Text = text; check.Dock = DockStyle.Fill; check.ForeColor = enabled ? UiTheme.TextSecondary : UiTheme.TextMuted;
                check.Enabled = enabled; check.AutoSize = false;
            }

            public static bool TryShow(IWin32Window owner, string username, bool hasAvatar, bool hasBio, out ProfileModerationResult result)
            {
                using (var dialog = new ProfileModerationDialog(username, hasAvatar, hasBio))
                {
                    dialog.ShowDialog(owner);
                    result = new ProfileModerationResult
                    {
                        RemoveAvatar = dialog._avatar.Checked, ClearDisplayName = dialog._name.Checked,
                        ClearBio = dialog._bio.Checked, Reason = dialog._reason.Text.Trim()
                    };
                    return dialog._confirmed;
                }
            }
        }

        private sealed class ReadOnlyDialog : Form
        {
            private readonly ComboBox _preset = new ComboBox();
            private readonly DateTimePicker _until = new DateTimePicker();
            private readonly TextBox _reason = new TextBox();
            private readonly Label _validation = new Label();
            private readonly bool _enable;
            private bool _confirmed;

            private ReadOnlyDialog(bool enable)
            {
                _enable = enable;
                Text = enable ? "Включить режим «Только чтение»" : "Отключить режим «Только чтение»";
                StyleDialog(this);
                ClientSize = new Size(620, enable ? 380 : 318);
                var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 7, Padding = new Padding(20, 16, 20, 12) };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 60));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, enable ? 26 : 0));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, enable ? 44 : 0));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 86));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                root.Controls.Add(new Label
                {
                    Text = enable ? "Вход и чтение сохранятся. Сервер заблокирует отправку сообщений, звонки и изменение данных." : "Пользователь снова сможет отправлять сообщения, звонить и изменять данные.",
                    Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary
                }, 0, 0);
                var periodCaption = new Label { Text = "Срок", Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary, Visible = enable };
                root.Controls.Add(periodCaption, 0, 1);
                var period = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Visible = enable };
                _preset.DropDownStyle = ComboBoxStyle.DropDownList; _preset.Width = 170;
                _preset.Items.AddRange(new object[] { "Бессрочно", "1 час", "24 часа", "7 дней", "Выбрать дату" }); _preset.SelectedIndex = 3; StyleInput(_preset);
                _until.Format = DateTimePickerFormat.Custom; _until.CustomFormat = "dd.MM.yyyy HH:mm"; _until.ShowUpDown = true; _until.Width = 170; _until.Value = DateTime.Now.AddDays(7); _until.Enabled = false;
                _preset.SelectedIndexChanged += (s, e) =>
                {
                    _until.Enabled = _preset.SelectedIndex == 4;
                    if (_preset.SelectedIndex == 1) _until.Value = DateTime.Now.AddHours(1);
                    else if (_preset.SelectedIndex == 2) _until.Value = DateTime.Now.AddDays(1);
                    else if (_preset.SelectedIndex == 3) _until.Value = DateTime.Now.AddDays(7);
                };
                period.Controls.Add(_preset); period.Controls.Add(_until); root.Controls.Add(period, 0, 2);
                root.Controls.Add(new Label { Text = "Причина для журнала аудита", Dock = DockStyle.Fill, ForeColor = UiTheme.TextSecondary }, 0, 3);
                _reason.Dock = DockStyle.Fill; _reason.Multiline = true; _reason.ScrollBars = ScrollBars.Vertical; _reason.MaxLength = 2000; StyleInput(_reason); root.Controls.Add(_reason, 0, 4);
                _validation.Dock = DockStyle.Fill; _validation.ForeColor = UiTheme.Danger; _validation.Font = UiTheme.BodySmall; root.Controls.Add(_validation, 0, 5);
                root.Controls.Add(DialogFooter(enable ? "Включить" : "Отключить", enable ? UiButtonKind.Danger : UiButtonKind.Primary, () =>
                {
                    if (string.IsNullOrWhiteSpace(_reason.Text)) { _validation.Text = "Укажите причину для аудита."; return; }
                    if (_enable && _preset.SelectedIndex == 4 && _until.Value <= DateTime.Now) { _validation.Text = "Дата должна быть в будущем."; return; }
                    _confirmed = true; Close();
                }, this), 0, 6);
                Controls.Add(root);
            }

            public static bool TryShow(IWin32Window owner, bool enable, out string reason, out DateTimeOffset? until)
            {
                using (var dialog = new ReadOnlyDialog(enable))
                {
                    dialog.ShowDialog(owner);
                    reason = dialog._reason.Text.Trim(); until = null;
                    if (enable && dialog._preset.SelectedIndex > 0)
                    {
                        var selected = dialog._preset.SelectedIndex == 1 ? DateTime.Now.AddHours(1) :
                            dialog._preset.SelectedIndex == 2 ? DateTime.Now.AddDays(1) :
                            dialog._preset.SelectedIndex == 3 ? DateTime.Now.AddDays(7) : dialog._until.Value;
                        until = new DateTimeOffset(DateTime.SpecifyKind(selected, DateTimeKind.Local));
                    }
                    return dialog._confirmed;
                }
            }
        }

        private static FlowLayoutPanel DialogFooter(string confirmText, UiButtonKind kind, Action confirm, Form owner)
        {
            var footer = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft, WrapContents = false, Padding = new Padding(0, 12, 0, 0) };
            var save = new Button { Text = confirmText, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = false, Cursor = Cursors.Hand, Padding = new Padding(16, 8, 16, 8), Margin = new Padding(8, 0, 0, 0) };
            UiTheme.StyleButton(save, kind); save.Click += (s, e) => confirm();
            var cancel = new Button { Text = "Отмена", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = false, Cursor = Cursors.Hand, Padding = new Padding(16, 8, 16, 8), Margin = new Padding(8, 0, 0, 0), DialogResult = DialogResult.Cancel };
            UiTheme.StyleButton(cancel, UiButtonKind.Secondary); cancel.Click += (s, e) => owner.Close();
            owner.AcceptButton = save; owner.CancelButton = cancel;
            footer.Controls.Add(save); footer.Controls.Add(cancel);
            return footer;
        }

        private sealed class SuspensionDialog : Form
        {
            private readonly TextBox _reason = new TextBox();
            private readonly CheckBox _includeInstallations = new CheckBox();
            private readonly CheckBox _temporary = new CheckBox();
            private readonly ComboBox _preset = new ComboBox();
            private readonly DateTimePicker _until = new DateTimePicker();
            private readonly Label _untilHint = new Label();
            private readonly Label _validation = new Label();
            private bool _confirmed;

            private SuspensionDialog(bool blocked)
            {
                SuspendLayout();
                Text = blocked ? "Блокировка аккаунта" : "Восстановление доступа";
                StyleDialog(this);
                ClientSize = new Size(620, blocked ? 348 : 318);

                var root = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 7,
                    Margin = new Padding(0),
                    Padding = new Padding(20, 16, 20, 12)
                };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 78));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, blocked ? 36 : 0));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));

                var description = new Label
                {
                    Text = blocked
                        ? "Доступ к аккаунту будет приостановлен, его сеансы будут отозваны. Причина и срок попадут в журнал аудита."
                        : "Ограничение аккаунта будет снято. Отдельные запреты на вход с устройств могут сохраниться.",
                    Dock = DockStyle.Fill,
                    ForeColor = Color.FromArgb(175, 192, 216),
                    TextAlign = ContentAlignment.TopLeft
                };
                var caption = new Label
                {
                    Text = "Причина (обязательно)",
                    Dock = DockStyle.Fill,
                    ForeColor = UiTheme.TextSecondary,
                    TextAlign = ContentAlignment.MiddleLeft
                };
                _reason.Dock = DockStyle.Fill;
                _reason.Multiline = true;
                _reason.ScrollBars = ScrollBars.Vertical;
                StyleInput(_reason);

                _includeInstallations.Dock = DockStyle.Fill;
                _includeInstallations.AutoSize = false;
                _includeInstallations.ForeColor = Color.FromArgb(195, 208, 228);
                _includeInstallations.Text = blocked
                    ? "Также запретить вход со всех установок аккаунта"
                    : "Также снять запреты на вход с установок аккаунта";

                _validation.Dock = DockStyle.Fill;
                _validation.ForeColor = UiTheme.Danger;
                _validation.Font = UiTheme.BodySmall;
                _validation.TextAlign = ContentAlignment.MiddleLeft;

                var temporaryRow = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0), Padding = new Padding(0) };
                temporaryRow.Visible = blocked;
                _temporary.Text = "Временное ограничение";
                _temporary.AutoSize = true;
                _temporary.ForeColor = Color.FromArgb(195, 208, 228);
                _temporary.Dock = DockStyle.Left;
                _temporary.CheckedChanged += (s, e) =>
                {
                    _preset.Enabled = _temporary.Checked;
                    _until.Enabled = _temporary.Checked && _preset.SelectedIndex == 3;
                };
                _preset.DropDownStyle = ComboBoxStyle.DropDownList;
                _preset.Items.AddRange(new object[] { "1 час", "24 часа", "7 дней", "Выбрать дату" });
                _preset.SelectedIndex = 2;
                _preset.Width = 126;
                _preset.Dock = DockStyle.Left;
                _preset.Margin = new Padding(10, 2, 0, 2);
                _preset.Enabled = false;
                StyleInput(_preset);
                _preset.SelectedIndexChanged += (s, e) =>
                {
                    _until.Enabled = _temporary.Checked && _preset.SelectedIndex == 3;
                    if (_preset.SelectedIndex == 0) _until.Value = DateTime.Now.AddHours(1);
                    else if (_preset.SelectedIndex == 1) _until.Value = DateTime.Now.AddDays(1);
                    else if (_preset.SelectedIndex == 2) _until.Value = DateTime.Now.AddDays(7);
                };
                _until.Dock = DockStyle.Left;
                _until.AutoSize = true;
                _until.Margin = new Padding(10, 2, 0, 2);
                _until.Format = DateTimePickerFormat.Custom;
                _until.CustomFormat = "dd.MM.yyyy HH:mm";
                _until.ShowUpDown = true;
                _until.Value = DateTime.Now.AddDays(7);
                _until.Enabled = false;
                _untilHint.Text = "без даты — бессрочно";
                _untilHint.Dock = DockStyle.Fill;
                _untilHint.ForeColor = UiTheme.TextMuted;
                _untilHint.Font = UiTheme.BodySmall;
                _untilHint.TextAlign = ContentAlignment.MiddleLeft;
                _untilHint.Padding = new Padding(10, 0, 0, 0);
                _temporary.Visible = blocked;
                _until.Visible = blocked;
                _untilHint.Visible = blocked;
                temporaryRow.Controls.Add(_untilHint);
                temporaryRow.Controls.Add(_until);
                temporaryRow.Controls.Add(_preset);
                temporaryRow.Controls.Add(_temporary);

                var footer = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    FlowDirection = FlowDirection.RightToLeft,
                    WrapContents = false,
                    BackColor = UiTheme.Background,
                    Margin = new Padding(0),
                    Padding = new Padding(0, 12, 0, 0)
                };
                var save = new Button
                {
                    Text = blocked ? "Заблокировать аккаунт" : "Восстановить доступ",
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlatStyle = FlatStyle.Flat,
                    UseVisualStyleBackColor = false,
                    Cursor = Cursors.Hand,
                    Margin = new Padding(8, 0, 0, 0)
                };
                UiTheme.StyleButton(save, blocked ? UiButtonKind.Danger : UiButtonKind.Primary);
                save.Padding = new Padding(16, 8, 16, 8);
                save.Click += (s, e) =>
                {
                    if (string.IsNullOrWhiteSpace(_reason.Text))
                    {
                        _validation.Text = "Укажите причину — она будет записана в журнал аудита.";
                        _reason.Focus();
                        return;
                    }
                    _confirmed = true;
                    Close();
                };
                var cancel = new Button
                {
                    Text = "Отмена",
                    AutoSize = true,
                    AutoSizeMode = AutoSizeMode.GrowAndShrink,
                    FlatStyle = FlatStyle.Flat,
                    UseVisualStyleBackColor = false,
                    Cursor = Cursors.Hand,
                    Margin = new Padding(8, 0, 0, 0),
                    DialogResult = DialogResult.Cancel
                };
                UiTheme.StyleButton(cancel, UiButtonKind.Secondary);
                cancel.Padding = new Padding(16, 8, 16, 8);
                cancel.Click += (s, e) => Close();
                footer.Controls.Add(save);
                footer.Controls.Add(cancel);

                root.Controls.Add(description, 0, 0);
                root.Controls.Add(caption, 0, 1);
                root.Controls.Add(_reason, 0, 2);
                root.Controls.Add(_includeInstallations, 0, 3);
                root.Controls.Add(temporaryRow, 0, 4);
                root.Controls.Add(_validation, 0, 5);
                root.Controls.Add(footer, 0, 6);
                Controls.Add(root);
                AcceptButton = save;
                CancelButton = cancel;
                AutoScaleMode = AutoScaleMode.Dpi;
                AutoScaleDimensions = new SizeF(96f, 96f);
                ResumeLayout(true);
            }

            public static bool TryShow(IWin32Window owner, bool blocked, out string reason,
                out bool includeInstallations, out DateTimeOffset? until)
            {
                using (var dialog = new SuspensionDialog(blocked))
                {
                    dialog.ShowDialog(owner);
                    reason = dialog._reason.Text.Trim();
                    includeInstallations = dialog._includeInstallations.Checked;
                    until = null;
                    if (blocked && dialog._temporary.Checked)
                    {
                        var selected = dialog._preset.SelectedIndex == 0 ? DateTime.Now.AddHours(1) :
                            dialog._preset.SelectedIndex == 1 ? DateTime.Now.AddDays(1) :
                            dialog._preset.SelectedIndex == 2 ? DateTime.Now.AddDays(7) : dialog._until.Value;
                        var local = DateTime.SpecifyKind(selected, DateTimeKind.Local);
                        until = new DateTimeOffset(local);
                    }
                    return dialog._confirmed;
                }
            }
        }
        private sealed class BulkDeleteDialog : Form
        {
            private readonly string _phrase;
            private readonly TextBox _confirmation = new TextBox();
            private readonly TextBox _reason = new TextBox();
            private readonly Label _validation = new Label();
            private bool _confirmed;

            private BulkDeleteDialog(string[] usernames)
            {
                _phrase = "УДАЛИТЬ " + usernames.Length;
                Text = "Подтверждение массового удаления";
                ClientSize = new Size(640, 470);
                StyleDialog(this);

                var preview = string.Join(", ", usernames.Take(8).Select(name => "@" + name));
                if (usernames.Length > 8) preview += " и ещё " + (usernames.Length - 8);
                Controls.Add(new Label
                {
                    Text = "Безвозвратно будут удалены аккаунты, их чаты и сообщения.\n\n" + preview,
                    Left = 20,
                    Top = 18,
                    Width = 600,
                    Height = 78,
                    ForeColor = Color.FromArgb(255, 184, 176)
                });
                Controls.Add(new Label
                {
                    Text = "Введите точную фразу:  " + _phrase,
                    Left = 20,
                    Top = 108,
                    Width = 600,
                    Height = 26,
                    ForeColor = Color.FromArgb(190, 204, 225)
                });
                _confirmation.SetBounds(20, 138, 600, 36);
                StyleInput(_confirmation);
                Controls.Add(_confirmation);

                Controls.Add(new Label
                {
                    Text = "Общая причина удаления (обязательна, будет записана в аудит)",
                    Left = 20,
                    Top = 190,
                    Width = 600,
                    Height = 26,
                    ForeColor = Color.FromArgb(190, 204, 225)
                });
                _reason.SetBounds(20, 220, 600, 100);
                _reason.Multiline = true;
                _reason.ScrollBars = ScrollBars.Vertical;
                StyleInput(_reason);
                Controls.Add(_reason);

                _validation.SetBounds(20, 328, 600, 40);
                _validation.ForeColor = Color.FromArgb(255, 132, 142);
                Controls.Add(_validation);

                var cancel = new Button { Text = "Отмена", Left = 374, Top = 402, Width = 112, Height = 40 };
                StyleSecondaryButton(cancel);
                cancel.Click += (s, e) => Close();
                var delete = new Button
                {
                    Text = "Удалить аккаунты",
                    Left = 496,
                    Top = 402,
                    Width = 124,
                    Height = 40,
                    FlatStyle = FlatStyle.Flat,
                    BackColor = Color.FromArgb(145, 52, 64),
                    ForeColor = Color.White,
                    UseVisualStyleBackColor = false,
                    Cursor = Cursors.Hand
                };
                delete.FlatAppearance.BorderSize = 0;
                delete.Click += (s, e) =>
                {
                    if (!string.Equals(_confirmation.Text, _phrase, StringComparison.Ordinal))
                    {
                        _validation.Text = "Фраза не совпадает. Аккаунты не удалены.";
                        _confirmation.Focus();
                        return;
                    }
                    if (string.IsNullOrWhiteSpace(_reason.Text))
                    {
                        _validation.Text = "Укажите причину для журнала аудита.";
                        _reason.Focus();
                        return;
                    }
                    _confirmed = true;
                    Close();
                };
                Controls.AddRange(new Control[] { cancel, delete });
                CancelButton = cancel;
                AutoScaleMode = AutoScaleMode.Dpi;
                AutoScaleDimensions = new SizeF(96f, 96f);
                Shown += (s, e) => _confirmation.Focus();
            }

            public static bool TryShow(IWin32Window owner, string[] usernames, out string reason)
            {
                using (var dialog = new BulkDeleteDialog(usernames))
                {
                    dialog.ShowDialog(owner);
                    reason = dialog._reason.Text.Trim();
                    return dialog._confirmed;
                }
            }
        }

        private sealed class DeleteUserDialog : Form
        {
            private readonly string _username;
            private readonly TextBox _confirmation = new TextBox();
            private readonly TextBox _reason = new TextBox();
            private readonly Label _validation = new Label();
            private bool _confirmed;

            private DeleteUserDialog(string username)
            {
                _username = username;
                Text = "Подтверждение удаления аккаунта";
                ClientSize = new Size(600, 386);
                StyleDialog(this);

                Controls.Add(new Label
                {
                    Text = "Удаление необратимо: аккаунт, чаты и сообщения будут удалены. Загруженные файлы попадут в очередь очистки.",
                    Left = 20,
                    Top = 18,
                    Width = 560,
                    Height = 54,
                    ForeColor = Color.FromArgb(255, 184, 176)
                });
                Controls.Add(new Label
                {
                    Text = "Введите имя пользователя точно так, как показано:  " + username,
                    Left = 20,
                    Top = 82,
                    Width = 560,
                    Height = 28,
                    ForeColor = Color.FromArgb(190, 204, 225)
                });
                _confirmation.SetBounds(20, 112, 560, 36);
                StyleInput(_confirmation);
                Controls.Add(_confirmation);

                Controls.Add(new Label
                {
                    Text = "Причина удаления (обязательна, будет записана в журнал аудита)",
                    Left = 20,
                    Top = 160,
                    Width = 560,
                    Height = 26,
                    ForeColor = Color.FromArgb(190, 204, 225)
                });
                _reason.SetBounds(20, 188, 560, 84);
                _reason.Multiline = true;
                _reason.ScrollBars = ScrollBars.Vertical;
                StyleInput(_reason);
                Controls.Add(_reason);

                _validation.SetBounds(20, 278, 560, 24);
                _validation.ForeColor = Color.FromArgb(255, 132, 142);
                Controls.Add(_validation);

                var cancel = new Button
                {
                    Text = "Отмена",
                    Left = 346,
                    Top = 326,
                    Width = 108,
                    Height = 40,
                    DialogResult = DialogResult.Cancel
                };
                StyleSecondaryButton(cancel);
                cancel.Click += (s, e) => Close();

                var delete = new Button
                {
                    Text = "Удалить аккаунт",
                    Left = 464,
                    Top = 326,
                    Width = 116,
                    Height = 40,
                    FlatStyle = FlatStyle.Flat,
                    BackColor = Color.FromArgb(145, 52, 64),
                    ForeColor = Color.White,
                    UseVisualStyleBackColor = false,
                    Cursor = Cursors.Hand
                };
                delete.FlatAppearance.BorderSize = 0;
                delete.FlatAppearance.MouseOverBackColor = Color.FromArgb(177, 60, 73);
                delete.Click += (s, e) =>
                {
                    if (!string.Equals(_confirmation.Text, _username, StringComparison.Ordinal))
                    {
                        _validation.Text = "Имя пользователя не совпадает. Аккаунт не удалён.";
                        _confirmation.Focus();
                        return;
                    }
                    if (string.IsNullOrWhiteSpace(_reason.Text))
                    {
                        _validation.Text = "Укажите причину удаления для журнала аудита.";
                        _reason.Focus();
                        return;
                    }
                    _confirmed = true;
                    Close();
                };
                Controls.AddRange(new Control[] { cancel, delete });
                CancelButton = cancel;
                Shown += (s, e) => _confirmation.Focus();
            }

            public static bool TryShow(IWin32Window owner, string username, out string confirmation, out string reason)
            {
                using (var dialog = new DeleteUserDialog(username))
                {
                    dialog.ShowDialog(owner);
                    confirmation = dialog._confirmation.Text;
                    reason = dialog._reason.Text.Trim();
                    return dialog._confirmed;
                }
            }
        }

        private sealed class TextPrompt : Form
        {
            private readonly TextBox _value = new TextBox();
            private readonly Label _validation = new Label();
            private bool _confirmed;

            private TextPrompt(string title, string label, string confirmText)
            {
                SuspendLayout();
                Text = title;
                ClientSize = new Size(540, 206);
                StyleDialog(this);
                var root = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 4,
                    Padding = new Padding(20, 14, 20, 12),
                    Margin = new Padding(0),
                    BackColor = UiTheme.Background
                };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                root.Controls.Add(new Label
                {
                    Text = label,
                    Dock = DockStyle.Fill,
                    ForeColor = UiTheme.TextSecondary,
                    Font = UiTheme.Semibold,
                    TextAlign = ContentAlignment.MiddleLeft
                }, 0, 0);
                _value.Dock = DockStyle.Fill;
                _value.Multiline = true;
                _value.ScrollBars = ScrollBars.Vertical;
                StyleInput(_value);
                UiTheme.SetPlaceholder(_value, "Кратко опишите основание для аудита");
                root.Controls.Add(_value, 0, 1);
                _validation.Dock = DockStyle.Fill;
                _validation.ForeColor = UiTheme.Danger;
                _validation.Font = UiTheme.BodySmall;
                _validation.TextAlign = ContentAlignment.MiddleLeft;
                root.Controls.Add(_validation, 0, 2);
                var footer = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    FlowDirection = FlowDirection.RightToLeft,
                    WrapContents = false,
                    Margin = new Padding(0),
                    Padding = new Padding(0, 6, 0, 0),
                    BackColor = UiTheme.Background
                };
                var ok = new Button { Text = confirmText, Width = 150, Height = 40, Margin = new Padding(8, 0, 0, 0) };
                UiTheme.StyleButton(ok, UiButtonKind.Primary);
                ok.Click += (s, e) =>
                {
                    if (string.IsNullOrWhiteSpace(_value.Text))
                    {
                        _validation.Text = "Укажите причину — она будет записана в журнал аудита.";
                        _value.Focus();
                        return;
                    }
                    _confirmed = true;
                    Close();
                };
                var cancel = new Button { Text = "Отмена", Width = 110, Height = 40, Margin = new Padding(8, 0, 0, 0), DialogResult = DialogResult.Cancel };
                StyleSecondaryButton(cancel);
                cancel.Click += (s, e) => Close();
                footer.Controls.Add(ok);
                footer.Controls.Add(cancel);
                root.Controls.Add(footer, 0, 3);
                Controls.Add(root);
                AcceptButton = ok;
                CancelButton = cancel;
                AutoScaleMode = AutoScaleMode.Dpi;
                AutoScaleDimensions = new SizeF(96f, 96f);
                Shown += (s, e) => _value.Focus();
                ResumeLayout(true);
            }

            public static bool TryShow(IWin32Window owner, string title, string label, out string value,
                string confirmText = "Сохранить")
            {
                using (var dialog = new TextPrompt(title, label, confirmText))
                {
                    dialog.ShowDialog(owner);
                    value = dialog._value.Text;
                    return dialog._confirmed;
                }
            }
        }
        private sealed class ChoiceDialog : Form
        {
            private readonly ListBox _choices = new ListBox();
            private bool _confirmed;

            private ChoiceDialog(string title, string[] choices)
            {
                Text = title;
                ClientSize = new Size(520, 340);
                StyleDialog(this);
                var root = new TableLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    ColumnCount = 1,
                    RowCount = 3,
                    Padding = new Padding(20, 16, 20, 12),
                    Margin = new Padding(0),
                    BackColor = UiTheme.Background
                };
                root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
                root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
                root.Controls.Add(new Label
                {
                    Text = "Доступные варианты",
                    Dock = DockStyle.Fill,
                    Font = UiTheme.Semibold,
                    ForeColor = UiTheme.TextSecondary,
                    TextAlign = ContentAlignment.MiddleLeft
                }, 0, 0);
                _choices.Dock = DockStyle.Fill;
                _choices.BackColor = UiTheme.Surface;
                _choices.ForeColor = UiTheme.Text;
                _choices.BorderStyle = BorderStyle.FixedSingle;
                _choices.Font = UiTheme.Body;
                _choices.IntegralHeight = false;
                _choices.Items.AddRange(choices.Cast<object>().ToArray());
                if (_choices.Items.Count > 0) _choices.SelectedIndex = 0;
                _choices.DoubleClick += (s, e) => { if (_choices.SelectedIndex >= 0) { _confirmed = true; Close(); } };
                root.Controls.Add(_choices, 0, 1);
                var footer = new FlowLayoutPanel
                {
                    Dock = DockStyle.Fill,
                    FlowDirection = FlowDirection.RightToLeft,
                    WrapContents = false,
                    Margin = new Padding(0),
                    Padding = new Padding(0, 10, 0, 0),
                    BackColor = UiTheme.Background
                };
                var ok = new Button { Text = "Открыть выбранное", Width = 170, Height = 40, Margin = new Padding(8, 0, 0, 0) };
                UiTheme.StyleButton(ok, UiButtonKind.Primary);
                ok.Click += (s, e) => { _confirmed = true; Close(); };
                var cancel = new Button { Text = "Отмена", Width = 110, Height = 40, Margin = new Padding(8, 0, 0, 0), DialogResult = DialogResult.Cancel };
                StyleSecondaryButton(cancel);
                cancel.Click += (s, e) => Close();
                footer.Controls.Add(ok);
                footer.Controls.Add(cancel);
                root.Controls.Add(footer, 0, 2);
                Controls.Add(root);
                AcceptButton = ok;
                CancelButton = cancel;
            }

            public static bool TryShow(IWin32Window owner, string title, string[] choices, out int selected)
            {
                using (var dialog = new ChoiceDialog(title, choices))
                {
                    dialog.ShowDialog(owner);
                    selected = dialog._choices.SelectedIndex;
                    return dialog._confirmed && selected >= 0;
                }
            }
        }
    }
}
