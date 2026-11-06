import java.util.Properties
import java.net.URI
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services) apply false
}

// Firebase is OPTIONAL at build time: the google-services plugin is only
// applied when a real `google-services.json` exists (Этап K - the Firebase
// project/credentials are not part of this repo). Without it the app builds
// and runs normally; FCM stays dormant (FirebaseApp is not initialized, see
// MessengerApp.onCreate).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Dev server host: 10.0.2.2 works only from the emulator. For a real device on the
// same LAN, set dev.server.host=<your PC's LAN IP> in local.properties (gitignored).
val devServerHost: String = run {
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { localProps.load(it) }
    }
    localProps.getProperty("dev.server.host", "10.0.2.2")
}
val forcedWebRtcRelay = providers.gradleProperty("forcedWebrtcRelay")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

// Public releases use the shared production origin. Private QA/LAN builds may
// still supply -PreleaseApiBaseUrl explicitly without changing user selection.
val alphaLan = providers.gradleProperty("alphaLan").map { it.toBoolean() }.getOrElse(false)
val serverOriginConfig = JsonSlurper().parse(rootProject.file("../shared/server-origin.json")) as Map<*, *>
val publicDefaultOrigin = serverOriginConfig["publicDefaultOrigin"] as String
require(serverOriginConfig["version"] == 1 && URI(publicDefaultOrigin).scheme == "https") { "Invalid shared public server origin" }
val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl").getOrElse(if (alphaLan) "http://$devServerHost:8080" else publicDefaultOrigin)
val releaseEndpoint = URI(releaseApiBaseUrl)
require((releaseEndpoint.scheme == "https" || (alphaLan && releaseEndpoint.scheme == "http")) && !releaseEndpoint.host.isNullOrBlank() &&
    releaseEndpoint.rawUserInfo == null && releaseEndpoint.rawQuery == null && releaseEndpoint.rawFragment == null &&
    releaseEndpoint.path.orEmpty() in listOf("", "/")) { "releaseApiBaseUrl must be an HTTPS origin" }
val releaseOrigin = releaseApiBaseUrl.trimEnd('/')

// Explicit APK-only ABI selection. App Bundles must keep all supported ABIs.
val apkAbis = providers.gradleProperty("apkAbis").orNull?.split(',')?.map(String::trim).orEmpty()
val supportedApkAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
require(apkAbis.all { it in supportedApkAbis } && apkAbis.distinct().size == apkAbis.size) {
    "apkAbis must contain unique supported ABIs: $supportedApkAbis"
}

