package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capture path must not LOAD {@code javax.imageio.ImageIO} or {@code java.awt.Toolkit}
 * (must-fix M2, brewshot/634).
 *
 * <p>WHY THIS EXISTS ALONGSIDE THE SOURCE CENSUS, and why it supersedes it as the real guard.
 * The census scans ONE FILE for a reference. A source scan cannot follow a call into another
 * class, so a helper class that uses {@code ImageIO} and is called from
 * {@code enforceCaptureBounds} satisfies the census and reintroduces the defect — the reviewer
 * demonstrated exactly that mutant with the full suite green. THE CENSUS ASSERTED A PROXY (no
 * import in this file) FOR THE PROPERTY (no AWT class ever loads), and a proxy holds only
 * until someone routes around it.
 *
 * <p>This asserts the property. A forked JVM runs the capture bound over real PNG and JPEG
 * bytes with {@code -Xlog:class+load}, and the log must never name those classes. The fork is
 * required: this test JVM has already loaded {@code ImageIO} to build the fixtures, so nothing
 * in-process could tell the difference.
 */
class CaptureBoundsDoesNotLoadAwtTest {

    private static final List<String> FORBIDDEN_CLASSES =
        List.of("javax.imageio.ImageIO", "java.awt.Toolkit");

    /** Fixtures are built HERE, in the parent, because encoding one would load ImageIO. */
    private static Path writePng(Path dir, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", out), "PNG fixture must encode");
        Path p = dir.resolve("fixture.png");
        Files.write(p, out.toByteArray());
        return p;
    }

    private static Path writeJpeg(Path dir, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpg", out), "JPEG fixture must encode");
        Path p = dir.resolve("fixture.jpg");
        Files.write(p, out.toByteArray());
        return p;
    }

    private record Fork(int exit, String stdout, String classLog) { }

    private static Fork runProbe(Path dir, String mode, Path... fixtures) throws Exception {
        Path log = dir.resolve("class-load-" + mode + ".log");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>(List.of(
            javaBin,
            "-Djava.awt.headless=true",
            "-Xlog:class+load=info:file=" + log,
            "-cp", System.getProperty("java.class.path"),
            CaptureBoundsClassLoadProbeMain.class.getName(),
            mode));
        for (Path f : fixtures) { cmd.add(f.toString()); }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "probe JVM did not exit in time");
        assertTrue(Files.isRegularFile(log),
            "no class-load log was written, so this test could not have seen a violation: " + stdout);
        return new Fork(p.exitValue(), stdout, Files.readString(log, StandardCharsets.UTF_8));
    }

    @Test
    void theCapturePathLoadsNeitherImageIoNorToolkit(@TempDir Path dir) throws Exception {
        Fork run = runProbe(dir, "clean", writePng(dir, 64, 48), writeJpeg(dir, 64, 48));
        assertEquals(0, run.exit(), "probe failed: " + run.stdout());
        assertTrue(run.stdout().contains("probe-ok"), "probe did not run to completion: " + run.stdout());

        // Anti-vacuity: the log must be real and must show the class under test loading.
        assertTrue(run.classLog().contains("com.brewshot.BrewShot"),
            "the class-load log does not even show BrewShot loading, so a clean result means nothing");

        for (String forbidden : FORBIDDEN_CLASSES) {
            assertFalse(run.classLog().contains(forbidden),
                "the capture path loaded " + forbidden + ". The native image has no libawt on "
                + "macOS, so this is the crash that made the native binary unable to capture at "
                + "all. A source census cannot catch this when the reference is one call away.");
        }
    }

    /**
     * POSITIVE CONTROL on the whole apparatus — fork, flag, log file, and matcher. Without it,
     * a typo in {@code -Xlog} or a mis-spelled class name yields a clean result for every run,
     * and the test above would pass while guarding nothing.
     */
    @Test
    void theProbeWouldHaveSeenImageIoIfItHadBeenLoaded(@TempDir Path dir) throws Exception {
        Fork run = runProbe(dir, "touch-imageio", writePng(dir, 64, 48));
        assertEquals(0, run.exit(), "control probe failed: " + run.stdout());
        assertTrue(run.classLog().contains("javax.imageio.ImageIO"),
            "the control deliberately touched ImageIO and the log did not show it, so the "
            + "class-load instrument is broken and every clean result from it is worthless");
    }
}
