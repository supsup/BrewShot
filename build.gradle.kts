// BrewShot — Java brews screenshots. Zero runtime dependencies: the library is
// pure JDK (java.net.http WebSocket + ImageIO), driving the locally installed
// Chrome over the DevTools Protocol. JUnit is test-scope only.
plugins {
    `java-library`
    application
}

group = "com.brewshot"
version = "0.9.0"

java {
    withSourcesJar()
}

// Build with the caller's JDK and target 21 bytecode. CI runs this build under
// both JDK 21 and 25, so "JDK 21+" is a runtime-tested promise rather than a
// --release-only claim hidden behind a JDK 25 toolchain.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.brewshot.Main"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.brewshot.Main")
    }
}

fun org.gradle.api.tasks.testing.Test.configureBrewShotTests() {
    useJUnitPlatform()
    // Input for QuickstartTeardownClaimTest: QUICKSTART.md is not a source file, so without
    // this Gradle holds the test task UP-TO-DATE after a doc-only edit and the guard never
    // runs — inert exactly when the prose it guards is being changed. Measured on the sibling
    // Sirentide guard: BUILD SUCCESSFUL in 252ms because the task did not execute.
    // `inputs.files` (plural) rather than `inputs.file(...).optional(true)`: optional does NOT
    // tolerate an absent file, it fails at CONFIGURATION time (Fixpoint, sirentide/868).
    inputs.files(layout.projectDirectory.file("QUICKSTART.md"))
        .withPropertyName("quickstartDoc")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // ImageIO/AWT tests must never initialize the macOS AppKit UI process.
    // Keep the test JVM explicit and deterministic on both desktop and CI hosts.
    systemProperty("java.awt.headless", "true")

    // Browser tests loud-skip when no local Chrome exists; keep CI honest.
    // A red CI that doesn't show WHY is only half-honest — surface full
    // exceptions (expected-vs-actual) so a failure names its own cause.
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    // CI-honesty guard: when BREWSHOT_REQUIRE_CHROME is set (CI sets it), NO test
    // may skip — the Chrome-driving suite must run or fail, never report
    // skipped==green. TestChrome.requireChromeOrLoudSkip turns absence into a
    // failure per-test under REQUIRE; this is the belt-and-suspenders that also
    // catches any future test that forgets the gate and skips on its own.
    val requireChrome = System.getenv("BREWSHOT_REQUIRE_CHROME")
        ?.let { it == "1" || it.equals("true", true) || it.equals("yes", true) } ?: false
    if (requireChrome) {
        failOnSkippedTests("BREWSHOT_REQUIRE_CHROME is set")
    }
}

fun org.gradle.api.tasks.testing.Test.failOnSkippedTests(reason: String) {
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null && result.skippedTestCount > 0) {
            throw GradleException(
                "$reason but ${result.skippedTestCount} test(s) were " +
                "SKIPPED — a required run must execute or fail every test, never skip " +
                "(green-that-tested-nothing guard)."
            )
        }
    }))
}

// Browser tests have historically lived beside pure-JDK/ImageIO tests, and a
// few classes intentionally contain both kinds. Derive method-level filters
// from the one fail-loud browser gate every Chrome test must invoke instead of
// maintaining a second, silently drifting hand-written list.
fun discoverChromeTests(): List<String> {
    val gate = Regex("TestChrome\\.requireChromeOrLoudSkip\\(")
    val testMethod = Regex(
        """(?s)@Test\s+(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:public\s+|protected\s+|private\s+)?void\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\([^)]*\)\s*(?:throws\s+[^\{]+)?\{"""
    )

    return fileTree("src/test/java") { include("**/*.java") }
        .files
        .sortedBy { it.path }
        .flatMap { source ->
            // This class tests the gate itself with injected true/false values;
            // it never launches a browser and belongs in the unit lane.
            if (source.name == "TestChromeTest.java") {
                return@flatMap emptyList()
            }

            val text = source.readText()
            val methods = testMethod.findAll(text).toList()
            gate.findAll(text).map { call ->
                val method = methods.lastOrNull { it.range.first < call.range.first }
                    ?: throw GradleException(
                        "${source.path}: Chrome gate is not inside a discoverable @Test method"
                    )
                val nextMethod = methods.firstOrNull { it.range.first > method.range.first }
                if (nextMethod != null && call.range.first > nextMethod.range.first) {
                    throw GradleException(
                        "${source.path}: Chrome gate could not be assigned to one @Test method"
                    )
                }
                "com.brewshot.${source.nameWithoutExtension}.${method.groupValues[1]}"
            }.toList()
        }
        .distinct()
        .also {
            if (it.isEmpty()) {
                throw GradleException("No Chrome-gated tests discovered; refusing a false unit lane")
            }
        }
}

val chromeTests = discoverChromeTests()

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    configureBrewShotTests()
}

val unitTest by tasks.registering(org.gradle.api.tasks.testing.Test::class) {
    group = "verification"
    description = "Runs every browser-free test under an explicitly headless JVM."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        chromeTests.forEach { excludeTestsMatching(it) }
    }
    // FORBID proves this lane cannot launch Chrome. REQUIRE turns every skip
    // into a failure, so a future browser test that escapes the source-derived
    // filter cannot disappear from both CI lanes as a green assumption skip.
    environment("BREWSHOT_FORBID_CHROME", "1")
    environment("BREWSHOT_REQUIRE_CHROME", "1")
    failOnSkippedTests("unitTest requires every browser-free test to execute")
}

val chromeTest by tasks.registering(org.gradle.api.tasks.testing.Test::class) {
    group = "verification"
    description = "Runs only tests that cross BrewShot's real-Chrome gate."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        chromeTests.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
    shouldRunAfter(unitTest)
}

tasks.register("verifyChromeTestCatalog") {
    group = "verification"
    description = "Prints the source-derived Chrome-test catalog used by unitTest/chromeTest."
    doLast {
        logger.lifecycle("Chrome-gated tests (${chromeTests.size}):")
        chromeTests.forEach { logger.lifecycle("  $it") }
    }
}

// Preserve the old CLI contract: `test` remains the aggregate lane and runs
// the union in one JVM; callers can select `unitTest` or `chromeTest` when they
// need an honest browser-free or browser-required gate.
tasks.test {
    description = "Runs the aggregate unit + Chrome test suite."
}

// Native binary: `./gradlew nativeImage` with a GraalVM JDK selected (or
// GRAALVM_HOME set). PNG/eval path is native-clean; GIF (ImageIO/AWT) is
// library-only until native-image AWT lands on macOS.
tasks.register<Exec>("nativeImage") {
    group = "distribution"
    description = "Build the brewshot native binary with GraalVM native-image."
    dependsOn(tasks.jar)
    val graalHome = System.getenv("GRAALVM_HOME")
    val nativeImageBin = if (graalHome != null) "$graalHome/bin/native-image" else "native-image"
    val jarFile = tasks.jar.get().archiveFile.get().asFile
    commandLine(
        nativeImageBin,
        "--no-fallback",
        "-o", layout.buildDirectory.file("brewshot").get().asFile.absolutePath,
        "-jar", jarFile.absolutePath,
    )
}
