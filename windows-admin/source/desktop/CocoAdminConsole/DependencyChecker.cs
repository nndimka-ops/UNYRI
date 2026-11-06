using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Principal;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace CocoAdmin
{
    internal sealed class CheckItem
    {
        public string Name;
        public string Status;
        public string Detail;
        public string Action; // dependency repair action
        public bool Ok;
    }

    internal sealed class HealthResult
    {
        public bool Ok;
        public string Body = "";
        public bool Database;
        public bool Redis;
        public string Status = "";
    }

    internal static class DependencyChecker
    {
        public static async Task<List<CheckItem>> CheckAllAsync()
        {
            var items = new List<CheckItem>();
            var docker = await ProcessRunner.DockerAsync("info --format {{.ServerVersion}}", 20000);
            var dockerReady = docker.Ok;
            var dockerAction = File.Exists(ServerControl.DockerDesktopPath) ? "open-docker" : "install-docker";
            items.Add(Item("Docker Engine", dockerReady,
                dockerReady ? "работает: " + docker.StdOut.Trim() : "Docker Desktop/engine недоступен",
                dockerReady ? "none" : dockerAction));

            if (dockerReady)
            {
                foreach (var container in new[]
                {
                    Tuple.Create("PostgreSQL", "messenger-postgres"),
                    Tuple.Create("Redis", "messenger-redis"),
                    Tuple.Create("MinIO", "messenger-minio"),
                    Tuple.Create("coturn", "messenger-coturn")
                })
                {
                    var check = await ContainerStatusAsync(container.Item2);
                    items.Add(Item(container.Item1, check.Item1,
                        check.Item2,
                        check.Item1 ? "none" : "start-stack"));
                }
                var ownership = await ServerControl.CheckStackOwnershipAsync();
                items.Add(Item("Принадлежность Docker-стека", ownership.Safe,
                    ownership.Detail, "none"));
            }
            else
            {
                foreach (var name in new[] { "PostgreSQL", "Redis", "MinIO", "coturn" })
                    items.Add(Item(name, false, "проверка невозможна без работающего Docker Engine", dockerAction));
            }

            var binary = File.Exists(AppPaths.ServerBinary);
            items.Add(Item("Backend binary", binary,
                binary ? AppPaths.ServerBinary : "не найден; можно собрать offline",
                binary ? "none" : "build-server"));

            var scripts = File.Exists(AppPaths.StartScript);
            items.Add(Item("Start-Coco scripts", scripts,
                scripts ? "найдены" : "не найден scripts/Start-Coco.ps1",
                scripts ? "none" : "open-workspace"));

            var env = File.Exists(AppPaths.EnvFile);
            items.Add(Item("server/.env", env,
                env ? "файл найден (значения приложением не читаются)" : "нет server/.env; запуск может быть неполным",
                env ? "none" : "open-workspace"));

            var tls = File.Exists(AppPaths.TlsIdentity);
            items.Add(Item("HTTPS identity", tls,
                tls ? "TLS identity найдена" : "нет runtime-logs/https-ca-identity.json",
                tls ? "none" : "https"));

            items.Add(await PortItemAsync("Backend :8080", "127.0.0.1", 8080));
            // Some local VPN/firewall products block the machine's own LAN IP
            // on 8443 while allowing the loopback Caddy listener. Treat either
            // verified listener as a healthy HTTPS proxy.
            var httpsLan = await IsPortOpenAsync(ServerControl.LanAddress, 8443);
            var httpsLoopback = await IsPortOpenAsync("127.0.0.1", 8443);
            items.Add(Item("HTTPS :8443", httpsLan || httpsLoopback,
                httpsLan
                    ? "порт " + ServerControl.LanAddress + ":8443 слушает"
                    : httpsLoopback
                        ? "LAN-адрес закрыт VPN/фаерволом; loopback 127.0.0.1:8443 слушает"
                        : "порт 8443 не отвечает ни на LAN, ни на loopback",
                httpsLan || httpsLoopback ? "none" : "start-stack"));
            items.Add(await PortItemAsync("MinIO HTTPS :9443", ServerControl.LanAddress, 9443));

            string caddyProcessDetail;
            var caddy = ServerControl.IsExpectedCaddyRunning(out caddyProcessDetail);

            var health = await BackendHealthAsync();
            items.Add(Item("Backend /health", health.Ok,
                health.Ok
                    ? "status=" + health.Status + ", database=" + health.Database + ", redis=" + health.Redis
                    : (health.Body.Length > 160 ? health.Body.Substring(0, 160) : health.Body),
                health.Ok ? "none" : "start-stack"));

            var elevated = IsElevated();
            items.Add(Item("Права администратора", elevated,
                elevated
                    ? "приложение запущено с повышенными правами"
                    : "backend/Caddy могут быть запущены от администратора; перезапуск из этого окна может быть недоступен",
                "none"));

            await AddToolVersionAsync(items, "Docker Compose", "docker", "compose version --short", AppPaths.Workspace, "install-docker");
            await AddToolVersionAsync(items, "Rust/Cargo", "cargo", "--version", Path.Combine(AppPaths.Workspace, "server"), "install-rust");
            await AddToolVersionAsync(items, "Rust compiler", "rustc", "--version", AppPaths.Workspace, "install-rust");
            await AddToolVersionAsync(items, "Git", "git", "--version", AppPaths.Workspace, "install-git");
            await AddDockerImageVersionsAsync(items);
            var caddyExecutable = ServerControl.CaddyPath;
            if (File.Exists(caddyExecutable))
            {
                var caddyVersion = await ProcessRunner.RunAsync(caddyExecutable, "version", AppPaths.Workspace, 8000);
                items.Add(Item("Caddy", caddyVersion.Ok && caddy,
                    caddyVersion.Ok
                        ? caddyProcessDetail + "; " + caddyVersion.StdOut.Trim()
                        : "Caddy установлен, но версия не определяется",
                    caddy ? "none" : "start-stack"));
            }
            else
            {
                // Caddy is optional for the plain-HTTP local stack. Keep one
                // row instead of emitting a contradictory duplicate.
                items.Add(Item("Caddy", caddy,
                    caddy ? caddyProcessDetail : caddyProcessDetail + "; HTTPS-компонент не установлен",
                    caddy ? "none" : "start-stack"));
            }

            try
            {
                var drive = new DriveInfo(Path.GetPathRoot(AppPaths.Workspace));
                var freeGb = drive.AvailableFreeSpace / (1024.0 * 1024 * 1024);
                items.Add(Item("Свободное место", freeGb >= 2.0,
                    string.Format("{0:0.0} ГБ свободно на {1}", freeGb, drive.Name.TrimEnd('\\')),
                    freeGb >= 2.0 ? "none" : "none"));
            }
            catch (Exception ex)
            {
                items.Add(Item("Свободное место", false, ex.Message, "none"));
            }

            return items;
        }

        private static async Task AddToolVersionAsync(List<CheckItem> items, string name, string executable,
            string arguments, string workingDirectory, string missingAction)
        {
            var result = await ProcessRunner.RunAsync(executable, arguments, workingDirectory, 8000);
            items.Add(Item(name, result.Ok,
                result.Ok ? result.StdOut.Trim() : (result.Combined.Length > 0 ? result.Combined : "не найден"),
                result.Ok ? "none" : missingAction));
        }

        private static async Task AddDockerImageVersionsAsync(List<CheckItem> items)
        {
            var result = await ProcessRunner.DockerAsync("compose images --format json", 15000);
            var detail = result.Ok
                ? (string.IsNullOrWhiteSpace(result.StdOut)
                    ? "локальные образы проекта не найдены"
                    : "локально установленные образы; наличие новых версий в реестре не проверялось: " +
                      (result.StdOut.Length > 1200 ? result.StdOut.Substring(0, 1200) : result.StdOut.Trim()))
                : (result.Combined.Length > 0 ? result.Combined : "Docker Engine недоступен");
            items.Add(Item("Docker-образы сервисов", result.Ok, detail, "none"));
        }

        public static async Task<HealthResult> BackendHealthAsync()
        {
            var result = new HealthResult();
            try
            {
                using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(3) })
                {
                    var text = await http.GetStringAsync("http://127.0.0.1:8080/health");
                    result.Body = text;
                    result.Status = MatchString(text, "status");
                    result.Database = Regex.IsMatch(text, "\"database\"\\s*:\\s*true");
                    result.Redis = Regex.IsMatch(text, "\"redis\"\\s*:\\s*true");
                    result.Ok = string.Equals(result.Status, "ok", StringComparison.OrdinalIgnoreCase) &&
                                result.Database && result.Redis;
                    return result;
                }
            }
            catch (Exception ex)
            {
                result.Body = ex.Message;
                return result;
            }
        }

        private static string MatchString(string json, string name)
        {
            var match = Regex.Match(json, "\"" + Regex.Escape(name) + "\"\\s*:\\s*\"([^\"]*)\"");
            return match.Success ? match.Groups[1].Value : "";
        }

        private static async Task<Tuple<bool, string>> ContainerStatusAsync(string name)
        {
            var inspect = await ProcessRunner.DockerAsync(
                "inspect --format \"{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}\" " + name,
                8000);
            if (!inspect.Ok)
                return Tuple.Create(false, "контейнер не найден");
            var parts = inspect.StdOut.Trim().Split('|');
            var state = parts.Length > 0 ? parts[0].Trim() : "unknown";
            var health = parts.Length > 1 ? parts[1].Trim() : "none";
            var healthy = state == "running" && (health == "none" || health == "healthy");
            return Tuple.Create(healthy, "state=" + state + ", health=" + health);
        }

        private static async Task<CheckItem> PortItemAsync(string name, string host, int port)
        {
            var open = await IsPortOpenAsync(host, port);
            return Item(name, open,
                open ? "порт " + host + ":" + port + " слушает" : "порт " + host + ":" + port + " не отвечает",
                open ? "none" : "start-stack");
        }

        private static async Task<bool> IsPortOpenAsync(string host, int port)
        {
            try
            {
                using (var client = new TcpClient())
                {
                    var connect = client.ConnectAsync(host, port);
                    var completed = await Task.WhenAny(connect, Task.Delay(900));
                    if (completed != connect) return false;
                    await connect;
                    return client.Connected;
                }
            }
            catch { return false; }
        }

        private static bool IsElevated()
        {
            try
            {
                using (var identity = WindowsIdentity.GetCurrent())
                {
                    return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
                }
            }
            catch { return false; }
        }

        private static CheckItem Item(string name, bool ok, string detail, string action)
        {
            return new CheckItem
            {
                Name = name,
                Ok = ok,
                Status = ok ? "OK" : "НЕТ",
                Detail = detail,
                Action = action
            };
        }
    }
}
