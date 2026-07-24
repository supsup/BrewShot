package com.brewshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Real-process discriminator for a child created by the parent's shutdown
 * path. The helper deliberately outlives and reparents away from the parent.
 */
public final class LateSpawnOnShutdownProbeMain {

    private LateSpawnOnShutdownProbeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "expected <parent|helper> <receipt> <ready>");
        }
        Path receipt = Path.of(args[1]);
        Path ready = Path.of(args[2]);
        if ("helper".equals(args[0])) {
            Files.writeString(
                ready, Long.toString(ProcessHandle.current().pid()),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            new CountDownLatch(1).await();
            return;
        }
        if (!"parent".equals(args[0])) {
            throw new IllegalArgumentException("unknown mode: " + args[0]);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Process helper = new ProcessBuilder(
                    javaBinary(),
                    "-Djava.awt.headless=true",
                    "-cp", System.getProperty("java.class.path"),
                    LateSpawnOnShutdownProbeMain.class.getName(),
                    "helper", receipt.toString(), ready.toString())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                Files.writeString(
                    receipt, Long.toString(helper.pid()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                awaitReady(ready);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "could not create late shutdown helper", e);
            }
        }, "brewshot-late-spawn-probe"));

        System.out.println("READY");
        System.out.flush();
        // The test's Process wrapper implements destroy() by sending this
        // portable normal-exit request. A direct OS signal does not reliably
        // run Java shutdown hooks on every supported platform.
        System.in.read();
    }

    private static String javaBinary() {
        return Path.of(
            System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void awaitReady(Path ready) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(ready) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (!Files.exists(ready)) {
            throw new IOException("late helper did not become ready");
        }
    }
}
