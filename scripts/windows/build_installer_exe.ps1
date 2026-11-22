param(
    [string]$AppDir = "C:\Users\henry\Downloads\SimpMusic\desktopApp\build\compose\binaries\main\app\SimpMusic",
    [string]$IconPath = "C:\Users\henry\Downloads\SimpMusic\composeApp\icon\circle_app_icon.ico",
    [string]$Version = "2.6.12",
    [string]$OutputExe = "C:\Users\henry\Downloads\SimpMusic-HeyJarvis-Setup.exe"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path $AppDir)) {
    throw "App directory not found: $AppDir"
}

$workDir = Join-Path $env:TEMP "simpmusic-exe-builder"
if (Test-Path $workDir) { Remove-Item $workDir -Recurse -Force }
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

$csPath = Join-Path $workDir "InstallerStub.cs"
$stubExe = Join-Path $workDir "stub.exe"
$payloadZip = Join-Path $workDir "payload.zip"

$csCode = @"
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("SimpMusic Hey Jarvis Setup")]
[assembly: AssemblyDescription("SimpMusic Hey Jarvis Installer and Updater")]
[assembly: AssemblyProduct("SimpMusic Hey Jarvis")]
[assembly: AssemblyVersion("$Version.0")]
[assembly: AssemblyFileVersion("$Version.0")]

namespace SimpMusicInstaller
{
    internal sealed class SubStream : Stream
    {
        private readonly Stream baseStream;
        private readonly long startOffset;
        private readonly long subLength;
        private long pos;

        public SubStream(Stream baseStream, long startOffset, long subLength)
        {
            this.baseStream = baseStream;
            this.startOffset = startOffset;
            this.subLength = subLength;
            this.pos = 0;
            this.baseStream.Position = startOffset;
        }

        public override bool CanRead { get { return true; } }
        public override bool CanSeek { get { return true; } }
        public override bool CanWrite { get { return false; } }
        public override long Length { get { return subLength; } }

        public override long Position
        {
            get { return pos; }
            set { Seek(value, SeekOrigin.Begin); }
        }

        public override void Flush() { }

        public override int Read(byte[] buffer, int offset, int count)
        {
            long remaining = subLength - pos;
            if (remaining <= 0) return 0;
            if (count > remaining) count = (int)remaining;
            baseStream.Position = startOffset + pos;
            int read = baseStream.Read(buffer, offset, count);
            pos += read;
            return read;
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            long target = pos;
            if (origin == SeekOrigin.Begin) target = offset;
            else if (origin == SeekOrigin.Current) target = pos + offset;
            else if (origin == SeekOrigin.End) target = subLength + offset;
            if (target < 0) target = 0;
            if (target > subLength) target = subLength;
            pos = target;
            baseStream.Position = startOffset + pos;
            return pos;
        }

        public override void SetLength(long value) { throw new NotSupportedException(); }
        public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
    }

    internal static class Program
    {
        private const string AppVersion = "$Version";
        private static readonly byte[] Magic = Encoding.ASCII.GetBytes("SMJARVIS");

