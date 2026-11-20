param([string]$Repository = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent))
$ErrorActionPreference = 'Stop'
$files = @('desktop/README.md','desktop/ADMIN_API_NOTES.md','desktop/UI_REDESIGN_SPEC.md','desktop/USER_CARD_SPEC.md','desktop/OPS_CHECKS.md','server/console/index.html','server/console/app.js','server/console/app.css','server/src/admin.rs','server/src/admin/signing.rs','server/src/admin/cursor.rs','server/src/admin_operations.rs','server/src/console.rs','server/src/server_management.rs','server/src/performance.rs','server/src/bin/coco-admin.rs','docs/USER_SAFETY_SPEC.md','docs/SELF_HOSTING.md','docs/DRAWER_A_SPEC.md','docs/DRAWER_C_SPEC.md')
$files += Get-ChildItem (Join-Path $Repository 'desktop/CocoAdminConsole') -Filter '*.cs' -File | ForEach-Object { 'desktop/CocoAdminConsole/' + $_.Name }
$files += Get-ChildItem (Join-Path $Repository 'desktop') -Filter 'Test-*.ps1' -File | ForEach-Object { 'desktop/' + $_.Name }
$files += Get-ChildItem (Join-Path $Repository 'desktop/tests') -Filter '*.cs' -File | ForEach-Object { 'desktop/tests/' + $_.Name }
$out = [Collections.Generic.List[string]]::new()
$out.Add('# Admin desktop source inventory')
$out.Add('')
$out.Add('Generated from an explicit source allowlist. All listed files were read in full by this extractor. This is a navigation/evidence index; ADMIN_DESKTOP_SPEC.md contains interpretation. References are one-based. Lines below preserve wire contracts and UI entry points, not secrets or runtime data.')
foreach ($relative in $files) {
    $path = Join-Path $Repository $relative
    if (!(Test-Path -LiteralPath $path)) { continue }
    $lines = [IO.File]::ReadAllLines($path)
    $out.Add(''); $out.Add('## ' + $relative)
    $out.Add(('Lines: {0}; SHA-256: {1}' -f $lines.Length,(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()))
    $out.Add('')
    $out.Add('```text')
    $inStruct = $false
    for ($i=0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        if ($relative -match '\.rs$' -and $line -match '^(pub )?struct ') { $inStruct = $true }
        $match = $line -match '^#{1,4} |\b(route\(|json!\(|jsonb_build_object|owner\(|Admin,|pub (async )?fn|fn (verify|payload)|assert|Check\(|Expect\(|Require\()|\b(private|internal|public) (static )?(async )?(Task[^ ]*|void|Control|Panel|bool|CommandResult) [A-Z]|^((async )?function |const (SECTIONS|sections|SESSION|PAGE))|(/admin/|"admin/|"auth/|"users/me)|Text = "|\.Controls.Add\(.*Button\(|AddPage\(|new Nav|\.Add\(.*"|COCO-ADMIN|x-coco-admin|12 hours|secret-tool'
        if ($match -or $inStruct) { $out.Add(('{0}:{1}: {2}' -f $relative,($i+1),$line.Trim())) }
        if ($inStruct -and $line -match '^}') { $inStruct = $false }
    }
    $out.Add('```')
}
$destination = Join-Path $Repository 'docs/ADMIN_DESKTOP_SOURCE_INVENTORY.md'
[IO.File]::WriteAllLines($destination,$out,[Text.UTF8Encoding]::new($false))
Write-Output ('Read {0} source files; wrote {1} evidence lines to {2}' -f $files.Count,$out.Count,$destination)
