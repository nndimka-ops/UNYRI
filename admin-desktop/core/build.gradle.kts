plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("net.java.dev.jna:jna-platform:5.6.0")
    testImplementation(kotlin("test-junit"))
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