        [STAThread]
        private static int Main(string[] args)
        {
            bool silent = false;
            string customDir = null;
            foreach (string a in args)
            {
                string lower = (a ?? "").Trim().ToLowerInvariant();
                if (lower == "--silent" || lower == "/s" || lower == "/silent" || lower == "/verysilent" || lower == "-s")
                {
                    silent = true;
                }
                else if (a != null && a.StartsWith("--dir=", StringComparison.OrdinalIgnoreCase))
                {
                    customDir = a.Substring(6).Trim('"');
                }
            }

            if (silent)
            {
                try
                {
                    RunInstall(customDir, null, null);
                    return 0;
                }
                catch
                {
                    return 1;
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            Form form = new Form();
            form.Text = "SimpMusic Hey Jarvis v" + AppVersion + " Setup";
            form.Size = new Size(480, 210);
            form.FormBorderStyle = FormBorderStyle.FixedDialog;
            form.MaximizeBox = false;
            form.MinimizeBox = false;
            form.StartPosition = FormStartPosition.CenterScreen;
            form.BackColor = Color.FromArgb(18, 18, 22);
            form.ForeColor = Color.White;
            try { form.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            Label titleLabel = new Label();
            titleLabel.Text = "Updating & Installing SimpMusic Hey Jarvis v" + AppVersion;
            titleLabel.Font = new Font("Segoe UI", 11.5f, FontStyle.Bold);
            titleLabel.ForeColor = Color.White;
            titleLabel.Location = new Point(22, 22);
            titleLabel.Size = new Size(425, 28);
            form.Controls.Add(titleLabel);

            Label statusLabel = new Label();
            statusLabel.Text = "Preparing installation...";
            statusLabel.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
            statusLabel.ForeColor = Color.FromArgb(190, 190, 200);
            statusLabel.Location = new Point(22, 58);
            statusLabel.Size = new Size(425, 24);
            form.Controls.Add(statusLabel);

            ProgressBar bar = new ProgressBar();
            bar.Location = new Point(22, 92);
            bar.Size = new Size(420, 24);
            bar.Minimum = 0;
            bar.Maximum = 100;
            bar.Value = 5;
            form.Controls.Add(bar);

            int exitCode = 0;
            form.Shown += delegate
            {
                Thread worker = new Thread(delegate()
                {
                    try
                    {
                        RunInstall(
                            customDir,
                            delegate(string msg)
                            {
                                try { form.BeginInvoke((MethodInvoker)delegate { statusLabel.Text = msg; }); } catch { }
                            },
                            delegate(int pct)
                            {
                                try { form.BeginInvoke((MethodInvoker)delegate { bar.Value = Math.Max(0, Math.Min(100, pct)); }); } catch { }
                            }
                        );
                        try { form.BeginInvoke((MethodInvoker)delegate { form.Close(); }); } catch { }
                    }
                    catch (Exception ex)
                    {
                        exitCode = 1;
                        try
                        {
                            form.BeginInvoke((MethodInvoker)delegate
                            {
                                MessageBox.Show(form, "Installation failed: " + ex.Message, "SimpMusic Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
                                form.Close();
                            });
                        }
                        catch { }
                    }
                });
                worker.IsBackground = true;
                worker.Start();
            };

            Application.Run(form);
            return exitCode;
        }

        private static void RunInstall(string customDir, Action<string> onStatus, Action<int> onProgress)
        {
            if (onStatus != null) onStatus("Closing any running SimpMusic instance...");
            if (onProgress != null) onProgress(8);

            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (string.IsNullOrEmpty(localAppData))
            {
                localAppData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "AppData\\Local");
            }
            string primaryDir = Path.Combine(localAppData, "Programs\\SimpMusic-Jarvis");

            HashSet<string> targetDirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            targetDirs.Add(Path.GetFullPath(primaryDir));
            if (!string.IsNullOrWhiteSpace(customDir))
            {
                try { targetDirs.Add(Path.GetFullPath(customDir)); } catch { }
            }

            // Detect any running SimpMusic.exe path before terminating it so we update that copy too
            try
            {
                foreach (Process p in Process.GetProcessesByName("SimpMusic"))
                {
                    try
                    {
                        string procPath = p.MainModule.FileName;
                        if (!string.IsNullOrEmpty(procPath))
                        {
                            string dir = Path.GetDirectoryName(procPath);
                            if (!string.IsNullOrEmpty(dir) && File.Exists(Path.Combine(dir, "app\\SimpMusic.cfg")))
                            {
                                targetDirs.Add(Path.GetFullPath(dir));
                            }
                        }
                    }
                    catch { }
                }
            }
            catch { }

            // Also check if %LOCALAPPDATA%\Programs\SimpMusic exists from an older install
            string legacyDir = Path.Combine(localAppData, "Programs\\SimpMusic");
            if (Directory.Exists(legacyDir))
            {
                targetDirs.Add(Path.GetFullPath(legacyDir));
            }

            // Stop all running SimpMusic processes
            KillRunningSimpMusic();

            if (onStatus != null) onStatus("Removing legacy MSIX package if present...");
            if (onProgress != null) onProgress(15);
            RemoveOldMsixIfPresent();

            string selfExe = Application.ExecutablePath;
            using (FileStream fs = new FileStream(selfExe, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                if (fs.Length < 24) throw new InvalidDataException("Installer payload missing.");
                fs.Position = fs.Length - 16;
                byte[] footer = new byte[16];
                int read = fs.Read(footer, 0, 16);
                if (read != 16) throw new InvalidDataException("Could not read installer footer.");
                for (int i = 0; i < 8; i++)
                {
                    if (footer[8 + i] != Magic[i])
                    {
                        throw new InvalidDataException("Installer payload signature mismatch.");
                    }
                }
                long payloadOffset = BitConverter.ToInt64(footer, 0);
                long payloadLength = (fs.Length - 16) - payloadOffset;
                if (payloadOffset <= 0 || payloadLength <= 0)
                {
                    throw new InvalidDataException("Invalid payload offset.");
                }

                List<string> dirs = new List<string>(targetDirs);
                for (int dIdx = 0; dIdx < dirs.Count; dIdx++)
                {
                    string installDir = dirs[dIdx];
                    try
                    {
                        Directory.CreateDirectory(installDir);
                        CleanOldJars(installDir);
                    }
                    catch
                    {
                        if (!string.Equals(installDir, primaryDir, StringComparison.OrdinalIgnoreCase)) continue;
                        throw;
                    }

                    SubStream sub = new SubStream(fs, payloadOffset, payloadLength);
                    using (ZipArchive archive = new ZipArchive(sub, ZipArchiveMode.Read, true))
                    {
                        int total = Math.Max(1, archive.Entries.Count);
                        int current = 0;
                        foreach (ZipArchiveEntry entry in archive.Entries)
                        {
                            current++;
                            if (dIdx == 0 && (current % 25 == 0 || current == total))
                            {
                                int pct = 20 + (int)((current * 70L) / total);
                                if (onProgress != null) onProgress(pct);
                                if (onStatus != null) onStatus("Installing files (" + current + "/" + total + ")...");
                            }

                            string relPath = entry.FullName.Replace('/', '\\').TrimStart('\\');
                            if (string.IsNullOrEmpty(relPath)) continue;
                            string outPath = Path.Combine(installDir, relPath);
                            if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\") || (string.IsNullOrEmpty(entry.Name) && entry.Length == 0))
                            {
                                Directory.CreateDirectory(outPath);
                                continue;
                            }
                            string parent = Path.GetDirectoryName(outPath);
                            if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);

                            ExtractEntryWithRetry(entry, outPath);
                        }
                    }
                }
            }

            if (onStatus != null) onStatus("Updating shortcuts & registry...");
            if (onProgress != null) onProgress(94);
            string primaryExe = Path.Combine(primaryDir, "SimpMusic.exe");
            UpdateShortcutsAndRegistry(primaryDir, primaryExe);

            if (onStatus != null) onStatus("Launching SimpMusic Hey Jarvis v" + AppVersion + "...");
            if (onProgress != null) onProgress(100);

            if (File.Exists(primaryExe))
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo();
                    psi.FileName = "cmd.exe";
                    psi.Arguments = "/c start \"\" \"" + primaryExe + "\"";
                    psi.WorkingDirectory = primaryDir;
                    psi.CreateNoWindow = true;
                    psi.UseShellExecute = false;
                    Process.Start(psi);
                }
                catch
                {
                    try
                    {
                        ProcessStartInfo psi = new ProcessStartInfo(primaryExe);
                        psi.WorkingDirectory = primaryDir;
                        psi.UseShellExecute = true;
                        Process.Start(psi);
                    }
                    catch { }
                }
            }
        }

        private static void KillRunningSimpMusic()
        {
            for (int attempt = 0; attempt < 15; attempt++)
            {
                Process[] procs = Process.GetProcessesByName("SimpMusic");
                if (procs == null || procs.Length == 0) break;
                foreach (Process p in procs)
                {
                    try { p.Kill(); p.WaitForExit(1500); } catch { }
                }
                try
                {
                    ProcessStartInfo tk = new ProcessStartInfo("taskkill.exe", "/F /IM SimpMusic.exe /T");
                    tk.CreateNoWindow = true;
                    tk.UseShellExecute = false;
                    using (Process proc = Process.Start(tk)) { if (proc != null) proc.WaitForExit(2000); }
                }
                catch { }
                Thread.Sleep(300);
            }
            Thread.Sleep(400);
        }

        private static void CleanOldJars(string installDir)
        {
            string appSubDir = Path.Combine(installDir, "app");
            if (!Directory.Exists(appSubDir)) return;
            foreach (string file in Directory.GetFiles(appSubDir, "*.jar"))
            {
                try { File.Delete(file); } catch { }
            }
        }

        private static void ExtractEntryWithRetry(ZipArchiveEntry entry, string outPath)
        {
            Exception lastEx = null;
            for (int retry = 0; retry < 10; retry++)
            {
                try
                {
                    if (File.Exists(outPath))
                    {
                        File.SetAttributes(outPath, FileAttributes.Normal);
                    }
                    using (Stream inStream = entry.Open())
                    using (FileStream outStream = new FileStream(outPath, FileMode.Create, FileAccess.Write, FileShare.None))
                    {
                        inStream.CopyTo(outStream);
                    }
                    return;
                }
                catch (Exception ex)
                {
                    lastEx = ex;
                    KillRunningSimpMusic();
                    Thread.Sleep(250);
                }
            }
            if (lastEx != null) throw lastEx;
        }

        private static void RemoveOldMsixIfPresent()
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo(
                    "powershell.exe",
                    "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -Command \"Get-AppxPackage *simpmusic* -ErrorAction SilentlyContinue | Remove-AppxPackage -ErrorAction SilentlyContinue\""
                );
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                using (Process p = Process.Start(psi))
                {
                    if (p != null) p.WaitForExit(4000);
                }
            }
            catch { }
        }

