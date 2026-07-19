// BrewShot — Java brews screenshots. Zero runtime dependencies: the library is
// pure JDK (java.net.http WebSocket + ImageIO), driving the locally installed
// Chrome over the DevTools Protocol. JUnit is test-scope only.
plugins {
    `java-library`
    application
}

group = "com.brewshot"
version = "0.7.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

// Build WITH 25, target 21 bytecode — the README's "JDK 21+" is a tested
// promise, not an aspiration.
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

tasks.test {
    useJUnitPlatform()
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
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null && requireChrome && result.skippedTestCount > 0) {
            throw GradleException(
                "BREWSHOT_REQUIRE_CHROME is set but ${result.skippedTestCount} test(s) were " +
                "SKIPPED — a required run must execute or fail every test, never skip " +
                "(green-that-tested-nothing guard)."
            )
        }
    }))
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
