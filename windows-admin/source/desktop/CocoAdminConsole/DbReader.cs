using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace CocoAdmin
{
    internal sealed class TableResult
    {
        public bool Ok;
        public string Error = "";
        public List<string[]> Rows = new List<string[]>();
    }

    internal sealed class OverviewStats
    {
        public long Users;
        public long RecentlyActive;
        public long OpenReports;
        public long OpenFeedback;
        public long NewDiagnostics;
        public bool DbOk;
        public string Error = "";
    }

    /// <summary>
    /// Read-only local database view for the desktop dashboard. This intentionally
    /// does not perform moderation writes: blocking/review actions stay in the
    /// audited server administration console.
    /// </summary>
    internal static class DbReader
    {
        private const string PsqlArguments =
            "exec -i messenger-postgres psql -U messenger -d messenger -v ON_ERROR_STOP=1 -A -t -F \"\t\"";

        public static async Task<TableResult> QueryAsync(string sql, int timeoutMs = 20000)
        {
            var result = new TableResult();
            var command = await ProcessRunner.RunAsync("docker", PsqlArguments, AppPaths.Workspace, timeoutMs, sql);
            if (!command.Ok)
            {
                result.Error = command.TimedOut
                    ? "Запрос к PostgreSQL не завершился вовремя."
                    : (command.Combined.Length > 0 ? command.Combined : "Не удалось подключиться к PostgreSQL в Docker.");
                try
                {
                    // Keep a bounded diagnostic trail without logging the SQL
                    // text itself: search terms and row contents can be personal.
                    var logPath = Path.Combine(AppPaths.Workspace, "desktop", "db-error.log");
                    var info = new FileInfo(logPath);
                    if (info.Exists && info.Length > 256 * 1024) File.Delete(logPath);
                    File.AppendAllText(logPath,
                        DateTime.Now.ToString("s") + " exit=" + command.ExitCode + " timeout=" + command.TimedOut +
                        Environment.NewLine + result.Error + Environment.NewLine);
                }
                catch { }
                return result;
            }
            foreach (var line in command.StdOut.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries))
            {
                var values = line.Split('\t');
                for (var i = 0; i < values.Length; i++)
                {
                    values[i] = Clean(values[i]);
                }
                result.Rows.Add(values);
            }
            result.Ok = true;
            return result;
        }

        public static async Task<OverviewStats> LoadOverviewAsync()
        {
            var stats = new OverviewStats();
            const string sql =
                "SELECT " +
                "(SELECT count(*) FROM users WHERE deleted_at IS NULL), " +
                "(SELECT count(*) FROM users WHERE deleted_at IS NULL AND last_seen_at > now() - interval '2 minutes'), " +
                "(SELECT count(*) FROM chat_reports WHERE review_status = 'open'), " +
                "(SELECT count(*) FROM bug_reports WHERE review_status = 'open');";
            var result = await QueryAsync(sql);
            if (!result.Ok || result.Rows.Count == 0)
            {
                stats.Error = result.Error;
                return stats;
            }
            var row = result.Rows[0];
            stats.DbOk = true;
            stats.Users = ParseLong(row, 0);
            stats.RecentlyActive = ParseLong(row, 1);
            stats.OpenReports = ParseLong(row, 2);
            stats.OpenFeedback = ParseLong(row, 3);
            stats.NewDiagnostics = -1; // Diagnostics live in Redis, not a PostgreSQL table.
            return stats;
        }

        public static async Task<TableResult> LoadUsersAsync(string search)
        {
            var term = EscapeLikeTerm(Clean(search));
            var sql =
                "SELECT username, coalesce(display_name,''), " +
                "COALESCE(to_char(last_seen_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI'),'-'), " +
                "CASE WHEN last_seen_at > now() - interval '2 minutes' THEN 'online' ELSE 'offline' END " +
                "FROM users WHERE deleted_at IS NULL";
            if (!string.IsNullOrWhiteSpace(term))
            {
                sql += " AND (username_normalized ILIKE '%" + term + "%' ESCAPE E'\\\\' OR " +
                       "coalesce(display_name,'') ILIKE '%" + term + "%' ESCAPE E'\\\\')";
            }
            sql += " ORDER BY last_seen_at DESC NULLS LAST, username_normalized LIMIT 200;";
            return await QueryAsync(sql);
        }

        public static async Task<TableResult> LoadReportsAsync()
        {
            const string sql =
                "SELECT coalesce(to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI'),'-'), " +
                "reason::text, review_status, left(coalesce(description,''), 120) " +
                "FROM chat_reports ORDER BY created_at DESC LIMIT 200;";
            return await QueryAsync(sql);
        }

        public static async Task<TableResult> LoadFeedbackAsync()
        {
            const string feedbackSql =
                "SELECT coalesce(to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI'),'-'), " +
                "coalesce(payload->>'platform','-'), coalesce(payload->>'app_version','-'), review_status, " +
                "left(coalesce(payload->>'description',''), 120) " +
                "FROM bug_reports ORDER BY created_at DESC LIMIT 100;";
            return await QueryAsync(feedbackSql);
        }

        private static long ParseLong(string[] row, int index)
        {
            if (row == null || index >= row.Length) return 0;
            long value;
            return long.TryParse(row[index], out value) ? value : 0;
        }

        private static string Clean(string value)
        {
            if (string.IsNullOrEmpty(value)) return "";
            return value.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ').Trim();
        }

        private static string EscapeLikeTerm(string value)
        {
            return (value ?? "")
                .Replace("\\", "\\\\")
                .Replace("%", "\\%")
                .Replace("_", "\\_")
                .Replace("'", "''");
        }
    }
}
