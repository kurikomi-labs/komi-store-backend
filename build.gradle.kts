import java.time.Duration

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("io.ktor.plugin") version "3.1.2"
    id("com.github.jk1.dependency-license-report") version "2.9"
}

group = "zed.rainxch.githubstore"
version = "0.1.0"

application {
    mainClass.set("zed.rainxch.githubstore.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.2"
val exposedVersion = "0.60.0"
val koinVersion = "4.0.4"
val logbackVersion = "1.5.18"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auto-head-response:$ktorVersion")

    // Ktor client (for Meilisearch)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.3.0")


    // DI
    implementation("io.insert-koin:koin-ktor:$koinVersion")
    implementation("io.insert-koin:koin-logger-slf4j:$koinVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Error tracking
    implementation("io.sentry:sentry:8.13.2")
    implementation("io.sentry:sentry-logback:8.13.2")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // Throwaway Postgres for DB-integration tests — executes the real pool /
    // sort SQL strings, the layer unit tests can't reach. Skips cleanly when
    // no Docker daemon is available (local runs without Docker still pass).
    testImplementation("org.testcontainers:postgresql:1.20.4")
}

ktor {
    fatJar {
        archiveFileName.set("github-store-backend.jar")
    }
}

// Generate THIRD-PARTY-LICENSES.md from the resolved dependency graph. Run
// `./gradlew generateLicenseReport` and copy the Markdown report onto the
// committed file; the manual file remains the source of truth for legal review.
licenseReport {
    outputDir = layout.buildDirectory.dir("license-reports").get().asFile.absolutePath
    renderers = arrayOf(
        com.github.jk1.license.render.InventoryMarkdownReportRenderer("THIRD-PARTY-LICENSES-generated.md", "github-store-backend"),
    )
}

// Write the project version to a resource file so the app can surface it at
// runtime (/v1/health, Sentry release tag, dashboard header). Single source
// of truth — bump `version` at the top of this file, rebuild, everything
// picks it up.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/resources/buildinfo")
    val v = project.version.toString()
    outputs.dir(outputDir)
    inputs.property("version", v)
    doLast {
        val file = outputDir.get().asFile.resolve("buildinfo.properties")
        file.parentFile.mkdirs()
        file.writeText("version=$v\n")
    }
}

sourceSets["main"].resources.srcDir(generateBuildInfo)

kotlin {
    jvmToolchain(21)
}

// Hard wall on test runtime so a hung HttpClient / dangling selector
// thread can't park CI (or a laptop run) for 45 minutes. Per-task
// timeout (Gradle 6.1+) — fires SIGKILL on the test JVM when exceeded.
// Adjust upward only with a written reason.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    timeout.set(Duration.ofMinutes(5))
    // One JVM per test class. Slower than the default shared-JVM mode but
    // bulletproof against leaked non-daemon threads (Ktor CIO selectors,
    // Postgres pool reapers, kotlinx.coroutines schedulers) — gradle ends
    // the worker process when the class finishes regardless of what's
    // still alive, so the task wall-clock can't drift past 5 min waiting
    // on a leaked thread.
    setForkEvery(1)
    // Print which test is running so a hang shows up in CI logs as the
    // last started-but-never-finished line, instead of a 5-minute silence.
    testLogging {
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Pre-PR validator for announcement JSON drafts. Authors / translators run
// `./gradlew validateAnnouncements` before opening a PR; CI runs the same
// task. Exit non-zero on any malformed file or duplicate id.
tasks.register<JavaExec>("validateAnnouncements") {
    group = "verification"
    description = "Validate src/main/resources/announcements/*.json against the schema."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("zed.rainxch.githubstore.announcements.AnnouncementsCliKt")
    // Forward `-Pdir=path` to the CLI as `--dir path`. Default (no override)
    // points at the in-tree resource directory.
    val customDir = (project.findProperty("dir") as String?)
    if (customDir != null) args("--dir", customDir)
}
