param([ValidateSet('test','createDistributable','packageDistributionForCurrentOS','run','checksums','packageChecksums')][string]$Task='test')
$ErrorActionPreference='Stop'
$free=[IO.DriveInfo]::new('C:\').AvailableFreeSpace
if ($free -lt 2GB) { Write-Output "SKIPPED $Task : C: has $free bytes free, below 2 GiB."; exit 2 }
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle'
$env:JAVA_TOOL_OPTIONS="-Duser.home=$env:USERPROFILE"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if ($Task -eq 'checksums') { & .\gradlew.bat --no-daemon --max-workers=2 --write-verification-metadata sha256 test }
    elseif ($Task -eq 'packageChecksums') { & .\gradlew.bat --no-daemon --max-workers=2 --write-verification-metadata sha256 test createDistributable }
    elseif ($Task -eq 'run') { & .\gradlew.bat --no-daemon --max-workers=2 :app:run }
    else { & .\gradlew.bat --no-daemon --max-workers=2 $Task }
    exit $LASTEXITCODE
} finally { Pop-Location }
