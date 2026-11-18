plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// This policy is plain Java and has no Gradle/Groovy runtime dependencies.
tasks.withType<JavaCompile>().configureEach { classpath = files() }
