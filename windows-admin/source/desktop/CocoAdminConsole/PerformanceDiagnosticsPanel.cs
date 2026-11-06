using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace CocoAdmin
{
    /// Read-only, opt-in telemetry. No mobile commands or account mutations.
    internal sealed class PerformanceDiagnosticsPanel : UserControl
    {
        private readonly DataGridView _events = new DataGridView();
        private readonly Label _status = new Label();
        private readonly TextBox _details = new TextBox();
        private readonly Timer _timer = new Timer { Interval = 5000 };
        private readonly HashSet<string> _seen = new HashSet<string>();
        private readonly Queue<string> _seenOrder = new Queue<string>();
        private AdminAuth _auth;
        private bool _loading;
        public AdminAuth Auth
        {
            set
            {
                if (ReferenceEquals(_auth, value)) return;
                _auth = value; _events.Rows.Clear(); _seen.Clear(); _seenOrder.Clear(); _details.Clear();
                _status.Text = "Диагностика выключена, пока пользователь не разрешил её в UNYRI.";
                UpdateTimer();
            }
        }
        public PerformanceDiagnosticsPanel()
        {
            Dock = DockStyle.Fill; BackColor = UiTheme.Background;
            _status.Dock = DockStyle.Top; _status.Height = 64; _status.Padding = new Padding(12);
            _status.ForeColor = UiTheme.TextSecondary;
            _status.Text = "Онлайн-диагностика доступна владельцу сервера. Обновление каждые 5 секунд.";
            _details.Dock = DockStyle.Bottom; _details.Height = 160; _details.Multiline = true;
            _details.ReadOnly = true; _details.ScrollBars = ScrollBars.Both;
            _details.BackColor = UiTheme.Surface; _details.ForeColor = UiTheme.Text;
            _events.Dock = DockStyle.Fill; _events.ReadOnly = true;
            _events.AllowUserToAddRows = false; _events.AllowUserToDeleteRows = false;
            _events.RowHeadersVisible = false; _events.MultiSelect = false;
            _events.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
            _events.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
            foreach (var title in new[] { "Время телефона", "Устройство / сборка", "Экран", "Событие", "Миллисекунды" })
                _events.Columns.Add("c" + _events.Columns.Count, title);
            UiTheme.StyleGrid(_events);
            _events.SelectionChanged += (s, e) => {
                if (_events.SelectedRows.Count > 0) _details.Text = Convert.ToString(_events.SelectedRows[0].Tag);
            };
            Controls.Add(_events); Controls.Add(_details); Controls.Add(_status);
            _timer.Tick += async (s, e) => await RefreshAsync();
            VisibleChanged += async (s, e) => { UpdateTimer(); if (Visible) await RefreshAsync(); };
        }
        private void UpdateTimer()
        {
            _timer.Enabled = Visible && _auth != null && string.Equals(_auth.AdminRole, "owner", StringComparison.OrdinalIgnoreCase);
        }
        internal async Task RefreshAsync()
        {
            var session = _auth;
            if (_loading || !Visible || session == null || session.AdminRole != "owner" || IsDisposed) return;
            _loading = true;
            try
            {
                var response = await session.SendAsync(HttpMethod.Get, "admin/performance", null, true, true);
                if (IsDisposed || !ReferenceEquals(session, _auth)) return;
                var batches = Rows(Field(response, "items")).ToList();
                foreach (var batch in batches.AsEnumerable().Reverse())
                    AppendSnapshot(Field(batch, "snapshot") as Dictionary<string, object>, GetText(batch, "device_id"));
                _status.Text = "Обновлено " + DateTime.Now.ToString("HH:mm:ss") + " · пакетов: " + batches.Count +
                    " · на экране до 1000 событий. Онлайн-пакеты хранятся до 24 часов.\r\n" +
                    "Включите в UNYRI: Настройки → Помощь → Диагностика производительности → Онлайн-отправка. Отсутствие пакетов не доказывает отсутствие ошибок.";
            }
            catch (Exception ex)
            {
                if (!IsDisposed && ReferenceEquals(session, _auth))
                    _status.Text = "Онлайн-диагностика недоступна. Последние события сохранены на экране. " + ex.Message;
            }
            finally { _loading = false; }
        }
        internal void AppendSnapshot(Dictionary<string, object> snapshot, string device)
        {
            if (snapshot == null) return;
            foreach (var item in Rows(Field(snapshot, "events")))
            {
                var key = GetText(snapshot, "recording_id") + ":" + GetText(item, "sequence");
                if (!_seen.Add(key)) continue;
                _seenOrder.Enqueue(key);
                while (_seenOrder.Count > 4096) _seen.Remove(_seenOrder.Dequeue());
                var metrics = Field(item, "metrics") as Dictionary<string, object>;
                var time = GetText(item, "at_ms"); long milliseconds;
                if (long.TryParse(time, out milliseconds))
                {
                    try { time = DateTimeOffset.FromUnixTimeMilliseconds(milliseconds).ToLocalTime().ToString("dd.MM HH:mm:ss.fff"); }
                    catch (ArgumentOutOfRangeException) { time = "Некорректное время"; }
                }
                _events.Rows.Insert(0, time, (device ?? "") + " · " + GetText(snapshot, "build"),
                    GetText(item, "screen"), GetText(item, "kind"), GetText(metrics, "duration_ms") == "" ? GetText(metrics, "gap_ms") : GetText(metrics, "duration_ms"));
                _events.Rows[0].Tag = "Версия: " + GetText(snapshot, "app_version") + " · ОС: " + GetText(snapshot, "os_version") +
                    " · Модель: " + GetText(snapshot, "hardware") + "\r\nСеанс: " + GetText(snapshot, "recording_id") +
                    " · Вытеснено локально: " + GetText(snapshot, "dropped") + " · Не вошло в срез: " + GetText(snapshot, "omitted") + "\r\n" + new JavaScriptSerializer().Serialize(item);
                if (_events.Rows[0].Selected) _details.Text = Convert.ToString(_events.Rows[0].Tag);
                while (_events.Rows.Count > 1000) _events.Rows.RemoveAt(_events.Rows.Count - 1);
            }
        }
        internal static object Field(Dictionary<string, object> item, string key)
        { object value; return item != null && item.TryGetValue(key, out value) ? value : null; }
        private static string GetText(Dictionary<string, object> item, string key)
        { return Convert.ToString(Field(item, key), CultureInfo.InvariantCulture) ?? ""; }
        private static IEnumerable<Dictionary<string, object>> Rows(object value)
        {
            var list = value as IEnumerable;
            if (list == null || value is string) yield break;
            foreach (var item in list) { var row = item as Dictionary<string, object>; if (row != null) yield return row; }
        }
        internal void ShowReportSnapshot(Dictionary<string, object> snapshot)
        {
            AppendSnapshot(snapshot, "Отчёт");
            _status.Text = "Диагностика из обращения. Это ограниченный срез, а не полная запись работы приложения. Выберите событие, чтобы увидеть метрики.";
        }
        protected override void Dispose(bool disposing)
        { if (disposing) { _timer.Stop(); _timer.Dispose(); } base.Dispose(disposing); }
    }
}
