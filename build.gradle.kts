plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.puregoldbe.ibms"
version = "0.0.1"

application {
    mainClass = "com.puregoldbe.ibms.MainKt"
}

kotlin {
    jvmToolchain(25)
}

// Flat layout carried over from Amper: sources in src/, tests in test/, resources in resources/.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        kotlin.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    // --- Ktor server ---
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.status.pages)
    // --- Data access ---
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    // --- Shared domain ---
    implementation(libs.kotlinx.datetime)
    // --- Auth ---
    implementation(libs.bcrypt)
    // --- Excel export ---
    implementation(libs.poi.ooxml)
    // --- Logging / metrics ---
    implementation(libs.logback.classic)
    implementation(libs.micrometer.registryPrometheus)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
}

ktor {
    fatJar {
        archiveFileName = "ibms-backend-all.jar"
    }
}

// Ktor's buildFatJar runs Shadow with duplicatesStrategy = EXCLUDE, which drops a duplicate
// META-INF/services file *before* the merge transformer ever sees it — so mergeServiceFiles()
// alone is a no-op. That let flyway-database-postgresql's 3-entry
// org.flywaydb.core.extensibility.Plugin file replace flyway-core's 37, and Flyway NPE'd on
// startup. INCLUDE + mergeServiceFiles() together are what actually merge them. No test covers
// this — specs run off the classpath, where the jars are still separate.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

// ---------------------------------------------------------------------------
// Local dev: auto-load .env into ./gradlew run
// ---------------------------------------------------------------------------
// Parses a standard .env file (KEY=VALUE per line, # comments, blank lines)
// and injects every entry into the run task's process environment. This lets
// developers keep secrets / per-machine config out of version control while
// still running `./gradlew run` without manual `export` calls.
// IDE "Run Configurations" that delegate to Gradle inherit these automatically.
// ---------------------------------------------------------------------------
fun loadDotEnv(): Map<String, String> {
    val envFile = file(".env")
    if (!envFile.exists()) return emptyMap()
    return envFile.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            else null
        }.toMap()
}

tasks.named<JavaExec>("run") {
    environment(loadDotEnv())
    // Override the Ktor port from application.yaml (8080) so the local JVM
    // doesn't collide with the Docker-compose app container.
    args("-port=8082")
}
