using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace CocoAdmin
{
    internal sealed class CommandResult
    {
        public int ExitCode;
        public string StdOut = "";
        public string StdErr = "";
        public bool TimedOut;

        public string Combined
        {
            get
            {
                var text = (StdOut ?? "") + (string.IsNullOrEmpty(StdErr) ? "" : Environment.NewLine + StdErr);
                return text.Trim();
            }
        }

        public bool Ok { get { return !TimedOut && ExitCode == 0; } }
    }

    internal static class ProcessRunner
    {
        public static async Task<CommandResult> RunAsync(
            string fileName,
            string arguments,
            string workingDirectory = null,
            int timeoutMs = 30000,
            string stdin = null,
            IDictionary<string, string> environment = null)
        {
            var result = new CommandResult();
            var startInfo = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments ?? "",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                RedirectStandardInput = stdin != null,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            if (!string.IsNullOrWhiteSpace(workingDirectory)) startInfo.WorkingDirectory = workingDirectory;
            if (environment != null)
                foreach (var pair in environment)
                    if (pair.Key != null && pair.Value != null) startInfo.EnvironmentVariables[pair.Key] = pair.Value;

            using (var process = new Process { StartInfo = startInfo })
            {
                var outBuffer = new StringBuilder();
                var errBuffer = new StringBuilder();
                var outDone = new TaskCompletionSource<bool>();
                var errDone = new TaskCompletionSource<bool>();
                process.OutputDataReceived += (s, e) =>
                {
                    if (e.Data == null) outDone.TrySetResult(true);
                    else lock (outBuffer) outBuffer.AppendLine(e.Data);
                };
                process.ErrorDataReceived += (s, e) =>
                {
                    if (e.Data == null) errDone.TrySetResult(true);
                    else lock (errBuffer) errBuffer.AppendLine(e.Data);
                };

                try
                {
                    process.Start();
                }
                catch (Exception ex)
                {
                    result.ExitCode = -1;
                    result.StdErr = ex.Message;
                    return result;
                }

                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                if (stdin != null)
                {
                    try { await process.StandardInput.WriteAsync(stdin); process.StandardInput.Close(); }
                    catch { /* process may have exited */ }
                }

                var waitTask = process.WaitForExitAsync();
                var completed = await Task.WhenAny(waitTask, Task.Delay(timeoutMs));
                var termination = "";
                if (completed != waitTask)
                {
                    result.TimedOut = true;
                    result.ExitCode = -1;
                    termination = await TerminateProcessTreeAsync(process);
                }
                else result.ExitCode = process.ExitCode;
                await Task.WhenAny(Task.WhenAll(outDone.Task, errDone.Task), Task.Delay(2000));
                lock (outBuffer) result.StdOut = outBuffer.ToString();
                lock (errBuffer) result.StdErr = errBuffer.ToString();
                if (!string.IsNullOrWhiteSpace(termination))
                    result.StdErr += Environment.NewLine + termination;
                if (result.TimedOut) result.StdErr += Environment.NewLine + "Превышено время ожидания команды. Проверьте состояние перед повторным запуском.";
                return result;
            }
        }

        private static async Task<string> TerminateProcessTreeAsync(Process process)
        {
            try
            {
                if (process.HasExited) return "";
                var taskkill = new Process
                {
                    StartInfo = new ProcessStartInfo
                    {
                        FileName = Path.Combine(Environment.SystemDirectory, "taskkill.exe"),
                        Arguments = "/PID " + process.Id + " /T /F",
                        UseShellExecute = false,
                        CreateNoWindow = true,
                        WindowStyle = ProcessWindowStyle.Hidden
                    }
                };
                using (taskkill)
                {
                    taskkill.Start();
                    var waitTask = taskkill.WaitForExitAsync();
                    if (await Task.WhenAny(waitTask, Task.Delay(5000)) != waitTask)
                    {
                        try { taskkill.Kill(); } catch { }
                        try { if (!process.HasExited) process.Kill(); } catch { }
                        return "Остановку дерева процессов не удалось подтвердить за 5 секунд. Проверьте процессы и состояние стека перед повторным запуском.";
                    }
                    if (taskkill.ExitCode != 0 && !process.HasExited)
                    {
                        try { process.Kill(); } catch { }
                        return "Не удалось подтвердить остановку дочерних процессов. Проверьте состояние стека перед повторным запуском.";
                    }
                }
                return "";
            }
            catch (Exception ex)
            {
                try { if (!process.HasExited) process.Kill(); } catch { }
                return "Остановка дерева процессов завершилась ошибкой: " + ex.Message +
                       ". Проверьте состояние стека перед повторным запуском.";
            }
        }

        public static async Task<CommandResult> PowerShellFileAsync(string script, string arguments, int timeoutMs = 30000)
        {
            // EncodedCommand makes Windows PowerShell serialize its side streams
            // as CLIXML, including startup progress before preferences take effect.
            // File mode keeps all streams textual; the private wrapper preserves
            // PowerShell argument parsing and is removed after the command exits.
            var command = "$ProgressPreference = 'SilentlyContinue'; [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); $OutputEncoding = [Console]::OutputEncoding; $ErrorActionPreference = 'Stop'; try { & '" + script.Replace("'", "''") + "' " + (arguments ?? "") + "; if (-not $?) { exit 1 } } catch { $message = $_.Exception.GetBaseException().Message; if ([string]::IsNullOrWhiteSpace($message)) { $message = 'PowerShell command failed. See the application logs for details.' }; [Console]::Error.WriteLine($message); exit 1 }";
            var wrapper = Path.Combine(Path.GetTempPath(), "CocoCommand-" + Guid.NewGuid().ToString("N") + ".ps1");
            try
            {
                File.WriteAllText(wrapper, command, new UTF8Encoding(true));
                var args = "-NoProfile -NonInteractive -OutputFormat Text -ExecutionPolicy Bypass -File " + Quote(wrapper);
                return await RunAsync("powershell.exe", args, AppPaths.Workspace, timeoutMs);
            }
            finally { if (File.Exists(wrapper)) File.Delete(wrapper); }
        }

        public static Task<CommandResult> DockerAsync(string arguments, int timeoutMs = 30000)
        {
            return RunAsync("docker", arguments, AppPaths.Workspace, timeoutMs);
        }

        public static string Quote(string value)
        {
            if (value == null) return "\"\"";
            var quoted = new StringBuilder("\"");
            var slashes = 0;
            foreach (var c in value)
            {
                if (c == '\\') { slashes++; continue; }
                quoted.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
                quoted.Append(c);
                slashes = 0;
            }
            quoted.Append('\\', slashes * 2);
            return quoted.Append('"').ToString();
        }
    }

    internal static class ProcessExtensions
    {
        public static Task WaitForExitAsync(this Process process)
        {
            var tcs = new TaskCompletionSource<bool>();
            process.EnableRaisingEvents = true;
            process.Exited += (s, e) => tcs.TrySetResult(true);
            if (process.HasExited) tcs.TrySetResult(true);
            return tcs.Task;
        }
    }
}
