plugins { kotlin("jvm"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin { jvmToolchain(17) }
dependencies { implementation(compose.desktop.common); api(compose.material3) }
