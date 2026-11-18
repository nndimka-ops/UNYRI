param(
    [string]$McPath = 'mc',
    [string]$Endpoint = 'http://127.0.0.1:59000',
    [ValidateNotNullOrEmpty()]
    [string]$Container = 'ut-minio'
)
# Dot-sourced into the caller's shell: keep its own error preference afterwards,
# or native stderr (cargo warnings) would abort the test command that follows.
$callerErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Stop'
$origin = [uri]$Endpoint
if (-not $origin.IsLoopback -or $origin.Scheme -ne 'http') {
    throw 'This fixture only provisions an existing loopback MinIO test service.'
}
$hostMc = Get-Command -Name $McPath -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
# An explicit -Container selects container mode even when host mc is installed.
$containerMode = $PSBoundParameters.ContainsKey('Container') -or $null -eq $hostMc
$mcEndpoint = $Endpoint
if ($containerMode) {
    $docker = Get-Command -Name docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $docker) {
        throw "No usable MinIO client: host '$McPath' was not selected or not found, and Docker is not on PATH. Install host mc or provide Docker with a running '$Container' container containing mc."
    }
    $dockerPath = $docker.Source
    $mcEndpoint = 'http://127.0.0.1:9000'
    $clientDescription = "Docker container '$Container'"
}
else {
    $hostMcPath = $hostMc.Source
    $clientDescription = "host mc '$hostMcPath'"
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$fixtureDirectory = Join-Path $projectRoot 'runtime-logs\test-storage'
New-Item -ItemType Directory -Force $fixtureDirectory | Out-Null
$configDirectory = Join-Path $fixtureDirectory 'mc'
if ($containerMode) { $configDirectory = '/tmp/unyri-testhealth-mc' }
function Invoke-TestMc {
    try {
        if ($containerMode) {
            & $dockerPath exec $Container mc --config-dir $configDirectory @args
        }
        else {
            & $hostMcPath --config-dir $configDirectory @args
        }
    }
    catch {
        throw "MinIO test setup failed using ${clientDescription}: mc $($args[0]) $($args[1]): $($_.Exception.Message)"
    }
    if ($LASTEXITCODE -ne 0) { throw "MinIO test setup failed using ${clientDescription}: mc $($args[0]) $($args[1]) exited $LASTEXITCODE. Check that this client/container is available." }
}
Invoke-TestMc --version | Out-Host
Write-Host "Using $clientDescription against $mcEndpoint (host test endpoint: $Endpoint)."
Invoke-TestMc alias set testhealth $mcEndpoint messenger_minio messenger_minio_secret
Invoke-TestMc mb --ignore-existing testhealth/messenger-attachments-test
$version = Invoke-TestMc --json version info testhealth/messenger-attachments-test | ConvertFrom-Json
if ($version.status -ne 'success' -or $null -eq $version.versioning -or $version.versioning.status) {
    throw 'Test bucket must never have enabled versioning (Enabled and Suspended are unsupported).'
}
$policy = (Get-Content (Join-Path $projectRoot 'server\storage-client-uploads-policy.template.json') -Raw).Replace('__COCO_BUCKET__', 'messenger-attachments-test')
$policyPath = Join-Path $fixtureDirectory 'upload-policy.json'
[IO.File]::WriteAllText($policyPath, $policy)
$mcPolicyPath = $policyPath
if ($containerMode) {
    $mcPolicyPath = '/tmp/unyri-testhealth-upload-policy.json'
    & $dockerPath cp $policyPath "${Container}:$mcPolicyPath"
    if ($LASTEXITCODE -ne 0) { throw "MinIO test setup failed: docker cp policy into '$Container' exited $LASTEXITCODE." }
}
Invoke-TestMc admin policy create testhealth unyri-test-upload-only $mcPolicyPath
Invoke-TestMc admin user add testhealth unyri_test_upload unyri_test_upload_secret
Invoke-TestMc admin policy attach testhealth unyri-test-upload-only --user unyri_test_upload
$env:S3_ENDPOINT_URL = $Endpoint
$env:S3_UPLOAD_ACCESS_KEY_ID = 'unyri_test_upload'
$env:S3_UPLOAD_SECRET_ACCESS_KEY = 'unyri_test_upload_secret'
# users_test enforces its own 30-second ingress. Other upload suites must use a
# bounded test ingress before declaring S3_MAX_UPLOAD_DURATION_SECONDS.
$ErrorActionPreference = $callerErrorActionPreference
Write-Host 'READY: unversioned test bucket and separate PutObject-only signer configured; users_test verifies bucket versioning before admission.'
