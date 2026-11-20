param([string]$CacheRoot = (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'))

# Offline, bounded JVM contracts. This is not an Android/Compose/Room build.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = Join-Path $repoRoot '.b033-validation/android-classes'
function CachedJars([string[]]$Groups) {
    @($Groups | ForEach-Object {
        $cached = @(Get-ChildItem -LiteralPath (Join-Path $CacheRoot $_) -Recurse -Filter '*.jar')
        if ($cached.Count -eq 0) { throw "Missing offline cached jars: $_" }
        $cached.FullName
    })
}
$compiler = CachedJars @(
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.0',
    'org.jetbrains.kotlin/kotlin-stdlib/2.1.0',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.1.0',
    'org.jetbrains.kotlin/kotlin-reflect',
    'org.jetbrains.intellij.deps/trove4j',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1',
    'org.jetbrains/annotations'
)
$libraries = CachedJars @(
    'org.jetbrains.kotlin/kotlin-stdlib/2.1.0',
    'org.jetbrains.kotlin/kotlin-test/2.1.0',
    'org.jetbrains.kotlin/kotlin-test-junit/2.1.0',
    'org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.1',
    'org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.1',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1',
    'org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm/1.8.1',
    'junit/junit/4.13.2',
    'org.hamcrest/hamcrest-core',
    'org.jetbrains/annotations'
)
$plugin = @(CachedJars @('org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.0'))[0]
$mainRoot = Join-Path $repoRoot 'android/app/src/main/java/com/messenger/app'
$testRoot = Join-Path $repoRoot 'android/app/src/test/java/com/messenger/app'
$sources = @(
    'data/identity/IdentityPolicy.kt', 'data/identity/PeerIdentityPolicy.kt',
    'data/api/UsersDto.kt', 'data/api/ChatsDto.kt', 'data/api/CallsDto.kt',
    'data/api/ContactsDto.kt', 'data/api/BlockedUsersDto.kt', 'data/api/KeysDto.kt',
    'data/api/HostileWire.kt', 'data/api/AuthApiException.kt',
    'domain/model/DisplayNameSanitizer.kt', 'domain/model/DisplayNamePolicy.kt',
    'domain/model/Unicode17.kt', 'domain/model/Profile.kt',
    'domain/model/SearchHistory.kt', 'domain/model/MessageSearchResult.kt',
    'conference/ConferencePeerDtos.kt', 'data/call/CallPeerIdentity.kt',
    'data/profile/ContactsStore.kt', 'data/profile/ContactsOrdering.kt',
    'data/profile/DirectoryCache.kt', 'ui/search/InlineSearchModel.kt'
) | ForEach-Object { Join-Path $mainRoot $_ }
$suites = @(
    'data/identity/PeerIdentityPolicyTest', 'data/identity/PeerIdentityDtoTest',
    'conference/ConferencePeerIdentityDtoTest', 'data/call/CallPeerIdentityTest',
    'domain/model/DisplayNamePolicyTest', 'data/profile/ContactsContractsTest',
    'data/profile/DirectoryCacheTest', 'ui/search/InlineSearchContractsTest'
)
$tests = $suites | ForEach-Object { Join-Path $testRoot "${_}.kt" }
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
& java -Xmx384m -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath ($libraries -join ';') `
    "-Xplugin=$plugin" -d $outputRoot @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Offline Kotlin contract compilation failed.' }
$classpath = @($outputRoot, (Join-Path $repoRoot 'shared'),
    (Join-Path $repoRoot 'android/app/src/main/resources')) + $libraries
$classes = $suites | ForEach-Object { 'com.messenger.app.' + $_.Replace('/', '.') }
Push-Location (Join-Path $repoRoot 'android/app')
try {
    & java -Xmx192m -cp ($classpath -join ';') org.junit.runner.JUnitCore @classes
    if ($LASTEXITCODE -ne 0) { throw 'Offline JVM peer identity contracts failed.' }
} finally { Pop-Location }
