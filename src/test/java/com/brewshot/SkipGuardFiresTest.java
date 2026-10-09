package com.brewshot;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the build's BREWSHOT_REQUIRE_CHROME no-skip guard actually fires.
 *
 * <p>The guard lives in {@code build.gradle.kts}. This test copies THAT file, unchanged,
 * into a throwaway fixture project and runs it through Gradle TestKit on the same Gradle
 * distribution that is running this suite. So the code under test is the real guard on
 * the real Gradle, not a transcription of either: when the wrapper moves to a Gradle that
 * changes how test evidence is recorded, this test is where it goes red.
 *
 * <p>The fixture has one test that always skips (it calls a stub
 * {@code TestChrome.requireChromeOrLoudSkip}, which also satisfies the build's
 * Chrome-test discovery) and one that always passes. Fixture-only configuration is
 * APPENDED to the copied script; nothing in the guard is replaced.
 *
 * <p>Browser-free: the fixture never launches Chrome, and the nested build gets an
 * environment with every inherited {@code BREWSHOT_*} variable removed, so the outer
 * lane's own REQUIRE/FORBID setting cannot leak into the case under test.
 */
class SkipGuardFiresTest {

    private static final String GUARD_MARKER = "green-that-tested-nothing guard";
    private static final String SKIP_VERDICT = "1 test(s) were SKIPPED";
    private static final String NO_EVIDENCE = "no-skip guard found no usable test evidence";
    private static final String PASSING_ONLY = "com.brewshot.FixturePlainTest";

    @TempDir
    Path fixture;

    @BeforeEach
    void writeFixture() throws IOException {
        Path realScript = Path.of("build.gradle.kts");
        assertTrue(Files.isRegularFile(realScript),
            "build.gradle.kts not found from the test working dir " + Path.of("").toAbsolutePath());
        Files.copy(realScript, fixture.resolve("build.gradle.kts"));
        Files.writeString(fixture.resolve("settings.gradle.kts"),
            "rootProject.name = \"brewshot-skip-guard-fixture\"\n");
        Path pkg = Files.createDirectories(fixture.resolve("src/test/java/com/brewshot"));
        Files.writeString(pkg.resolve("TestChrome.java"), """
            package com.brewshot;
            final class TestChrome {
                static void requireChromeOrLoudSkip(String suite) {
                    org.junit.jupiter.api.Assumptions.abort("fixture skip: " + suite);
                }
            }
            """);
        Files.writeString(pkg.resolve("FixtureChromeTest.java"), """
            package com.brewshot;
            import org.junit.jupiter.api.Test;
            class FixtureChromeTest {
                @Test
                void browser() {
                    TestChrome.requireChromeOrLoudSkip("FixtureChromeTest");
                }
            }
            """);
        Files.writeString(pkg.resolve("FixturePlainTest.java"), """
            package com.brewshot;
            import org.junit.jupiter.api.Test;
            class FixturePlainTest {
                @Test
                void plain() { }
            }
            """);
    }

    @Test
    void requiredRunWithASkipFailsTheBuild() {
        BuildResult result = fails(true, "test");
        assertEquals(TaskOutcome.FAILED, testOutcome(result), result.getOutput());
        assertTrue(result.getOutput().contains(
            "BREWSHOT_REQUIRE_CHROME is set but " + SKIP_VERDICT), result.getOutput());
        assertTrue(result.getOutput().contains(GUARD_MARKER), result.getOutput());
    }

    @Test
    void requiredRunWithNoSkipPasses() throws IOException {
        // Control for the case above: same fixture, same flag, the skipping test filtered out.
        BuildResult result = passes(true, "test", "--tests", PASSING_ONLY, "--warning-mode", "all");
        assertEquals(TaskOutcome.SUCCESS, testOutcome(result), result.getOutput());
        assertFalse(result.getOutput().contains(GUARD_MARKER), result.getOutput());
        // The guard used to be a deprecated afterSuite(Closure) listener scheduled for
        // removal. Its replacement must not trade one deprecation for another, whether
        // Gradle reports it at run time ("has been deprecated") or the Kotlin compiler
        // reports it while compiling the script ("w: ... is deprecated").
        assertFalse(result.getOutput().toLowerCase(java.util.Locale.ROOT).contains("deprecated"),
            result.getOutput());
        assertTrue(fixtureReport().contains("tests=\"1\""), "the control must really have run a test");
    }

    @Test
    void guardStaysOffWhenTheFlagIsUnset() throws IOException {
        // Local runs without Chrome must still be able to skip.
        BuildResult result = passes(false, "test");
        assertEquals(TaskOutcome.SUCCESS, testOutcome(result), result.getOutput());
        assertTrue(fixtureReport("FixtureChromeTest").contains("<skipped"),
            "the off case must really have recorded a skip, or it proves nothing");
    }

