import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins { kotlin("jvm"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core")); implementation(project(":design-system"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.google.zxing:core:3.4.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
compose.desktop {
    application {
        mainClass = "org.unyri.admin.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UNYRI-Admin"
            packageVersion = project.version.toString()
            description = "UNYRI administration for Windows and Linux"
            vendor = "UNYRI"
            modules("java.net.http", "java.desktop", "java.naming", "jdk.crypto.ec", "jdk.unsupported", "jdk.localedata")
            windows {
                iconFile.set(project.file("src/main/resources/unyri.ico"))
                upgradeUuid = "cd46d0ea-a8a5-458e-b197-71e3c7c53370"
                menuGroup = "UNYRI"; shortcut = true
                // Owner 18 Nov: "even my grandmother" - installs for the current user, no administrator prompt.
                perUserInstall = true
            }
            linux { iconFile.set(project.file("src/main/resources/unyri.png")); packageName = "unyri-admin" }
        }
    }
}
