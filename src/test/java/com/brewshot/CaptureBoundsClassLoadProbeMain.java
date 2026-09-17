package com.brewshot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs {@link BrewShot#enforceCaptureBounds} in a FORKED JVM so a class-load log can show
 * what the capture path actually pulls in (must-fix M2, brewshot/634).
 *
 * <p>THIS CLASS MUST NOT TOUCH {@code ImageIO} OR AWT ITSELF in the "clean" mode, or it
 * would load the very classes the test is checking for and the result would say nothing
 * about the capture path. That is why it READS fixture bytes from disk rather than encoding
 * them: encoding an image is exactly what would load {@code ImageIO}. The parent generates
 * the fixtures, where using {@code ImageIO} is fine.
 *
 * <p>Mode {@code touch-imageio} is the POSITIVE CONTROL: it deliberately does the forbidden
 * thing, so the test can prove the class-load log would have SHOWN a violation. Without it,
 * a typo in the {@code -Xlog} flag yields an empty log and "no ImageIO was loaded" passes for
 * the wrong reason.
 */
public final class CaptureBoundsClassLoadProbeMain {

    private CaptureBoundsClassLoadProbeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("expected <clean|touch-imageio> <file>...");
        }
        String mode = args[0];
        for (int i = 1; i < args.length; i++) {
            byte[] bytes = Files.readAllBytes(Path.of(args[i]));
            BrewShot.enforceCaptureBounds(bytes);
            int[] wh = BrewShot.readImageHeaderDimensions(bytes);
            if (wh == null) {
                throw new IllegalStateException("probe fixture did not parse: " + args[i]);
            }
            System.out.println("parsed " + wh[0] + "x" + wh[1]);
        }
        if ("touch-imageio".equals(mode)) {
            // The control: reach ImageIO on purpose so the log has something to find.
            System.out.println("readers="
                + javax.imageio.ImageIO.getImageReadersByFormatName("png").hasNext());
        }
        System.out.println("probe-ok");
    }
}