        private static void UpdateShortcutsAndRegistry(string installDir, string exePath)
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\SimpMusic-Jarvis"))
                {
                    if (key != null)
                    {
                        key.SetValue("DisplayName", "SimpMusic Hey Jarvis");
                        key.SetValue("DisplayVersion", AppVersion);
                        key.SetValue("Publisher", "SimpMusic Hey Jarvis");
                        key.SetValue("InstallLocation", installDir);
                        key.SetValue("DisplayIcon", exePath);
                        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                    }
                }
            }
            catch { }

            try
            {
                string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                string startMenu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), @"Microsoft\Windows\Start Menu\Programs");
                string ps = string.Format(
                    "`$s = New-Object -ComObject WScript.Shell; " +
                    "foreach (`$p in @('{0}', '{1}')) {{ " +
                    "  `$lnk = `$s.CreateShortcut(`$p); " +
                    "  `$lnk.TargetPath = '{2}'; " +
                    "  `$lnk.WorkingDirectory = '{3}'; " +
                    "  `$lnk.IconLocation = '{2},0'; " +
                    "  `$lnk.Description = 'SimpMusic Hey Jarvis v{4}'; " +
                    "  `$lnk.Save(); " +
                    "}}",
                    Path.Combine(desktop, "SimpMusic Jarvis.lnk").Replace("'", "''"),
                    Path.Combine(startMenu, "SimpMusic Jarvis.lnk").Replace("'", "''"),
                    exePath.Replace("'", "''"),
                    installDir.Replace("'", "''"),
                    AppVersion
                );
                ProcessStartInfo psi = new ProcessStartInfo("powershell.exe", "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -Command \"" + ps + "\"");
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                using (Process p = Process.Start(psi))
                {
                    if (p != null) p.WaitForExit(4000);
                }
            }
            catch { }
        }
    }
}
"@