    @Test
    void disabledXmlReportFailsClosed() throws IOException {
        appendToFixtureScript("tasks.test { reports.junitXml.required = false }");
        BuildResult result = fails(true, "test", "--tests", PASSING_ONLY);
        assertEquals(TaskOutcome.FAILED, testOutcome(result), result.getOutput());
        assertTrue(result.getOutput().contains("JUnit XML report is disabled"), result.getOutput());
    }

    @Test
    void missingResultsDirectoryFailsClosed() throws IOException {
        // Stand-in for a future Gradle that writes the report somewhere else: delete the
        // evidence after the tests ran and before the guard reads it.
        appendBeforeGuard("delete(reports.junitXml.outputLocation)");
        BuildResult result = fails(true, "test", "--tests", PASSING_ONLY);
        assertEquals(TaskOutcome.FAILED, testOutcome(result), result.getOutput());
        assertTrue(result.getOutput().contains(NO_EVIDENCE), result.getOutput());
        assertTrue(result.getOutput().contains("does not exist"), result.getOutput());
    }

    @Test
    void unparseableReportFailsClosed() throws IOException {
        appendBeforeGuard("reports.junitXml.outputLocation.get().file(\"TEST-zz-garbage.xml\")"
            + ".asFile.writeText(\"<testsuite tests=\")");
        BuildResult result = fails(true, "test", "--tests", PASSING_ONLY);
        assertEquals(TaskOutcome.FAILED, testOutcome(result), result.getOutput());
        assertTrue(result.getOutput().contains(NO_EVIDENCE), result.getOutput());
        assertTrue(result.getOutput().contains("TEST-zz-garbage.xml is not parseable XML"),
            result.getOutput());
    }

    @Test
    void zeroRecordedTestsFailsClosed() throws IOException {
        appendToFixtureScript("""
            tasks.test {
                filter {
                    isFailOnNoMatchingTests = false
                    includeTestsMatching("com.brewshot.NoSuchFixtureTest")
                }
            }
            """);
        BuildResult result = fails(true, "test");
        assertEquals(TaskOutcome.FAILED, testOutcome(result), result.getOutput());
        assertTrue(result.getOutput().contains(NO_EVIDENCE), result.getOutput());
    }

    /**
     * Insert a fixture action AFTER the tests ran but BEFORE the guard's named doLast, so
     * the guard reads evidence the fixture tampered with. Fails the nested build at
     * configuration time if the guard action is not installed under its name.
     */
    private void appendBeforeGuard(String kotlinStatement) throws IOException {
        appendToFixtureScript("""
            tasks.test {
                val guardIndex = actions.indexOfFirst {
                    (it as? org.gradle.api.Describable)?.displayName?.contains("brewshotNoSkipGuard") == true
                }
                if (guardIndex < 0) throw GradleException("fixture: no-skip guard action not installed")
                actions.add(guardIndex, Action<Task> { %s })
            }
            """.formatted(kotlinStatement));
    }

    private void appendToFixtureScript(String kotlin) throws IOException {
        Path script = fixture.resolve("build.gradle.kts");
        Files.writeString(script, Files.readString(script) + "\n// ---- fixture-only ----\n" + kotlin + "\n");
    }

    private String fixtureReport() throws IOException {
        return fixtureReport("FixturePlainTest");
    }

    private String fixtureReport(String simpleName) throws IOException {
        return Files.readString(fixture.resolve(
            "build/test-results/test/TEST-com.brewshot." + simpleName + ".xml"));
    }

    /** The nested :test outcome, or a failure that shows the nested build's output. */
    private static TaskOutcome testOutcome(BuildResult result) {
        var task = result.task(":test");
        assertNotNull(task, "nested build never ran :test\n" + result.getOutput());
        return task.getOutcome();
    }

    private BuildResult passes(boolean requireChrome, String... args) {
        return runner(requireChrome, args).build();
    }

    private BuildResult fails(boolean requireChrome, String... args) {
        return runner(requireChrome, args).buildAndFail();
    }

    private GradleRunner runner(boolean requireChrome, String... args) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(k -> k.startsWith("BREWSHOT_"));
        if (requireChrome) {
            env.put("BREWSHOT_REQUIRE_CHROME", "1");
        }
        List<String> arguments = new ArrayList<>(List.of(args));
        arguments.add("--stacktrace");
        return GradleRunner.create()
            .withProjectDir(fixture.toFile())
            .withEnvironment(env)
            .withArguments(arguments);
    }
}
