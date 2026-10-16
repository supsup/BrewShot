package com.brewshot;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Private subprocess entry point: the folder worker bounds this entire JVM's lifetime. */
final class BrewShotWatchCapture {
    private BrewShotWatchCapture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) { System.exit(2); }
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Path receipt = Path.of(args[2]);
        Path scratch = Path.of(args[3]);
        // URL queries, page text and eval results may contain secrets. Only the bounded
        // structured result below crosses the subprocess boundary into watcher logs.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Map<String, Object> result = new LinkedHashMap<>();
        int exit = 1;
        Path image = null;
        try {
            BrewShotWatchJob.Prepared job = BrewShotWatchJob.prepare(source, scratch);
            image = target.resolveSibling(target.getFileName() + "." + job.format());
            var cli = literalArguments(job.arguments());
            cli.add("-o");
            cli.add(image.toString());
            // Keep diagnostics private to the job; Main supplies requested/encoded GIF
            // timing and typed cleanup in its own receipt, with no second implementation.
            cli.add("--json");
            cli.add(scratch.resolve("capture.json").toString());
            CleanupOutcome[] cleanup = new CleanupOutcome[1];
            exit = Main.run(cli.toArray(String[]::new), value -> cleanup[0] = value);
            result.put("format", job.format());
            if (cleanup[0] != null) { result.put("cleanup", cleanup[0].toJson()); }
            if (exit == 0 || exit == Main.EXIT_CLEANUP_UNCONFIRMED) {
                // Both are hidden worker temporaries on the SAME output mount. Main
                // created the image owner-only; rename preserves that permission mode.
                Files.move(image, target, StandardCopyOption.ATOMIC_MOVE);
            }
            result.put("code", "capture-exit-" + exit);
        } catch (Exception | LinkageError failure) {
            // The parser's detail is intentionally not logged here, even on a future
            // path that accidentally includes source content in an exception message.
            String message = failure.getMessage();
            result.put("code", message != null && message.matches("JOB_[A-Z_]{1,48}")
                ? message : "job-" + failure.getClass().getSimpleName());
            exit = 1;
        } finally {
            if (image != null) { Files.deleteIfExists(image); }
            Files.writeString(receipt, MiniJson.stringify(result));
        }
        System.exit(exit);
    }

    static ArrayList<String> literalArguments(java.util.List<String> arguments) {
        var result = new ArrayList<String>();
        result.add(arguments.get(0));
        // Main supports --name=value. Use that spelling so its normalization
        // cannot reinterpret a VALUE beginning "--x=y" as another option pair.
        for (int index = 1; index < arguments.size(); index += 2) {
            result.add(arguments.get(index) + "=" + arguments.get(index + 1));
        }
        return result;
    }
}
