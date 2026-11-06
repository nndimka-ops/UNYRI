using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace CocoAdmin
{
    internal sealed class StackOwnershipResult
    {
        public bool Safe;
        public bool ContainersPresent;
        public string Detail = "";
        public string ActualWorkspace = "";
    }

    internal static class ServerControl
    {
        private static readonly string[] ManagedContainers =
        {
            "messenger-postgres", "messenger-redis", "messenger-minio", "messenger-coturn"
        };

        public static string CaddyPath
        {
            get
            {
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "COCO", "lan-tls", "caddy.exe");
            }
        }

        public static string LanAddress
        {
            get
            {
                try
                {
                    // Prefer the adapter that owns the default route. DNS can
                    // return WSL/Hyper-V addresses (172.x) that Caddy never binds.
                    foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                    {
                        if (nic.OperationalStatus != OperationalStatus.Up) continue;
                        if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                        var name = (nic.Name + " " + nic.Description).ToLowerInvariant();
                        if (name.Contains("wsl") || name.Contains("vethernet") || name.Contains("hyper-v")) continue;
                        var properties = nic.GetIPProperties();
                        if (properties.GatewayAddresses.Count == 0) continue;
                        var address = properties.UnicastAddresses
                            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                            .Select(a => a.Address.ToString())
                            .FirstOrDefault();
                        if (!string.IsNullOrWhiteSpace(address)) return address;
                    }
                    return "127.0.0.1";
                }
                catch { return "127.0.0.1"; }
            }
        }

        public static Task<CommandResult> PrepareDockerAsync()
        {
            return ProcessRunner.PowerShellFileAsync(AppPaths.StartScript,
                "-StartDocker -PrepareDockerOnly", 180000);
        }

        public static async Task<CommandResult> StartStackAsync(bool build, bool restart, string containerWorkspace = null)
        {
            var ownership = await CheckStackOwnershipAsync(containerWorkspace);
            // The startup script can start Docker, then repeats every ownership
            // check before touching containers. A known foreign owner still needs
            // explicit confirmation; an unavailable engine must not block bootstrap.
            if (!ownership.Safe && !string.IsNullOrWhiteSpace(ownership.ActualWorkspace))
                return BlockedByOwnership(ownership);
            var args = "-StartDocker -LanAddress " + LanAddress;
            if (!string.IsNullOrWhiteSpace(containerWorkspace))
                args += " -ContainerWorkspace '" + containerWorkspace.Replace("'", "''") + "'";
            if (build) args += " -Build";
            if (restart || build) args += " -Restart";
            return await ProcessRunner.PowerShellFileAsync(AppPaths.StartScript, args, 900000);
        }

        public static Task<CommandResult> StopStackAsync(string containerWorkspace)
        {
            var script = Path.Combine(AppPaths.Workspace, "scripts", "Stop-Coco.ps1");
            var args = "-ContainerWorkspace '" + containerWorkspace.Replace("'", "''") + "'";
            return ProcessRunner.PowerShellFileAsync(script, args, 240000);
        }

        public static async Task<CommandResult> ResetDatabaseAsync(string confirmation)
        {
            if (!string.Equals(confirmation, "DELETE COCO DATABASE ON THIS PC", StringComparison.Ordinal))
                throw new InvalidOperationException("Confirmation phrase did not match.");
            var ownership = await CheckStackOwnershipAsync();
            if (!ownership.Safe) return BlockedByOwnership(ownership);
            var args = "-Confirmation '" + confirmation.Replace("'", "''") + "'";
            return await ProcessRunner.PowerShellFileAsync(AppPaths.ResetDatabaseScript, args, 900000);
        }

        public static async Task<CommandResult> CreateDatabaseBackupAsync()
        {
            var ownership = await CheckStackOwnershipAsync();
            if (!ownership.Safe) return BlockedByOwnership(ownership);
            return await ProcessRunner.PowerShellFileAsync(AppPaths.BackupDatabaseScript, "", 900000);
        }

        public static async Task<CommandResult> RestoreDatabaseBackupAsync(string backupPath, string confirmation)
        {
            if (!string.Equals(confirmation, "RESTORE COCO DATABASE", StringComparison.Ordinal))
                throw new InvalidOperationException("Confirmation phrase did not match.");
            if (string.IsNullOrWhiteSpace(backupPath) || !File.Exists(backupPath))
                throw new FileNotFoundException("Backup file was not found.", backupPath);
            var ownership = await CheckStackOwnershipAsync();
            if (!ownership.Safe) return BlockedByOwnership(ownership);
            var args = "-BackupPath '" + backupPath.Replace("'", "''") + "' -Confirmation '" + confirmation + "'";
            return await ProcessRunner.PowerShellFileAsync(AppPaths.RestoreDatabaseScript, args, 1200000);
        }

        public static async Task<string> CheckUpdatesAsync()
        {
            var results = await Task.WhenAll(
                ProcessRunner.RunAsync("winget", "upgrade --include-unknown", AppPaths.Workspace, 120000),
                ProcessRunner.RunAsync("rustup", "check", AppPaths.Workspace, 60000),
                ProcessRunner.RunAsync("cargo", "update --dry-run", Path.Combine(AppPaths.Workspace, "server"), 120000));
            var names = new[] { "winget — обновления Windows-приложений", "rustup — обновление Rust", "Cargo — обновления библиотек (dry-run, без изменения Cargo.lock)" };
            var output = new System.Text.StringBuilder();
            for (var i = 0; i < results.Length; i++)
            {
                output.AppendLine(names[i] + " · " + (results[i].Ok ? "проверено" : results[i].TimedOut ? "таймаут" : "проверка не выполнена"));
                output.AppendLine(results[i].Combined.Length > 5000
                    ? results[i].Combined.Substring(results[i].Combined.Length - 5000)
                    : (results[i].Combined.Length > 0 ? results[i].Combined : "Нет вывода от команды."));
                output.AppendLine();
            }
            output.AppendLine("Проверка не устанавливает обновления. Образы Docker и Caddy сверяются отдельно; обновление зависимостей пока не выполняется автоматически.");
            return output.ToString();
        }

        public static async Task<string> UpdateToolsAsync()
        {
            var commands = new[]
            {
                Tuple.Create("Docker Desktop / Docker Compose", "winget",
                    "upgrade --id Docker.DockerDesktop -e --accept-source-agreements --accept-package-agreements",
                    AppPaths.Workspace),
                Tuple.Create("Git", "winget",
                    "upgrade --id Git.Git -e --accept-source-agreements --accept-package-agreements",
                    AppPaths.Workspace),
                Tuple.Create("Rust stable / Cargo", "rustup", "update stable", AppPaths.Workspace)
            };
            var output = new System.Text.StringBuilder();
            foreach (var command in commands)
            {
                output.AppendLine("=== " + command.Item1 + " ===");
                var result = await ProcessRunner.RunAsync(command.Item2, command.Item3, command.Item4, 1200000);
                output.AppendLine(result.TimedOut ? "Команда превысила таймаут." : "Код завершения: " + result.ExitCode);
                output.AppendLine(result.Combined.Length > 4000
                    ? result.Combined.Substring(result.Combined.Length - 4000)
                    : result.Combined);
                if (!result.Ok) output.AppendLine("Остальные обновления не запускались после ошибки.");
                output.AppendLine();
                if (!result.Ok) break;
            }
            output.AppendLine("PostgreSQL/Redis/MinIO/coturn образы и Cargo.lock не обновлялись.");
            return output.ToString();
        }

        public static async Task<CommandResult> StartHttpsAsync()
        {
            var ownership = await CheckStackOwnershipAsync();
            if (!ownership.Safe) return BlockedByOwnership(ownership);
            return await ProcessRunner.PowerShellFileAsync(AppPaths.HttpsScript, "-LanAddress " + LanAddress, 120000);
        }

        public static async Task<StackOwnershipResult> CheckStackOwnershipAsync(string confirmedContainerWorkspace = null)
        {
            var expected = NormalizePath(AppPaths.Workspace);
            var actual = "";
            var found = false;
            foreach (var container in ManagedContainers)
            {
                var inspect = await ProcessRunner.DockerAsync(
                    "inspect --format \"{{json .Config.Labels}}\" " + container, 12000);
                if (!inspect.Ok)
                {
                    var error = inspect.Combined ?? "";
                    if (error.IndexOf("No such object", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        error.IndexOf("No such container", StringComparison.OrdinalIgnoreCase) >= 0)
                        continue;
                    return new StackOwnershipResult
                    {
                        Safe = false,
                        ContainersPresent = found,
                        Detail = "Не удалось проверить принадлежность контейнера " + container + ": " + error
                    };
                }

                found = true;
                Dictionary<string, string> labels;
                try
                {
                    labels = new JavaScriptSerializer().Deserialize<Dictionary<string, string>>(inspect.StdOut.Trim());
                }
                catch (Exception ex)
                {
                    return new StackOwnershipResult
                    {
                        Safe = false,
                        ContainersPresent = true,
                        Detail = "Docker не вернул читаемые compose labels для " + container + ": " + ex.Message
                    };
                }

                string workingDirectory;
                if (labels == null || !labels.TryGetValue("com.docker.compose.project.working_dir", out workingDirectory) ||
                    string.IsNullOrWhiteSpace(workingDirectory))
                {
                    return new StackOwnershipResult
                    {
                        Safe = false,
                        ContainersPresent = true,
                        Detail = "Контейнер " + container + " не имеет compose working_dir; управление заблокировано."
                    };
                }

                var normalized = NormalizePath(workingDirectory);
                if (actual.Length == 0) actual = normalized;
                if (!string.Equals(expected, normalized, StringComparison.OrdinalIgnoreCase) &&
                    (string.IsNullOrWhiteSpace(confirmedContainerWorkspace) ||
                     !string.Equals(NormalizePath(confirmedContainerWorkspace), normalized, StringComparison.OrdinalIgnoreCase)))
                {
                    return new StackOwnershipResult
                    {
                        Safe = false,
                        ContainersPresent = true,
                        ActualWorkspace = workingDirectory,
                        Detail = "Контейнер " + container + " принадлежит " + workingDirectory +
                                 ", а консоль управляет " + AppPaths.Workspace + ". Операция заблокирована."
                    };
                }
            }

            return new StackOwnershipResult
            {
                Safe = true,
                ContainersPresent = found,
                ActualWorkspace = actual,
                Detail = found
                    ? "Принадлежность контейнеров проверена: " + AppPaths.Workspace +
                      (string.IsNullOrWhiteSpace(confirmedContainerWorkspace) ? "" : "; подтверждён также " + confirmedContainerWorkspace)
                    : "контейнеры с ожидаемыми именами не запущены; конфликт владения не найден"
            };
        }

        public static bool IsExpectedCaddyRunning(out string detail)
        {
            var expected = NormalizePath(CaddyPath);
            var seen = false;
            var inaccessible = false;
            foreach (var process in Process.GetProcessesByName("caddy"))
            {
                using (process)
                {
                    seen = true;
                    try
                    {
                        var actual = NormalizePath(process.MainModule.FileName);
                        if (string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase))
                        {
                            detail = "запущен ожидаемый процесс: " + CaddyPath;
                            return true;
                        }
                    }
                    catch { inaccessible = true; }
                }
            }
            detail = !seen
                ? "процесс Caddy не запущен"
                : inaccessible
                    ? "процесс Caddy найден, но путь к нему нельзя проверить"
                    : "найден другой процесс Caddy; ожидается " + CaddyPath;
            return false;
        }

        private static CommandResult BlockedByOwnership(StackOwnershipResult ownership)
        {
            return new CommandResult
            {
                ExitCode = -1,
                StdErr = "Операция не выполнялась: " + ownership.Detail
            };
        }

        private static string NormalizePath(string path)
        {
            if (string.IsNullOrWhiteSpace(path)) return "";
            try { return Path.GetFullPath(path).TrimEnd('\\', '/'); }
            catch { return path.Trim().TrimEnd('\\', '/'); }
        }

        public static void OpenLogs()
        {
            if (Directory.Exists(AppPaths.RuntimeLogs))
                Process.Start(new ProcessStartInfo("explorer.exe", AppPaths.RuntimeLogs) { UseShellExecute = true });
        }

        public static void OpenWorkspace()
        {
            Process.Start(new ProcessStartInfo("explorer.exe", AppPaths.Workspace) { UseShellExecute = true });
        }

        public static void OpenDockerDownload()
        {
            Process.Start(new ProcessStartInfo("https://www.docker.com/products/docker-desktop/") { UseShellExecute = true });
        }

        public static Task<CommandResult> StartDockerInstall()
        {
            return ProcessRunner.RunAsync(
                "winget",
                "install -e --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements",
                AppPaths.Workspace,
                900000);
        }

        public static Task<CommandResult> StartRustInstall()
        {
            return ProcessRunner.RunAsync("winget",
                "install -e --id Rustlang.Rustup --accept-source-agreements --accept-package-agreements",
                AppPaths.Workspace, 900000);
        }

        public static Task<CommandResult> StartGitInstall()
        {
            return ProcessRunner.RunAsync("winget",
                "install -e --id Git.Git --accept-source-agreements --accept-package-agreements",
                AppPaths.Workspace, 900000);
        }

        public static void RefreshProcessPath()
        {
            var machinePath = Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.Machine) ?? "";
            var userPath = Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.User) ?? "";
            var currentUserCargo = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".cargo", "bin");
            var parts = new[] { currentUserCargo, userPath, machinePath }
                .Where(part => !string.IsNullOrWhiteSpace(part));
            Environment.SetEnvironmentVariable("PATH", string.Join(";", parts), EnvironmentVariableTarget.Process);
        }

        public static Task<CommandResult> StartCargoBuildAsync()
        {
            return ProcessRunner.RunAsync(
                "cargo",
                "build --offline --bin messenger-server",
                Path.Combine(AppPaths.Workspace, "server"),
                300000);
        }

        public static string LatestServerLog(bool errors = false)
        {
            if (!Directory.Exists(AppPaths.RuntimeLogs)) return "Папка runtime-logs отсутствует.";
            var log = new DirectoryInfo(AppPaths.RuntimeLogs).GetFiles(errors ? "server-*.err.log" : "server-*.out.log")
                .OrderByDescending(f => f.LastWriteTimeUtc).FirstOrDefault();
            if (log == null) return "Логи сервера ещё не созданы.";
            try
            {
                using (var stream = new FileStream(log.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                {
                    var truncated = stream.Length > 120000;
                    if (truncated) stream.Seek(-120000, SeekOrigin.End);
                    using (var reader = new StreamReader(stream))
                    {
                        if (truncated) reader.ReadLine(); // discard partial UTF-8 line
                        return log.Name + Environment.NewLine + (truncated ? "… последние 120 КБ …" + Environment.NewLine : "") + reader.ReadToEnd();
                    }
                }
            }
            catch (Exception ex)
            {
                return "Не удалось прочитать лог: " + ex.Message;
            }
        }

        public static string DockerDesktopPath
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Docker", "Docker", "Docker Desktop.exe"); }
        }

        public static void OpenDockerDesktop()
        {
            Process.Start(new ProcessStartInfo(DockerDesktopPath) { UseShellExecute = true });
        }
    }
}
