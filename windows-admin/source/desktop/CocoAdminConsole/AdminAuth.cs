using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace CocoAdmin
{
    internal sealed class ApiException : Exception
    {
        public int Status;
        public string Code;
        public string ResponseBody;
        public ApiException(int status, string code, string message, string responseBody = "")
            : base(string.IsNullOrWhiteSpace(message) ? DefaultMessage(status) : message)
        {
            Status = status;
            Code = string.IsNullOrWhiteSpace(code) ? "http" : code;
            ResponseBody = responseBody ?? "";
        }

        private static string DefaultMessage(int status)
        {
            if (status == 0) return "Сервер недоступен или соединение было прервано.";
            if (status == 401) return "Сессия завершилась. Войдите в административную консоль повторно.";
            if (status == 403) return "У текущего администратора недостаточно прав для этой операции.";
            if (status == 404) return "Запрошенная функция отсутствует в запущенной версии сервера или объект уже удалён.";
            if (status == 409) return "Операция конфликтует с текущим состоянием данных. Обновите экран и повторите проверку.";
            if (status >= 500) return "Сервер не смог выполнить операцию из-за внутренней ошибки.";
            return "Сервер отклонил операцию (HTTP " + status + ").";
        }
    }

    internal sealed class AdminStatus
    {
        public bool Configured;
        public int KeyProtocol;
        public int AuthProtocol;
        public string AuthPolicy = AdminSecurityPolicy.Both;
        public bool TotpConfigured;
        public bool PaperKeyConfigured;
        public long AuthRevision;
        public string ServerId;
        public string SigningPublicKey;
        public bool SupportsFactorChoice { get { return AuthProtocol >= 2; } }
    }

    internal sealed class AuthConfig
    {
        public string BaseUrl = "https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud";
        public string Username = "";
        public string InstallationId = "";
    }

    internal sealed class RememberedAdminCredentials
    {
        public string BaseUrl;
        public string Username;
        public string Password;
        public string PaperKey;
    }

    internal sealed class PersistedAdminSession
    {
        public string BaseUrl;
        public string AccessToken;
        public string RefreshToken;
        public string AdminToken;
        public string UserId;
        public string Username;
        public string DeviceId;
        public string AdminRole;
        public string ServerId;
        public string AccessTokenExpiresAt;
        public bool UserOptedIn;
    }

    internal static class AdminCredentialStore
    {
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("COCO Admin Console credentials v1");

        private static string CredentialPath
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CocoAdmin");
                Directory.CreateDirectory(dir);
                return Path.Combine(dir, "admin-credentials.bin");
            }
        }

        internal static class AdminSessionStore
        {
            private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("COCO Admin Console session v1");

            private static string SessionPath
            {
                get
                {
                    var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CocoAdmin");
                    Directory.CreateDirectory(dir);
                    return Path.Combine(dir, "admin-session.bin");
                }
            }

            public static PersistedAdminSession Load()
            {
                if (!File.Exists(SessionPath)) return null;
                var encrypted = File.ReadAllBytes(SessionPath);
                var clear = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
                try
                {
                    return new JavaScriptSerializer().Deserialize<PersistedAdminSession>(Encoding.UTF8.GetString(clear));
                }
                finally
                {
                    Array.Clear(clear, 0, clear.Length);
                }
            }

            public static void Save(PersistedAdminSession session)
            {
                var clear = Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(session));
                byte[] encrypted;
                try
                {
                    encrypted = ProtectedData.Protect(clear, Entropy, DataProtectionScope.CurrentUser);
                }
                finally
                {
                    Array.Clear(clear, 0, clear.Length);
                }
                File.WriteAllBytes(SessionPath, encrypted);
            }

            public static void Delete()
            {
                if (File.Exists(SessionPath)) File.Delete(SessionPath);
            }
        }

        public static RememberedAdminCredentials Load(string baseUrl)
        {
            if (!File.Exists(CredentialPath)) return null;
            var protectedData = File.ReadAllBytes(CredentialPath);
            var clearData = ProtectedData.Unprotect(protectedData, Entropy, DataProtectionScope.CurrentUser);
            try
            {
                var values = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(clearData));
                var saved = new RememberedAdminCredentials
                {
                    BaseUrl = Text(values, "base_url", ""),
                    Username = Text(values, "username", ""),
                    Password = Text(values, "password", ""),
                    PaperKey = Text(values, "paper_key", "")
                };
                return string.Equals(NormalizeBaseUrl(saved.BaseUrl), NormalizeBaseUrl(baseUrl), StringComparison.OrdinalIgnoreCase) &&
                       !string.IsNullOrEmpty(saved.Password)
                    ? saved
                    : null;
            }
            finally
            {
                Array.Clear(clearData, 0, clearData.Length);
            }
        }

        public static void Save(RememberedAdminCredentials credentials)
        {
            var values = new Dictionary<string, object>
            {
                { "base_url", credentials.BaseUrl },
                { "username", credentials.Username },
                { "password", credentials.Password },
                { "paper_key", credentials.PaperKey }
            };
            var clearData = Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(values));
            byte[] protectedData;
            try
            {
                protectedData = ProtectedData.Protect(clearData, Entropy, DataProtectionScope.CurrentUser);
            }
            finally
            {
                Array.Clear(clearData, 0, clearData.Length);
            }
            File.WriteAllBytes(CredentialPath, protectedData);
        }

        public static void Delete()
        {
            if (File.Exists(CredentialPath)) File.Delete(CredentialPath);
        }

        private static string NormalizeBaseUrl(string value)
        {
            Uri uri;
            return Uri.TryCreate(value, UriKind.Absolute, out uri)
                ? uri.GetLeftPart(UriPartial.Authority).TrimEnd('/')
                : (value ?? "").Trim().TrimEnd('/');
        }

        private static string Text(Dictionary<string, object> values, string key, string fallback)
        {
            object value;
            return values != null && values.TryGetValue(key, out value) && value != null
                ? Convert.ToString(value)
                : fallback;
        }
    }

    internal static class AuthConfigStore
    {
        private static string PathFile
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CocoAdmin");
                Directory.CreateDirectory(dir);
                return Path.Combine(dir, "config.json");
            }
        }

        public static AuthConfig Load()
        {
            try
            {
                var json = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(File.ReadAllText(PathFile));
                var config = new AuthConfig();
                config.BaseUrl = Text(json, "base_url", config.BaseUrl);
                config.Username = Text(json, "username", "");
                config.InstallationId = Text(json, "installation_id", "");
                if (string.IsNullOrWhiteSpace(config.InstallationId)) config.InstallationId = Guid.NewGuid().ToString();
                return config;
            }
            catch
            {
                return new AuthConfig { InstallationId = Guid.NewGuid().ToString() };
            }
        }

        public static void Save(AuthConfig config)
        {
            try
            {
                var body = new Dictionary<string, object>
                {
                    { "base_url", config.BaseUrl },
                    { "username", config.Username ?? "" },
                    { "installation_id", config.InstallationId }
                };
                File.WriteAllText(PathFile, new JavaScriptSerializer().Serialize(body));
            }
            catch { /* local settings are non-critical */ }
        }

        private static string Text(Dictionary<string, object> dict, string key, string fallback)
        {
            object value;
            return dict != null && dict.TryGetValue(key, out value) && value != null ? Convert.ToString(value) : fallback;
        }
    }

    internal sealed class AdminAuth : IDisposable
    {
        private readonly JavaScriptSerializer _json = new JavaScriptSerializer();
        private HttpClient _http;

        public string BaseUrl { get; private set; }
        public string AccessToken { get; private set; }
        public string RefreshToken { get; private set; }
        public string AdminToken { get; private set; }
        public string UserId { get; private set; }
        public string Username { get; private set; }
        public string DeviceId { get; private set; }
        public string AdminRole { get; private set; }
        public string ServerId { get; private set; }
        public DateTimeOffset AccessTokenExpiresAt { get; private set; }
        public bool IsAdminUnlocked { get { return !string.IsNullOrEmpty(AdminToken); } }
        public bool IsAccessTokenExpired
        {
            get
            {
                return AccessTokenExpiresAt != default(DateTimeOffset) &&
                       AccessTokenExpiresAt <= DateTimeOffset.UtcNow.AddSeconds(30);
            }
        }

        public AdminAuth(string baseUrl)
        {
            SetBaseUrl(baseUrl);
        }

        public void SetBaseUrl(string baseUrl)
        {
            if (string.IsNullOrWhiteSpace(baseUrl)) throw new ArgumentException("Server URL is required.");
            var normalized = baseUrl.Trim().TrimEnd('/');
            Uri uri;
            if (!Uri.TryCreate(normalized, UriKind.Absolute, out uri)) throw new ArgumentException("Server URL is invalid.");
            if ((uri.Scheme != Uri.UriSchemeHttps && !(uri.Scheme == Uri.UriSchemeHttp && uri.IsLoopback)) ||
                !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) || uri.AbsolutePath != "/")
                throw new ArgumentException("Используйте HTTPS-адрес сервера без пути. HTTP разрешён только для localhost / 127.0.0.1.");
            BaseUrl = normalized;
            if (_http != null) _http.Dispose();
            var handler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false };
            _http = new HttpClient(handler) { BaseAddress = new Uri(BaseUrl + "/"), Timeout = TimeSpan.FromSeconds(20) };
        }

        public void SetRequestTimeout(TimeSpan timeout)
        {
            if (timeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException("timeout");
            _http.Timeout = timeout;
        }

        public async Task LoginAsync(string username, string password, string installationId)
        {
            var body = new Dictionary<string, object>
            {
                { "username", username },
                { "password", password },
                { "installation_id", installationId },
                { "device_platform", "desktop" },
                { "device_name", "UNYRI Admin Console" },
                { "os_version", Environment.OSVersion.VersionString },
                { "app_version", "0.3.16" }
            };
            var data = await SendAsync(HttpMethod.Post, "auth/login", body, false, false);
            AccessToken = Str(data, "access_token");
            RefreshToken = Str(data, "refresh_token");
            DateTimeOffset expiresAt;
            if (DateTimeOffset.TryParse(Str(data, "access_token_expires_at"), out expiresAt))
                AccessTokenExpiresAt = expiresAt;
            DeviceId = Str(data, "device_id");
            var user = Obj(data, "user");
            UserId = Str(user, "id");
            Username = Str(user, "username");
            if (string.IsNullOrEmpty(AccessToken)) throw new ApiException(500, "bad_response", "Login response did not contain an access token.");
            var me = await SendAsync(HttpMethod.Get, "users/me", null, true, false);
            AdminRole = Str(me, "admin_role");
            if (string.IsNullOrEmpty(AdminRole))
                throw new ApiException(403, "not_admin", "Этот аккаунт не является администратором.");
        }

        public static AdminAuth FromPersistedSession(PersistedAdminSession session)
        {
            if (session == null || string.IsNullOrWhiteSpace(session.BaseUrl) ||
                !session.UserOptedIn || string.IsNullOrWhiteSpace(session.AccessToken) ||
                string.IsNullOrWhiteSpace(session.AdminToken))
                return null;
            var auth = new AdminAuth(session.BaseUrl)
            {
                AccessToken = session.AccessToken,
                RefreshToken = session.RefreshToken,
                AdminToken = session.AdminToken,
                UserId = session.UserId,
                Username = session.Username,
                DeviceId = session.DeviceId,
                AdminRole = session.AdminRole,
                ServerId = session.ServerId
            };
            DateTimeOffset expiresAt;
            if (DateTimeOffset.TryParse(session.AccessTokenExpiresAt, out expiresAt))
                auth.AccessTokenExpiresAt = expiresAt;
            return auth;
        }

        public PersistedAdminSession ToPersistedSession()
        {
            return new PersistedAdminSession
            {
                BaseUrl = BaseUrl,
                AccessToken = AccessToken,
                // A refresh rotates the ordinary auth session and invalidates the
                // admin token. Do not persist this high-value token when the
                // desktop cannot safely refresh and re-unlock without a fresh OTP.
                RefreshToken = "",
                AdminToken = AdminToken,
                UserId = UserId,
                Username = Username,
                DeviceId = DeviceId,
                AdminRole = AdminRole,
                ServerId = ServerId,
                UserOptedIn = true,
                AccessTokenExpiresAt = AccessTokenExpiresAt == default(DateTimeOffset)
                    ? ""
                    : AccessTokenExpiresAt.ToString("o")
            };
        }

        public async Task<AdminStatus> GetAdminStatusAsync()
        {
            var data = await SendAsync(HttpMethod.Get, "admin/status", null, true, false);
            return ParseAdminStatus(data);
        }

        internal static AdminStatus ParseAdminStatus(Dictionary<string, object> data)
        {
            var protocol = Int(data, "auth_protocol");
            var policy = protocol >= 2 ? AdminSecurityPolicy.RequireKnown(Str(data, "auth_policy")) : AdminSecurityPolicy.Both;
            var signingKey = Str(data, "signing_public_key");
            long revision;
            long.TryParse(Str(data, "auth_revision"), out revision);
            return new AdminStatus
            {
                Configured = Bool(data, "configured"),
                KeyProtocol = Int(data, "key_protocol"),
                AuthProtocol = protocol,
                AuthPolicy = policy,
                TotpConfigured = protocol >= 2 ? Bool(data, "totp_configured") : Bool(data, "configured"),
                PaperKeyConfigured = protocol >= 2 ? Bool(data, "paper_key_configured") : !string.IsNullOrEmpty(signingKey),
                AuthRevision = revision,
                ServerId = Str(data, "server_id"),
                SigningPublicKey = signingKey
            };
        }

        public async Task<AdminSetupResult> SetupAsync(string password, string policy, bool supportsFactorChoice)
        {
            AdminSecurityPolicy.RequireKnown(policy);
            if (!supportsFactorChoice && policy != AdminSecurityPolicy.Both)
                throw new InvalidOperationException("Этот сервер пока поддерживает только аутентификатор вместе с бумажным ключом. Обновите сервер для выбора способов защиты.");
            var body = new Dictionary<string, object> { { "password", password } };
            if (supportsFactorChoice) body["auth_policy"] = policy;
            var data = await SendAsync(HttpMethod.Post, "admin/setup", body, true, false);
            return new AdminSetupResult { Policy = supportsFactorChoice ? AdminSecurityPolicy.RequireKnown(Str(data, "auth_policy")) : policy,
                QrPng = Str(data, "qr_png"), Secret = Str(data, "secret") };
        }

        public async Task<AdminUnlockResult> UnlockAsync(string password, string paperCode, string code)
        {
            var status = await GetAdminStatusAsync();
            return await UnlockAsync(password, paperCode, code, status.AuthPolicy, status);
        }

        public async Task<AdminUnlockResult> UnlockAsync(string password, string paperCode, string code, string policy, AdminStatus status)
        {
            AdminSecurityPolicy.RequireKnown(policy);
            if (status == null) status = await GetAdminStatusAsync();
            ServerId = status.ServerId;
            if (string.IsNullOrWhiteSpace(UserId) || string.IsNullOrWhiteSpace(ServerId))
                throw new ApiException(0, "no_session", "Сначала выполните обычный вход.");
            var unlockBody = new Dictionary<string, object> { { "password", password } };
            if (status.SupportsFactorChoice) unlockBody["auth_policy"] = policy;
            if (AdminSecurityPolicy.UsesAuthenticator(policy))
            {
                if (string.IsNullOrWhiteSpace(code)) throw new InvalidOperationException("Введите код аутентификатора или одноразовый резервный код.");
                unlockBody["code"] = code.Trim();
            }
            if (AdminSecurityPolicy.UsesPaperKey(policy))
                unlockBody["key_proof"] = await CreatePaperProofAsync(paperCode, "unlock", status.SigningPublicKey);
            var unlocked = await SendAsync(HttpMethod.Post, "admin/unlock", unlockBody, true, false);
            AdminToken = Str(unlocked, "token");
            if (string.IsNullOrEmpty(AdminToken)) throw new ApiException(500, "bad_response", "Unlock response did not contain a token.");
            var recovery = new List<string>();
            object recoveryValue;
            if (unlocked.TryGetValue("recovery_codes", out recoveryValue) &&
                recoveryValue is System.Collections.IEnumerable && !(recoveryValue is string))
            {
                foreach (var item in (System.Collections.IEnumerable)recoveryValue)
                    if (item != null) recovery.Add(Convert.ToString(item));
            }
            return new AdminUnlockResult { RecoveryCodes = recovery };
        }

        public async Task<Dictionary<string, object>> CreatePaperProofAsync(string paperCode, string purpose, string expectedPublicKey)
        {
            string normalized;
            if (!CryptoAdmin.ValidatePaperCode(paperCode, out normalized))
                throw new ApiException(0, "paper_code", "Проверьте бумажный ключ: 32 символа, дефисы и пробелы допускаются.");
            var publicKey = CryptoAdmin.PublicKeyBase64(normalized);
            if (!string.IsNullOrEmpty(expectedPublicKey) && expectedPublicKey != publicKey)
                throw new ApiException(0, "paper_key_mismatch", "Этот бумажный ключ не подходит к администратору на выбранном сервере.");
            var challengeBody = new Dictionary<string, object>
            {
                { "purpose", purpose },
                { "public_key", publicKey }
            };
            var challenge = await SendAsync(HttpMethod.Post, "admin/key-challenge", challengeBody, true, false);
            var challengeId = Str(challenge, "challenge_id");
            var authSession = Str(challenge, "auth_session");
            var nonce = Str(challenge, "nonce");
            var serverId = Str(challenge, "server_id");
            var userId = Str(challenge, "user_id");
            var echoedPurpose = Str(challenge, "purpose");
            var echoedKey = Str(challenge, "public_key");
            if (serverId != ServerId || userId != UserId || echoedPurpose != purpose || echoedKey != publicKey ||
                string.IsNullOrEmpty(challengeId) || string.IsNullOrEmpty(authSession) || string.IsNullOrEmpty(nonce))
                throw new ApiException(0, "challenge_mismatch", "Сервер вернул неожиданные данные challenge.");
            var proof = CryptoAdmin.ProofMessage(ServerId, UserId, authSession, challengeId, purpose, publicKey, nonce);
            var signature = CryptoAdmin.SignBase64(normalized, proof);
            return new Dictionary<string, object> { { "challenge_id", challengeId }, { "signature", signature } };
        }

        public async Task<Dictionary<string, object>> SendAsync(HttpMethod method, string path, object body,
            bool bearer, bool admin)
        {
            using (var request = new HttpRequestMessage(method, path))
            {
                if (bearer)
                {
                    if (string.IsNullOrEmpty(AccessToken)) throw new ApiException(401, "unauthorized", "Нет Bearer-сессии.");
                    request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + AccessToken);
                }
                if (admin)
                {
                    EnsureAdmin();
                    request.Headers.TryAddWithoutValidation("x-coco-admin", AdminToken);
                }
                if (body != null)
                    request.Content = new StringContent(_json.Serialize(body), Encoding.UTF8, "application/json");
                using (var response = await _http.SendAsync(request))
                {
                    var text = await response.Content.ReadAsStringAsync();
                    if (!response.IsSuccessStatusCode) throw ErrorFrom(response.StatusCode, text);
                    if (string.IsNullOrWhiteSpace(text)) return new Dictionary<string, object>();
                    var data = _json.Deserialize<Dictionary<string, object>>(text);
                    return data ?? new Dictionary<string, object>();
                }
            }
        }

        public Task<byte[]> GetBytesAsync(string path)
        {
            return GetAdminBytesAsync(path, 16 * 1024 * 1024);
        }

        public async Task<byte[]> GetUserAvatarBytesAsync(string userId)
        {
            return await GetAdminBytesAsync(
                "admin/users/" + Uri.EscapeDataString(userId) + "/avatar?size=640",
                4 * 1024 * 1024);
        }

        private async Task<byte[]> GetAdminBytesAsync(string path, int maximumBytes)
        {
            EnsureAdmin();
            using (var request = new HttpRequestMessage(HttpMethod.Get, path))
            {
                request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + AccessToken);
                request.Headers.TryAddWithoutValidation("x-coco-admin", AdminToken);
                using (var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead))
                {
                    if (!response.IsSuccessStatusCode)
                        throw ErrorFrom(response.StatusCode, await response.Content.ReadAsStringAsync());
                    if (response.Content.Headers.ContentLength > maximumBytes)
                        throw new ApiException(413, "download_too_large", "Файл превышает допустимый размер.");
                    using (var input = await response.Content.ReadAsStreamAsync())
                    using (var output = new MemoryStream())
                    {
                        var buffer = new byte[81920];
                        int read;
                        while ((read = await input.ReadAsync(buffer, 0, buffer.Length)) > 0)
                        {
                            if (output.Length + read > maximumBytes)
                                throw new ApiException(413, "download_too_large", "Файл превышает допустимый размер.");
                            output.Write(buffer, 0, read);
                        }
                        return output.ToArray();
                    }
                }
            }
        }

        public async Task LogoutAsync()
        {
            Exception logoutError = null;
            if (IsAdminUnlocked)
            {
                try { await SendAsync(HttpMethod.Post, "admin/lock", null, true, true); }
                catch (Exception ex) { logoutError = ex; }
            }
            if (!string.IsNullOrEmpty(AccessToken))
            {
                try { await SendAsync(HttpMethod.Post, "auth/logout", null, true, false); }
                catch (Exception ex) { if (logoutError == null) logoutError = ex; }
            }
            AdminToken = null;
            AccessToken = null;
            RefreshToken = null;
            if (logoutError != null) throw logoutError;
        }

        public void CancelPendingRequests()
        {
            _http.CancelPendingRequests();
        }

        public void Dispose()
        {
            if (_http != null) _http.Dispose();
            AdminToken = null;
            AccessToken = null;
            RefreshToken = null;
        }

        private void EnsureAdmin()
        {
            if (string.IsNullOrEmpty(AdminToken)) throw new ApiException(401, "admin_locked", "Админ-сессия не разблокирована.");
        }

        private ApiException ErrorFrom(HttpStatusCode status, string text)
        {
            try
            {
                var data = _json.Deserialize<Dictionary<string, object>>(text);
                var error = Obj(data, "error");
                return new ApiException((int)status, Str(error, "code"), Str(error, "message", text), text);
            }
            catch
            {
                return new ApiException((int)status, "http", text, text);
            }
        }

        internal static string Str(Dictionary<string, object> dict, string key, string fallback = "")
        {
            object value;
            return dict != null && dict.TryGetValue(key, out value) && value != null ? Convert.ToString(value) : fallback;
        }

        internal static bool Bool(Dictionary<string, object> dict, string key)
        {
            object value;
            if (dict == null || !dict.TryGetValue(key, out value) || value == null) return false;
            bool parsed;
            return bool.TryParse(Convert.ToString(value), out parsed) && parsed;
        }

        internal static int Int(Dictionary<string, object> dict, string key)
        {
            object value;
            int parsed;
            return dict != null && dict.TryGetValue(key, out value) && value != null && int.TryParse(Convert.ToString(value), out parsed) ? parsed : 0;
        }

        internal static Dictionary<string, object> Obj(Dictionary<string, object> dict, string key)
        {
            object value;
            if (dict == null || !dict.TryGetValue(key, out value)) return null;
            return value as Dictionary<string, object>;
        }
    }

    internal sealed class AdminUnlockResult
    {
        public List<string> RecoveryCodes = new List<string>();
    }

}
