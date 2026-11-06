param(
    [string]$DestinationDirectory = (Join-Path $PSScriptRoot 'lib'),
    [string]$PackagePath = ''
)
$ErrorActionPreference = 'Stop'
function Get-DependencySha256([string]$Path) {
    $hashStream = [System.IO.File]::OpenRead([System.IO.Path]::GetFullPath($Path))
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hasher.ComputeHash($hashStream)).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose(); $hashStream.Dispose() }
}
$expectedPackage = 'f091ffccab4d03993e660bace277659a79dee0972f54d7f1f4bd46d680966241'
$expectedDll = '4f96977e9c67334742c683410b3a361258219f0d3084a5e0bc10fba96cf23a0d'
$destination = [System.IO.Path]::GetFullPath($DestinationDirectory)
$target = Join-Path $destination 'BouncyCastle.Cryptography.dll'
if (Test-Path -LiteralPath $target) {
    if ((Get-DependencySha256 $target) -ne $expectedDll) {
        throw 'Existing Bouncy Castle DLL has an unexpected SHA-256. It was not replaced.'
    }
    return
}
[System.IO.Directory]::CreateDirectory($destination) | Out-Null
$ownedDownload = $null
$temporaryDll = Join-Path $destination ('unyri-dependency-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$archive = $null
try {
    if ([string]::IsNullOrWhiteSpace($PackagePath)) {
        $ownedDownload = Join-Path ([System.IO.Path]::GetTempPath()) ('unyri-bouncycastle-' + [Guid]::NewGuid().ToString('N') + '.nupkg')
        $downloadClock = [System.Diagnostics.Stopwatch]::StartNew()
        $request = [System.Net.HttpWebRequest]::Create('https://api.nuget.org/v3-flatcontainer/bouncycastle.cryptography/2.7.0/bouncycastle.cryptography.2.7.0.nupkg')
        $request.Timeout = 120000
        $request.ReadWriteTimeout = 30000
        $request.MaximumAutomaticRedirections = 5
        $response = $request.GetResponse()
        try {
            if ($response.ResponseUri.Scheme -ne 'https' -or $response.ContentLength -gt 64MB) { throw 'Unexpected dependency download response.' }
            $responseStream = $response.GetResponseStream()
            try {
                $downloadStream = [System.IO.File]::Open($ownedDownload, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                try {
                    $buffer = New-Object byte[] 131072
                    $downloaded = 0L
                    while ($true) {
                        $remainingMilliseconds = [int](120000L - $downloadClock.ElapsedMilliseconds)
                        if ($remainingMilliseconds -le 0) { $request.Abort(); throw 'Dependency download exceeded the total 120-second deadline.' }
                        $readTask = $responseStream.ReadAsync($buffer, 0, $buffer.Length)
                        if (-not $readTask.Wait($remainingMilliseconds)) {
                            $request.Abort()
                            throw 'Dependency download exceeded the total 120-second deadline.'
                        }
                        $readCount = $readTask.GetAwaiter().GetResult()
                        if ($readCount -eq 0) { break }
                        $downloaded += $readCount
                        if ($downloaded -gt 64MB) { throw 'Dependency download exceeds the size limit.' }
                        $downloadStream.Write($buffer, 0, $readCount)
                    }
                } finally { $downloadStream.Dispose() }
            } finally { $responseStream.Dispose() }
        } finally { $response.Dispose() }
        $PackagePath = $ownedDownload
    }
    if ((Get-DependencySha256 $PackagePath) -ne $expectedPackage) {
        throw 'Bouncy Castle package SHA-256 does not match the pinned official version 2.7.0.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead([System.IO.Path]::GetFullPath($PackagePath))
    $entry = $archive.GetEntry('lib/net461/BouncyCastle.Cryptography.dll')
    if ($null -eq $entry -or $entry.Length -ne 4885392) { throw 'Expected .NET Framework Bouncy Castle DLL not found.' }
    $entryStream = $entry.Open()
    try {
        $dllStream = [System.IO.File]::Open($temporaryDll, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $entryStream.CopyTo($dllStream) } finally { $dllStream.Dispose() }
    } finally { $entryStream.Dispose() }
    if ((Get-DependencySha256 $temporaryDll) -ne $expectedDll) {
        throw 'Extracted Bouncy Castle DLL SHA-256 does not match.'
    }
    # File.Move does not overwrite another file, including a concurrent restore.
    [System.IO.File]::Move($temporaryDll, $target)
} finally {
    if ($null -ne $archive) { $archive.Dispose() }
    if (Test-Path -LiteralPath $temporaryDll) { Remove-Item -LiteralPath $temporaryDll }
    if ($null -ne $ownedDownload -and (Test-Path -LiteralPath $ownedDownload)) { Remove-Item -LiteralPath $ownedDownload }
}
