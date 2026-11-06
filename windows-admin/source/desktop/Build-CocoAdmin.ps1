param(
    [string]$Output = (Join-Path $PSScriptRoot 'UNYRI-AdminConsole.exe')
)
$ErrorActionPreference = 'Stop'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw 'csc.exe (.NET Framework 4.8) not found.' }
$sourceDir = Join-Path $PSScriptRoot 'CocoAdminConsole'
$sources = Get-ChildItem -LiteralPath $sourceDir -Filter '*.cs' -File | Sort-Object Name | ForEach-Object { $_.FullName }
if (-not $sources) { throw 'No C# sources found.' }
$manifest = Join-Path $PSScriptRoot 'CocoAdminConsole.exe.manifest'
if (-not (Test-Path -LiteralPath $manifest)) { throw 'Application DPI manifest not found.' }
$framework = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$references = @(
    'System.dll',
    'System.Core.dll',
    'System.Drawing.dll',
    'System.Windows.Forms.dll',
    'System.Net.Http.dll',
    'System.Security.dll',
    'System.Web.Extensions.dll',
    'System.Xml.dll'
) | ForEach-Object { Join-Path $framework $_ }
$libDir = Join-Path $PSScriptRoot 'lib'
& (Join-Path $PSScriptRoot 'Restore-Dependencies.ps1') -DestinationDirectory $libDir
$references += @(
    (Join-Path $libDir 'BouncyCastle.Cryptography.dll')
)
$args = @('/nologo', '/target:winexe', '/platform:x64', '/codepage:65001', '/optimize+',
    ('/win32manifest:' + $manifest), ('/win32icon:' + (Join-Path $PSScriptRoot 'assets/unyri.ico')), ('/out:' + $Output)) + ($references | ForEach-Object { '/reference:' + $_ }) + $sources
& $csc @args
if ($LASTEXITCODE -ne 0) { throw "C# build failed ($LASTEXITCODE)." }
$outDir = Split-Path -Parent $Output
foreach ($name in 'BouncyCastle.Cryptography.dll') {
    Copy-Item -LiteralPath (Join-Path $libDir $name) -Destination (Join-Path $outDir $name) -Force
}
$configSource = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'CocoAdminConsole.exe.config'))
$configDestination = [System.IO.Path]::GetFullPath($Output + '.config')
if (-not [string]::Equals($configSource, $configDestination, [System.StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $configSource -Destination $configDestination -Force
}
$file = Get-Item -LiteralPath $Output
$artifactStream = [System.IO.File]::OpenRead($file.FullName)
$artifactHasher = [System.Security.Cryptography.SHA256]::Create()
try { $artifactSha256 = [BitConverter]::ToString($artifactHasher.ComputeHash($artifactStream)).Replace('-', '') }
finally { $artifactHasher.Dispose(); $artifactStream.Dispose() }
[PSCustomObject]@{
    Artifact = $file.FullName
    MiB = [math]::Round($file.Length / 1MB, 2)
    SHA256 = $artifactSha256
}