android {
    namespace = "com.messenger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.messenger.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2026110602
        versionName = "0.3.17"

        // Instrumentation (androidTest) runner for the real-SQLite/Room tests
        // (M.1 verification closure - FTS migration/triggers/DAO).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Stage 1: local dev server reachable from the Android emulator or a LAN device.
        buildConfigField("String", "WS_URL", "\"ws://$devServerHost:8080/ws\"")
        buildConfigField("String", "API_BASE_URL", "\"http://$devServerHost:8080\"")
        // Keep the legacy Signal store anchor separate from the connection
        // default. Task-specific endpoint overrides must not select new keys.
        buildConfigField("String", "SIGNAL_NAMESPACE_ORIGIN", "\"http://$devServerHost:8080\"")
        buildConfigField("String", "PUBLIC_DEFAULT_API_ORIGIN", "\"$publicDefaultOrigin\"")
        buildConfigField("boolean", "FORCE_WEBRTC_RELAY", "false")
        buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        buildConfigField("boolean", "LAN_TLS_MIGRATION", alphaLan.toString())
    }

    buildTypes {
        debug {
            if (!alphaLan) {
                // Normal debug updates retain the public transport and Signal identity scope.
                buildConfigField("String", "API_BASE_URL", "\"$releaseOrigin\"")
                buildConfigField("String", "SIGNAL_NAMESPACE_ORIGIN", "\"$releaseOrigin\"")
                buildConfigField("String", "WS_URL", "\"${releaseOrigin.replaceFirst("https://", "wss://")}/ws\"")
                buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "false")
                buildConfigField("boolean", "LAN_TLS_MIGRATION", "false")
            }
            if (alphaLan) {
                buildConfigField("String", "API_BASE_URL", "\"$releaseOrigin\"")
                buildConfigField("String", "SIGNAL_NAMESPACE_ORIGIN", "\"$releaseOrigin\"")
                buildConfigField("String", "WS_URL", "\"${releaseOrigin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")}/ws\"")
            }
            buildConfigField("boolean", "FORCE_WEBRTC_RELAY", forcedWebRtcRelay.toString())
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseOrigin\"")
            buildConfigField("String", "SIGNAL_NAMESPACE_ORIGIN", "\"$releaseOrigin\"")
            buildConfigField("String", "WS_URL", "\"${releaseOrigin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")}/ws\"")
            buildConfigField("boolean", "FORCE_WEBRTC_RELAY", "false")
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", providers.gradleProperty("alphaLan").getOrElse("false"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    if (alphaLan) sourceSets.getByName("debug").res.setSrcDirs(listOf("src/alpha/res"))
    sourceSets.getByName("release").res.setSrcDirs(listOf(if (alphaLan) "src/alpha/res" else "src/release/res"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by org.signal:libsignal-android (AAR metadata check).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // No universal sibling is generated for a targeted APK build. `bundleRelease`
    // remains architecture-complete: splits do not filter the AAB native modules.
    splits {
        abi {
            isEnable = apkAbis.isNotEmpty()
            if (apkAbis.isNotEmpty()) {
                reset()
                include(*apkAbis.toTypedArray())
            }
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // libsignal-android's AAR also ships desktop-platform native
            // artifacts (macOS .dylib, Windows .dll, "testing" variants)
            // as loose root-level files - completely unused on Android,
            // pure APK bloat (Этап H.2 APK size audit).
            excludes += "libsignal_jni_testing_*.dylib"
            excludes += "libsignal_jni_*.dylib"
            excludes += "signal_jni_testing_*.dll"
            excludes += "signal_jni_*.dll"
        }
        // libsignal-android's AAR bundles a test-only native variant
        // (libsignal_jni_testing.so, ~78MB per ABI) that's never used by
        // production code paths - exclude it (Этап H.2 APK size audit).
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
    }

    testOptions {
        // No Robolectric in this project (see SignalCryptoTest.kt) - plain
        // JVM unit tests that incidentally touch a stubbed Android
        // framework method (e.g. `android.util.Log.w` from
        // MessageIngestionPipeline/GlobalSyncManager) would otherwise throw
        // "not mocked" RuntimeExceptions; this makes such calls return
        // harmless defaults instead;
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Stable CameraX, pinned together; direct chat capture owns only its own use cases.
    val cameraXVersion = "1.5.3"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.navigation.compose)
    implementation(libs.security.crypto)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Official Signal Protocol implementation - never roll our own crypto.
    // AGPL-3.0 licensed (see final report for the distribution implication).
    implementation(libs.libsignal.android)
    // Streaming AEAD for large attachments (Этап H.2) - already resolved
    // transitively via androidx.security:security-crypto at the same
    // version, so making it a direct dependency adds ~0 bytes to the APK.
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    // Real WebRTC (DTLS-SRTP media, ICE/STUN/TURN, standard codecs) for
    // Этап J 1:1 calls - Google's own org.webrtc Android artifact is
    // defunct (JCenter shut down); this Apache-2.0 pre-built mirror is the
    // actively-maintained replacement (see final report for size/ABI audit).
    implementation(libs.stream.webrtc.android)
    // Separate, prefixed WebRTC classes/JNI; keep the established 1:1 engine.
    implementation("io.livekit:livekit-android:2.28.2")
    // Track encryption metadata is part of LiveKit's public protobuf API.
    implementation("com.google.protobuf:protobuf-javalite:3.25.9")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // FCM push (Этап K) - messaging only, NO Analytics/Crashlytics/Firestore/
    // Auth to keep the APK overhead minimal. Resolved via the Firebase BoM.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")

    // Instrumentation (androidTest) - real SQLite/Room only, never mocked FTS.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
