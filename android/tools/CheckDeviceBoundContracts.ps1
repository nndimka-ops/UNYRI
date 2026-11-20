param(
    [string]$CacheRoot = (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'),
    [string]$AndroidJar = 'C:/Android/Sdk/platforms/android-36/android.jar'
)

# Offline bounded-heap compiler + JVM contracts. Does not replace a full Android build or hardware check.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = Join-Path $repoRoot 'android/app/build/device-bound-validation/classes'
function CachedJars([string[]]$Groups) {
    @($Groups | ForEach-Object {
        $cached = @(Get-ChildItem -LiteralPath (Join-Path $CacheRoot $_) -Recurse -Filter '*.jar')
        if ($cached.Count -eq 0) { throw "Missing offline cached jars: $_" }
        $cached.FullName
    })
}
$compiler = CachedJars @(
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.0',
    'org.jetbrains.kotlin/kotlin-stdlib/2.1.0', 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.0',
    'org.jetbrains.kotlin/kotlin-reflect', 'org.jetbrains.intellij.deps/trove4j',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1', 'org.jetbrains/annotations'
)
$libraries = CachedJars @(
    'org.jetbrains.kotlin/kotlin-stdlib/2.1.0',
    'org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.1',
    'org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.1',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1',
    'com.squareup.okhttp3/okhttp/4.12.0', 'com.squareup.okio/okio-jvm',
    'junit/junit/4.13.2', 'org.hamcrest/hamcrest-core', 'org.jetbrains/annotations'
)
if (!(Test-Path -LiteralPath $AndroidJar)) { throw 'Offline Android SDK jar is required for actual Keystore API compilation.' }
$plugin = @(CachedJars @('org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.0'))[0]
$mainRoot = Join-Path $repoRoot 'android/app/src/main/java/com/messenger/app'
$testRoot = Join-Path $repoRoot 'android/app/src/test/java/com/messenger/app'
$sources = @(
    'data/auth/DeviceProofBuilder.kt', 'data/auth/DeviceSessionBinding.kt', 'data/auth/DeviceBoundRequests.kt', 'data/auth/DeviceAccessExpiry.kt',
    'data/auth/AuthFailurePolicy.kt', 'data/auth/DeviceBindingAvailability.kt', 'data/api/AuthDto.kt', 'data/api/AuthApiException.kt',
    'data/api/ApiErrors.kt', 'data/api/HostileWire.kt', 'data/api/KeysDto.kt', 'data/api/ServerInputLimits.kt', 'data/api/CancellableApiCall.kt'
) | ForEach-Object { Join-Path $mainRoot $_ }
$suites = @('data/auth/DeviceProofBuilderTest', 'data/auth/DeviceAccessExpiryTest', 'data/auth/DeviceBindingAvailabilityTest', 'data/auth/AuthRefreshPolicyTest')
$tests = $suites | ForEach-Object { Join-Path $testRoot "${_}.kt" }
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
& java -Xmx384m -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath ((@($AndroidJar) + $libraries) -join ';') `
    "-Xplugin=$plugin" -d $outputRoot @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Offline device-bound Kotlin compilation failed.' }
$classes = $suites | ForEach-Object { 'com.messenger.app.' + $_.Replace('/', '.') }
& java -Xmx192m -cp ((@($outputRoot, $AndroidJar) + $libraries) -join ';') org.junit.runner.JUnitCore @classes
if ($LASTEXITCODE -ne 0) { throw 'Offline JVM device-bound contracts failed.' }
