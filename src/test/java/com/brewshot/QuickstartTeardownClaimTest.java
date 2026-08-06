package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/// Pins QUICKSTART's teardown claim against the behaviour the code actually implements
/// (plan 398daca1 item 4).
///
/// WHY. The canonical try-with-resources example — the FIRST code a new user reads —
/// commented `// Chrome + temp profile cleaned up`. That is an unconditional claim about a
/// conditional outcome. `BrewShot.ResourceLease` deletes the profile only when
/// `releaseProven` holds (`processTreeReaped && orphansReaped && processTreeReleaseSafe()`),
/// and deregistration additionally requires `profileAbsenceProven()`; a failed or uncertain
/// proof deliberately RETAINS the profile, and JVM exit leaves an unproven one on disk.
///
/// RELEASE_NOTES 0.9.0 already says exactly this, and says it "corrects the older 'no leaked
/// temp dirs' overclaim" — so the code and the release notes agreed with each other and
/// QUICKSTART was the outlier, still carrying the very sentence that entry was written to
/// retract.
///
/// WHY A NEGATIVE PIN. There is no numeric floor to derive this from, the way the LatteX
/// corpus figure derives from the ratchet constant. What CAN be pinned is the retraction: a
/// future edit must not quietly reassert unconditional cleanup. This asserts the absence of
/// the corrected phrasing and the presence of the conditional framing — narrow, and stated
/// as narrow.
///
/// SCOPE: QUICKSTART only. README and the javadoc are not audited here.
class QuickstartTeardownClaimTest {

    private static final Path QUICKSTART = Path.of("QUICKSTART.md");

    @Test
    void quickstartDoesNotReassertUnconditionalProfileCleanup() throws IOException {
        assertTrue(Files.exists(QUICKSTART),
            "QUICKSTART.md not found from the test working dir " + Path.of("").toAbsolutePath()
                + " — this guard is INERT, not passing");
        String text = Files.readString(QUICKSTART, StandardCharsets.UTF_8);

        // The exact overclaim RELEASE_NOTES 0.9.0 retracts.
        assertTrue(!text.contains("no leaked temp dirs"),
            "QUICKSTART reasserts the 'no leaked temp dirs' claim that RELEASE_NOTES 0.9.0 "
                + "explicitly corrects");
        assertTrue(!text.contains("temp profile cleaned up"),
            "QUICKSTART claims the temp profile is unconditionally cleaned up. The lease deletes "
                + "it only when releaseProven holds, and RETAINS it on a failed or uncertain "
                + "proof — an unconditional promise in the first example a user reads is the "
                + "overclaim RELEASE_NOTES 0.9.0 was written to retract.");

        // NON-VACUITY. Without this, deleting the teardown discussion entirely would pass both
        // assertions above — absence of a wrong claim is not presence of a right one.
        assertTrue(text.contains("proven safe") || text.contains("prove containment"),
            "the teardown comment no longer states the CONDITION under which the profile is "
                + "removed; this guard only checks that the overclaim is gone, so without the "
                + "conditional framing present it would pass over a doc that says nothing at all");
    }
}
