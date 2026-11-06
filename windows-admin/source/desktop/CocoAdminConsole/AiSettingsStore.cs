using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace CocoAdmin
{
    internal sealed class AiSettings
    {
        public string ApiKey = "";
        public string Model = "deepseek-chat";
        public string BaseUrl = "https://api.deepseek.com/chat/completions";
        public int IntervalSeconds = 300;
        public bool ScanAllLogs = false;
        public bool IncludeDatabase = false;
        public bool DatabaseDump = false;
        public bool SendRawToApi = false;
        public bool IncludeAudit = true;
        public bool IncludeDocker = true;
        public bool IncludePerformance = false;
    }

    internal static class AiSettingsStore
    {
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("COCO AI watcher settings v1");

        private static string PathFile
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CocoAdmin");
                Directory.CreateDirectory(dir);
                return Path.Combine(dir, "ai-watcher-settings.bin");
            }
        }

        public static AiSettings Load()
        {
            try
            {
                if (!File.Exists(PathFile)) return new AiSettings();
                var encrypted = File.ReadAllBytes(PathFile);
                var clear = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
                try
                {
                    var values = new JavaScriptSerializer().Deserialize<AiSettings>(Encoding.UTF8.GetString(clear));
                    return values ?? new AiSettings();
                }
                finally
                {
                    Array.Clear(clear, 0, clear.Length);
                }
            }
            catch
            {
                return new AiSettings();
            }
        }

        public static void Save(AiSettings settings)
        {
            if (settings == null) throw new ArgumentNullException("settings");
            var clear = Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(settings));
            byte[] encrypted;
            try
            {
                encrypted = ProtectedData.Protect(clear, Entropy, DataProtectionScope.CurrentUser);
            }
            finally
            {
                Array.Clear(clear, 0, clear.Length);
            }
            File.WriteAllBytes(PathFile, encrypted);
        }

        public static void Delete()
        {
            try { if (File.Exists(PathFile)) File.Delete(PathFile); }
            catch { }
        }

        public static bool IsConfigured
        {
            get { return !string.IsNullOrWhiteSpace(Load().ApiKey); }
        }
    }
}
