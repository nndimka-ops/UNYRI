param([ValidateSet('erase-backups','retire')][string]$Action)
. (Join-Path $PSScriptRoot 'common.ps1')
Test-SelfhostDocker
if (-not (Test-Path -LiteralPath (Join-Path $SelfhostRoot 'desktop-install.marker'))) { throw 'Not a desktop installation.' }
if ($Action -eq 'erase-backups') {
    $target=[IO.Path]::GetFullPath((Join-Path $SelfhostRoot 'selfhost-backups'))
    if([IO.Path]::GetDirectoryName($target) -ne $SelfhostRoot) { throw 'Invalid path.' }
    if(Test-Path -LiteralPath $target) {
        if((Get-Item -LiteralPath $target).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked backups refused.' }
        if(Get-ChildItem -LiteralPath $target -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Linked backup entries refused.' }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
} elseif($Action -eq 'retire') {
    foreach($name in @('.env','.selfhost-from-source','desktop-install.marker','desktop-local-ready')) {
        $target=[IO.Path]::GetFullPath((Join-Path $SelfhostRoot $name))
        if([IO.Path]::GetDirectoryName($target) -ne $SelfhostRoot) { throw 'Invalid path.' }
        if(Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
    }
}
