package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The capture path must not reference {@code javax.imageio} or {@code java.awt}.
 *
 * <p>THIS GUARD IS ON THE DEPENDENCY, NOT THE BEHAVIOUR, and that is deliberate
 * (ruling brewshot/632, plan d9a42ced). The JVM test suite CANNOT observe the failure
 * this exists to prevent: on a JVM {@code libawt} is present and {@code ImageIO}
 * initializes fine, and this build even sets {@code java.awt.headless=true} so it
 * initializes quietly. The failure appears only in the GraalVM native image, which is
 * built by a separate task and is not what the suite exercises. So asserting behaviour
 * here would assert the wrong thing; the IMPORT is the part that can be seen from a JVM.
 *
 * <p>What went wrong without it: {@code enforceCaptureBounds} inspected captured bytes
 * through {@code ImageIO}. It was careful about the expensive OPERATION — its javadoc
 * correctly said it read the header and decoded "no full pixel raster" — but not about
 * the expensive DEPENDENCY. Touching {@code ImageIO} at all runs its class initializer,
 * which reaches {@code IIORegistry} to {@code AppContext} to {@code java.awt.Toolkit} to
 * {@code libawt}, and the native binary died with {@code UnsatisfiedLinkError} on EVERY
 * capture while every test stayed green.
 *
 * <p>Scoped to {@code BrewShot.java} as a whole rather than to the one method, and that
 * is measured rather than assumed: before this change, every {@code ImageIO} reference in
 * the file sat inside {@code enforceCaptureBounds}, and the file contains no
 * {@code java.awt} reference at all. {@code GifWriter} is explicitly NOT covered — the GIF
 * path legitimately uses {@code ImageIO} and is documented library-only.
 */
class CaptureBoundsNativeCleanCensusTest {

    /** Package-relative source paths, resolved against whichever root the test JVM starts in. */
    private static final String CAPTURE_PATH_SOURCE = "src/main/java/com/brewshot/BrewShot.java";
    /** The GIF path, which legitimately uses ImageIO — the positive control for the matcher. */
    private static final String IMAGEIO_USING_SOURCE = "src/main/java/com/brewshot/GifWriter.java";

    private static final List<String> FORBIDDEN = List.of("javax.imageio", "java.awt", "ImageIO");

    /**
     * Resolve a repo-relative source file, failing LOUDLY when it cannot be found.
     * A census that silently reads nothing passes for the wrong reason.
     */
    private static String readSource(String relative) throws IOException {
        List<Path> tried = new ArrayList<>();
        Path cwd = Path.of("").toAbsolutePath();
        for (Path base = cwd; base != null; base = base.getParent()) {
            Path candidate = base.resolve(relative);
            tried.add(candidate);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(
            "census cannot find " + relative + "; looked at " + tried
            + ". A census that cannot read its subject must FAIL, never pass quietly.");
    }

    /**
     * Strip comments before scanning, because the census must judge CODE and not PROSE.
     *
     * <p>A bare substring match on "ImageIO" flags the javadoc that EXPLAINS why ImageIO is
     * banned, and a pre-existing comment elsewhere in the file that mentions it in passing. A
     * rule that forbids naming the thing it forbids cannot be documented, so it would have been
     * weakened or deleted by whoever hit it next. Matching a pattern is not matching the concept.
     *
     * <p>LITERAL-AWARE, and the first version was not (should-fix S2, brewshot/634). It used
     * {@code //.*$}, which also strips from a {@code //} INSIDE A STRING LITERAL to the end of
     * the line — and this very file carries such literals, at the websocket URL patterns. So
     * {@code "ws://..."} followed by a fully qualified {@code ImageIO} call ON THE SAME LINE
     * left the census green, which made the sentence I had written here — that a literal "would
     * be flagged rather than missed" — FALSE. The scanner below tracks string and char literals
     * and their escapes, so a {@code //} inside one is not a comment and code after it is still
     * scanned. {@code theStripperDoesNotTreatASlashSlashInsideAStringAsAComment} pins it.
     *
     * <p>NOT HANDLED: text blocks. This file contains none (checked), and adding them would need
     * a third state; saying so beats leaving a reader to assume coverage that is not there.
     */
    static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        final int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = (i + 1 < n) ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') { i++; }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) { i++; }
                i = Math.min(i + 2, n);
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) { out.append(source.charAt(i)); i++; continue; }
                    if (d == quote) { break; }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Pins should-fix S2 with the exact evasion the reviewer named: a string literal carrying
     * {@code //} on the same line as a forbidden reference. The old regex stripper returned
     * everything after the literal's slashes, so the census read this line as empty and passed.
     */
    @Test
    void theStripperDoesNotTreatASlashSlashInsideAStringAsAComment() {
        String line = "String u = \"ws://127.0.0.1\"; javax.imageio.ImageIO.read(null);";
        String stripped = withoutComments(line);
        assertTrue(stripped.contains("javax.imageio.ImageIO"),
            "code after a string literal containing // must survive stripping, or the census "
            + "can be evaded by putting a URL on the same line. Got: " + stripped);
    }

    @Test
    void theStripperStillRemovesRealComments() {
        String src = "int a = 1; // javax.imageio.ImageIO here is prose\nint b = 2;\n"
            + "/* and java.awt.Toolkit here is prose too */ int c = 3;";
        String stripped = withoutComments(src);
        assertFalse(stripped.contains("javax.imageio.ImageIO"), "line comment must be stripped");
        assertFalse(stripped.contains("java.awt.Toolkit"), "block comment must be stripped");
        assertTrue(stripped.contains("int c = 3;"), "code after a block comment must survive");
    }

    @Test
    void theCapturePathReferencesNoImageIoOrAwt() throws IOException {
        String source = withoutComments(readSource(CAPTURE_PATH_SOURCE));

        // Anti-vacuity: prove we read the real file before trusting a clean result.
        assertTrue(source.length() > 10_000,
            "read a suspiciously small " + CAPTURE_PATH_SOURCE + " (" + source.length()
            + " chars) — the census must not pass because it read the wrong thing");
        assertTrue(source.contains("static void enforceCaptureBounds("),
            "the source read does not contain enforceCaptureBounds, so this is not the capture path");

        List<String> hits = new ArrayList<>();
        for (String token : FORBIDDEN) {
            if (source.contains(token)) { hits.add(token); }
        }
        assertTrue(hits.isEmpty(),
            "the capture path must stay native-clean, but " + CAPTURE_PATH_SOURCE
            + " references " + hits + ". ImageIO's class initializer reaches java.awt.Toolkit "
            + "and libawt, which the GraalVM native image lacks on macOS, so the native binary "
            + "cannot capture at all. Read dimensions from the bytes instead (PNG IHDR, JPEG SOF).");
    }

    /**
     * POSITIVE CONTROL on the matcher itself. Without this, a census whose token list was
     * typo'd — or whose reader returned an empty string — would report CLEAN for every file
     * and look like a guard while guarding nothing.
     */
    @Test
    void theCensusCanActuallyDetectAnImageIoReference() throws IOException {
        String gifWriter = withoutComments(readSource(IMAGEIO_USING_SOURCE));
        assertTrue(gifWriter.length() > 1_000,
            "control file read too small to be real: " + gifWriter.length() + " chars");

        List<String> hits = new ArrayList<>();
        for (String token : FORBIDDEN) {
            if (gifWriter.contains(token)) { hits.add(token); }
        }
        assertFalse(hits.isEmpty(),
            "the GIF path is known to use ImageIO, so the census MUST flag it. Finding it clean "
            + "means the matcher or the reader is broken, and every other clean result in this "
            + "class is worthless.");
    }
}