[System.IO.File]::WriteAllText($csPath, $csCode, [System.Text.Encoding]::UTF8)

$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$IconPath" /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll "/out:$stubExe" $csPath
if ($LASTEXITCODE -ne 0 -or !(Test-Path $stubExe)) {
    throw "csc.exe failed to compile installer stub"
}

Write-Host "Creating payload zip from $AppDir..."
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $payloadZip) { Remove-Item $payloadZip -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($AppDir, $payloadZip, [System.IO.Compression.CompressionLevel]::Optimal, $false)

Write-Host "Combining stub.exe + payload.zip into $OutputExe..."
$stubBytes = [System.IO.File]::ReadAllBytes($stubExe)
$payloadOffset = [int64]$stubBytes.LongLength

$outDir = Split-Path -Parent $OutputExe
if ($outDir -and !(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
if (Test-Path $OutputExe) { Remove-Item $OutputExe -Force }

$outStream = [System.IO.File]::Open($OutputExe, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
try {
    $outStream.Write($stubBytes, 0, $stubBytes.Length)
    $inStream = [System.IO.File]::OpenRead($payloadZip)
    try {
        $inStream.CopyTo($outStream)
    } finally {
        $inStream.Close()
    }
    $offsetBytes = [System.BitConverter]::GetBytes($payloadOffset)
    $magicBytes = [System.Text.Encoding]::ASCII.GetBytes("SMJARVIS")
    $outStream.Write($offsetBytes, 0, 8)
    $outStream.Write($magicBytes, 0, 8)
} finally {
    $outStream.Close()
}

Remove-Item $workDir -Recurse -Force -ErrorAction SilentlyContinue
$finalItem = Get-Item $OutputExe
Write-Host "Successfully built standalone EXE installer: $($finalItem.FullName) ($([Math]::Round($finalItem.Length / 1MB, 2)) MB)"
