using System;
using System.IO;

namespace CocoAdmin
{
    internal static class AppPaths
    {
        private static string _workspace;

        public static string Workspace
        {
            get
            {
                if (_workspace != null) return _workspace;
                var configured = Environment.GetEnvironmentVariable("COCO_WORKSPACE");
                if (IsWorkspace(configured))
                {
                    _workspace = Path.GetFullPath(configured);
                    return _workspace;
                }
                var dir = new DirectoryInfo(AppDomain.CurrentDomain.BaseDirectory);
                for (var i = 0; i < 7 && dir != null; i++, dir = dir.Parent)
                {
                    if (File.Exists(Path.Combine(dir.FullName, "scripts", "Start-Coco.ps1")) &&
                        Directory.Exists(Path.Combine(dir.FullName, "server")))
                    {
                        _workspace = dir.FullName;
                        return _workspace;
                    }
                }
                _workspace = @"C:\dev\messenger";
                return _workspace;
            }
        }

        private static bool IsWorkspace(string path)
        {
            if (string.IsNullOrWhiteSpace(path)) return false;
            try
            {
                var full = Path.GetFullPath(path);
                return File.Exists(Path.Combine(full, "scripts", "Start-Coco.ps1")) &&
                       Directory.Exists(Path.Combine(full, "server"));
            }
            catch { return false; }
        }

        public static string StartScript { get { return Path.Combine(Workspace, "scripts", "Start-Coco.ps1"); } }
        public static string ResetDatabaseScript { get { return Path.Combine(Workspace, "scripts", "Reset-CocoDatabase.ps1"); } }
        public static string BackupDatabaseScript { get { return Path.Combine(Workspace, "scripts", "Backup-CocoDatabase.ps1"); } }
        public static string RestoreDatabaseScript { get { return Path.Combine(Workspace, "scripts", "Restore-CocoDatabase.ps1"); } }
        public static string HttpsScript { get { return Path.Combine(Workspace, "scripts", "Start-CocoHttps.ps1"); } }
        public static string ServerBinary { get { return Path.Combine(Workspace, "server", "target", "debug", "messenger-server.exe"); } }
        public static string RuntimeLogs { get { return Path.Combine(Workspace, "runtime-logs"); } }
        public static string EnvFile { get { return Path.Combine(Workspace, "server", ".env"); } }
        public static string TlsIdentity { get { return Path.Combine(RuntimeLogs, "https-ca-identity.json"); } }
        public static string DraftDirectory { get { return Path.Combine(Workspace, "desktop", "CocoAdminConsole"); } }
        public static string AiWatcherScript { get { return Path.Combine(Workspace, "desktop", "ai-watcher", "ai_watcher.py"); } }
        public static string AiFindingsFile { get { return Path.Combine(RuntimeLogs, "ai-findings.jsonl"); } }
        public static string AiIssuesFile { get { return Path.Combine(RuntimeLogs, "ai-issues.jsonl"); } }
        public static string AiErrorsFile { get { return Path.Combine(RuntimeLogs, "ai-errors.jsonl"); } }
        public static string AiCurrentErrorsFile { get { return Path.Combine(RuntimeLogs, "ai-errors-current.jsonl"); } }
        public static string AiJournalFile { get { return Path.Combine(RuntimeLogs, "ai-journal.jsonl"); } }
        public static string AiEvidenceFile { get { return Path.Combine(RuntimeLogs, "ai-last-evidence.txt"); } }
        public static string AiStateFile { get { return Path.Combine(RuntimeLogs, "ai-watcher-state.json"); } }
        public static string AiDatabaseIndexFile { get { return Path.Combine(RuntimeLogs, "ai-database-index.json"); } }
        public static string AiDatabaseDumpFile { get { return Path.Combine(RuntimeLogs, "ai-database-full.sql"); } }
        public static string AiConfigFile { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "COCO", "ai-watcher.json"); } }
    }
}
