package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.ArrayList;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/** CLI argument validation and scripted capture paths; receipt fixtures launch no real Chrome. */
@Isolated("Scripted Main launch, process and stderr seams are restored before other tests run")
class MainCliTest {

    @Test
    void failedWaitDoesNotCaptureOrReturnSuccessAndOnTimeWaitStillCaptures(
            @TempDir Path directory) throws Exception {
        for (boolean ready : new boolean[] {false, true}) {
            Path venue = Files.createDirectory(directory.resolve("wait-" + ready));
            Path out = venue.resolve("capture.png");
            Path json = venue.resolve("capture.json");
            WaitRun run = runWaitCli(venue, out, json, false, ready);
            assertEquals(1, run.polls(), "actual waitFor evaluation must be reached");
            if (ready) {
                assertNull(run.failure());
                assertEquals(Integer.valueOf(0), run.code());
                assertEquals(1, run.captures());
                assertTrue(Files.isRegularFile(out));
                assertTrue(Files.isRegularFile(json));
            } else {
                assertTrue(run.failure() instanceof IllegalStateException, String.valueOf(run.failure()));
                assertTrue(run.failure().getMessage().startsWith("waitFor timed out after 50ms"));
                assertNull(run.code(), "Main.run propagates runtime capture failures to its existing caller");
                assertEquals(0, run.captures());
                assertFalse(Files.exists(out));
                assertFalse(Files.exists(json));
                assertTrue(run.elapsedMs() < 1_000, "wait must not spend the default 15s command budget");
            }
        }
    }

    record WaitRun(Integer code, Exception failure, int captures, int polls, long elapsedMs) { }

    static WaitRun runWaitCli(Path venue, Path out, Path json, boolean digest, boolean ready)
            throws Exception {
        ReceiptScript script = new ReceiptScript(out, false);
        script.waitPredicateReady = ready;
        long started = System.nanoTime();
        String waitMillis = ready ? "1000" : "50";
        ReceiptRun run = runReceiptCli(new ReceiptLane("wait", "png",
            List.of("--wait-js", "ready", "--wait-timeout", waitMillis)), digest,
            out, json, script, venue, 90);
        return new WaitRun(run.code(), run.failure(), script.captures, script.waitPolls,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /** Case 4: real Main/BrewShot paths, with replacement at close BEFORE manifest publication. */
    @Test
    void everyCliLaneKeepsItsCompletedProducerReceiptAcrossPostCaptureReplacement(
            @TempDir Path directory) throws Exception {
        List<ReceiptLane> lanes = List.of(
            new ReceiptLane("png", "png", List.of()),
            new ReceiptLane("jpeg", "jpg", List.of("--jpeg-quality", "80")),
            new ReceiptLane("pdf", "pdf", List.of()),
            new ReceiptLane("gif-page", "gif", List.of("--gif", "3", "--gif-delay", "1")),
            new ReceiptLane("gif-element", "gif", List.of("--gif", "3", "--gif-delay", "1",
                "--gif-element", "#fixture")),
            new ReceiptLane("selector", "png", List.of("--clip-selector", "#fixture")),
            new ReceiptLane("clip-js", "png", List.of("--clip-js", "({x:0,y:0,w:16,h:12})")),
            new ReceiptLane("scale", "png", List.of("--scale", "2")),
            new ReceiptLane("vision", "png", List.of("--vision-deficiency", "deuteranopia"))
        );
        int id = 0;
        for (ReceiptLane lane : lanes) {
            Path positiveVenue = Files.createDirectory(directory.resolve(lane.name() + "-positive"));
            Path positiveOut = positiveVenue.resolve("capture." + lane.extension());
            Path positiveJson = positiveVenue.resolve("capture.json");
            ReceiptScript positive = new ReceiptScript(positiveOut, false);
            positive.prepareExpected(lane, directory.resolve(lane.name() + "-reference.gif"));
            int positiveId = id++;
            ReceiptRun captured = ArtifactWriterTest.withoutArtifactReads(positiveVenue,
                () -> runReceiptCli(lane, true, positiveOut, positiveJson, positive, positiveVenue, positiveId));
            assertNull(captured.failure(), "positive CLI capture failed: " + captured.failure());
            assertEquals(Integer.valueOf(0), captured.code());
            org.junit.jupiter.api.Assertions.assertArrayEquals(positive.expectedProduced,
                Files.readAllBytes(positiveOut), "real enabled CLI must write its capture bytes");
            @SuppressWarnings("unchecked")
            Map<String, Object> positiveRoot = (Map<String, Object>) MiniJson.parse(Files.readString(positiveJson));
            assertEquals(ArtifactWriterTest.receiptHash(positive.expectedProduced), positiveRoot.get("outSha256"));

            java.util.Set<String> oldKeys = null;
            for (boolean enabled : new boolean[] {false, true}) {
                Path venue = Files.createDirectory(directory.resolve(lane.name() + "-" + enabled));
                Path out = venue.resolve("capture." + lane.extension());
                Path json = venue.resolve("capture.json");
                ReceiptScript script = new ReceiptScript(out, true);
                script.prepareExpected(lane, directory.resolve(lane.name() + "-replacement-reference.gif"));
                int runId = id++;
                ReceiptRun run = enabled
                    ? ArtifactWriterTest.withoutArtifactReads(venue,
                        () -> runReceiptCli(lane, true, out, json, script, venue, runId))
                    : runReceiptCli(lane, false, out, json, script, venue, runId);
                assertNull(run.failure(), "real CLI failed before receipt assertions: " + run.failure());
                assertEquals(Integer.valueOf(0), run.code(), lane.name());
                assertNull(script.oracleFailure, "INSTRUMENT: close-time replacement failed");
                assertEquals(1, script.navigations);
                assertEquals(lane.extension().equals("gif") ? 3 : 1, script.captures);
                assertTrue(script.closed, "INSTRUMENT: actual transport close must occur before JSON");
                assertTrue(script.expectedProduced != null && script.expectedProduced.length > 0);
                assertTrue(Files.size(out) > script.expectedProduced.length,
                    "INSTRUMENT: replacement must differ in size as well as identity");
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) MiniJson.parse(Files.readString(json));
                assertEquals("640x480", root.get("viewport"), "viewport stays request metadata");
                if (!enabled) {
                    oldKeys = java.util.Set.copyOf(root.keySet());
                    assertTrue(oldKeys.containsAll(java.util.Set.of("input", "mode", "viewport",
                        "settleMs", "waitJs", "out", "outBytes", "eval", "failJs", "failJsPassed",
                        "elapsedMs", "brewshot")), "CLI must preserve every unconditional legacy key");
                    assertFalse(root.containsKey("outSha256"));
                    assertFalse(root.containsKey("outSha256Provenance"));
                    assertEquals(Files.size(out), ((Number) root.get("outBytes")).longValue(),
                        "unflagged receipt retains its old post-close Files.size semantics");
                } else {
                    assertEquals(script.expectedProduced.length, ((Number) root.get("outBytes")).longValue());
                    assertEquals(ArtifactWriterTest.receiptHash(script.expectedProduced), root.get("outSha256"));
                    assertEquals("capture-time-producer", root.get("outSha256Provenance"));
                    var expectedKeys = new java.util.HashSet<>(oldKeys);
                    expectedKeys.addAll(java.util.Set.of("outSha256", "outSha256Provenance"));
                    assertEquals(expectedKeys, root.keySet(), "exactly the two approved added keys");
                }
            }

            // A genuine artifact commit failure, after a live capture, cannot replace prior JSON.
            Path venue = Files.createDirectory(directory.resolve(lane.name() + "-write-failure"));
            Path out = Files.createDirectory(venue.resolve("capture." + lane.extension()));
            Files.writeString(out.resolve("keep"), "existing-directory");
            Path json = venue.resolve("capture.json");
            Files.writeString(json, "known-good-prior-sidecar");
            ReceiptScript script = new ReceiptScript(out, false);
            ReceiptRun failed = runReceiptCli(lane, true, out, json, script, venue, id++);
            assertTrue(failed.failure() instanceof java.io.IOException,
                "artifact publication must fail, not publish a partial receipt: " + failed.failure());
            assertTrue(script.captures > 0, "INSTRUMENT: failure must follow a real capture response");
            assertEquals("known-good-prior-sidecar", Files.readString(json));
            assertEquals("existing-directory", Files.readString(out.resolve("keep")));
            try (var files = Files.list(venue)) {
                assertFalse(files.anyMatch(f -> f.getFileName().toString().endsWith(".tmp")),
                    "failed receipt/artifact publication must clean its sibling temporary");
            }
        }
    }

    /** Case 6: the existing launch seam gains only a default availability witness for this gate. */
    @Test
    void artifactDigestRequiresExplicitJsonBeforeAvailabilityLaunchOrAnyWrite(
            @TempDir Path directory) throws Exception {
        AtomicInteger lookups = new AtomicInteger(), launches = new AtomicInteger();
        Main.SessionLauncher saved = Main.sessionLauncherForTests;
        Main.sessionLauncherForTests = new Main.SessionLauncher() {
            @Override public boolean available() { lookups.incrementAndGet(); return true; }
            @Override public BrewShot launch(int width, int height, Integer dpr) throws java.io.IOException {
                launches.incrementAndGet();
                throw new java.io.IOException("receipt-positive-launch-witness");
            }
        };
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(errors, true, java.nio.charset.StandardCharsets.UTF_8));
            Path out = directory.resolve("capture.png");
            for (String[] args : List.of(
                    new String[] {"https://example.invalid/", "--artifact-sha256", "-o", out.toString()},
                    new String[] {"https://example.invalid/", "-o", out.toString(), "--artifact-sha256"})) {
                errors.reset();
                Integer code = null;
                Exception failure = null;
                try { code = Main.run(args); } catch (Exception actual) { failure = actual; }
                assertEquals(0, lookups.get(), "invalid combination reached browser discovery");
                assertEquals(0, launches.get(), "invalid combination reached process launch");
                assertNull(failure, "usage refusal must return its code, not throw");
                assertEquals(Integer.valueOf(2), code);
                assertTrue(errors.toString(java.nio.charset.StandardCharsets.UTF_8).contains("--json"));
                assertFalse(Files.exists(out));
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            }
            // Positive control goes through actual Main parsing and BOTH browser-work hooks.
            Path json = directory.resolve("explicit.json");
            Files.writeString(json, "known-good-sidecar");
            Exception observed = null;
            try {
                Main.run(new String[] {"https://example.invalid/", "--artifact-sha256",
                    "--json", json.toString(), "-o", out.toString()});
            } catch (Exception actual) { observed = actual; }
            assertEquals(1, lookups.get(), "INSTRUMENT: valid explicit JSON must reach lookup");
            assertEquals(1, launches.get(), "INSTRUMENT: valid explicit JSON must reach launch");
            assertTrue(observed instanceof java.io.IOException
                && "receipt-positive-launch-witness".equals(observed.getMessage()),
                "INSTRUMENT: the deliberate launcher witness must decide the positive control");
            assertFalse(Files.exists(out));
            assertEquals("known-good-sidecar", Files.readString(json));
        } finally {
            System.setErr(original);
            Main.sessionLauncherForTests = saved;
        }
    }

    private record ReceiptLane(String name, String extension, List<String> options) { }
    private record ReceiptRun(Integer code, Exception failure) { }

    private static ReceiptRun runReceiptCli(ReceiptLane lane, boolean digest, Path out, Path json,
            ReceiptScript script, Path venue, int id) throws Exception {
        List<String> args = new ArrayList<>(List.of("https://example.invalid/receipt", "--size", "640x480",
            "--settle", "1", "-o", out.toString(), "--json", json.toString()));
        args.addAll(lane.options());
        if (digest) args.add("--artifact-sha256");
        return runReceiptArgs(args, script, venue, id);
    }

    private static ReceiptRun runReceiptArgs(List<String> args, ReceiptScript script,
            Path venue, int id) throws Exception {
        Main.SessionLauncher saved = Main.sessionLauncherForTests;
        var savedProbe = BrewShot.exitedButUnreapedProbeForTests;
        var chrome = new MainCliUnconfirmedCleanupTest.FakeChrome(524300L + id, true);
        Path profile = Files.createDirectory(venue.resolve("synthetic-profile"));
        BrewShot.exitedButUnreapedProbeForTests = pid -> false;
        Main.sessionLauncherForTests = (width, height, dpr) -> {
            assertEquals(640, width); assertEquals(480, height);
            return script.session(chrome, profile);
        };
        Integer code = null;
        Exception failure = null;
        try { code = Main.run(args.toArray(String[]::new)); }
        catch (Exception actual) { failure = actual; }
        finally {
            try {
                chrome.die();
                BrewShot.runShutdownCleanupForTests();
            } finally {
                Main.sessionLauncherForTests = saved;
                BrewShot.exitedButUnreapedProbeForTests = savedProbe;
            }
        }
        return new ReceiptRun(code, failure);
    }

    @Test
    void attachedReceiptBooleansPreserveInputAndTheLastOccurrenceWins(
            @TempDir Path directory) throws Exception {
        List<List<String>> spellings = List.of(
            List.of("--artifact-sha256"),
            List.of("--artifact-sha256=true"),
            List.of("--artifact-sha256=false"),
            List.of("--artifact-sha256", "--artifact-sha256=false"),
            List.of("--artifact-sha256=false", "--artifact-sha256=true"),
            List.of("--artifact-sha256=false", "--artifact-sha256"));
        int id = 100;
        for (int i = 0; i < spellings.size(); i++) {
            for (boolean beforeInput : new boolean[] {false, true}) {
                Path venue = Files.createDirectory(directory.resolve("spelling-" + i + "-" + beforeInput));
                Path out = venue.resolve("capture.png"), json = venue.resolve("capture.json");
                ReceiptScript script = new ReceiptScript(out, false);
                List<String> args = new ArrayList<>();
                if (beforeInput) args.addAll(spellings.get(i));
                args.addAll(List.of("https://example.invalid/receipt", "--size", "640x480",
                    "--settle", "1", "-o", out.toString(), "--json", json.toString()));
                if (!beforeInput) args.addAll(spellings.get(i));
                ReceiptRun run = runReceiptArgs(args, script, venue, id++);
                assertNull(run.failure(), "flag spelling " + args + ": " + run.failure());
                assertEquals(Integer.valueOf(0), run.code());
                assertEquals(1, script.navigations);
                assertEquals(1, script.captures);
                assertTrue(script.closed);
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) MiniJson.parse(Files.readString(json));
                assertEquals("https://example.invalid/receipt", root.get("input"));
                boolean enabled = i != 2 && i != 3;
                assertEquals(enabled, root.containsKey("outSha256"));
                assertEquals(enabled, root.containsKey("outSha256Provenance"));
                if (enabled) assertEquals(ArtifactWriterTest.receiptHash(script.png), root.get("outSha256"));
                org.junit.jupiter.api.Assertions.assertArrayEquals(script.png, Files.readAllBytes(out));
            }
        }
    }

    @Test
    void receiptBooleanDoesNotReplaceALocalInputContainingEquals(
            @TempDir Path directory) throws Exception {
        Path input = directory.resolve("input=false.html");
        Files.writeString(input, "<!doctype html><title>receipt input</title>");
        for (boolean beforeInput : new boolean[] {false, true}) {
            Path venue = Files.createDirectory(directory.resolve("file-" + beforeInput));
            Path out = venue.resolve("capture.png"), json = venue.resolve("capture.json");
            ReceiptScript script = new ReceiptScript(out, false);
            List<String> args = new ArrayList<>();
            if (beforeInput) args.add("--artifact-sha256=false");
            args.addAll(List.of(input.toString(), "--size", "640x480", "--settle", "1",
                "-o", out.toString(), "--json", json.toString()));
            if (!beforeInput) args.add("--artifact-sha256=false");
            ReceiptRun run = runReceiptArgs(args, script, venue, 150);
            assertNull(run.failure());
            assertEquals(Integer.valueOf(0), run.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) MiniJson.parse(Files.readString(json));
            assertEquals(input.toString(), root.get("input"));
            assertEquals("file", root.get("mode"));
            assertFalse(root.containsKey("outSha256"));
            assertFalse(root.containsKey("outSha256Provenance"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(script.png, Files.readAllBytes(out));
        }
    }

    @Test
    void disabledReceiptNeedsNoJsonAndSeparateBooleanWordsRemainPositional(
            @TempDir Path directory) throws Exception {
        for (String word : List.of("true", "false")) {
            // A bare flag must not consume the following word as its boolean value.
            // The existing last-positional-input rule then chooses the final URL.
            Path venue = Files.createDirectory(directory.resolve(word));
            Path out = venue.resolve("capture.png"), json = venue.resolve("capture.json");
            ReceiptScript script = new ReceiptScript(out, false);
            ReceiptRun run = runReceiptArgs(new ArrayList<>(List.of("--artifact-sha256", word,
                "https://example.invalid/receipt", "--size", "640x480", "--settle", "1",
                "-o", out.toString(), "--json", json.toString())), script, venue, 200);
            assertNull(run.failure());
            assertEquals(Integer.valueOf(0), run.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) MiniJson.parse(Files.readString(json));
            assertEquals("https://example.invalid/receipt", root.get("input"));
            assertEquals(ArtifactWriterTest.receiptHash(script.png), root.get("outSha256"));
        }
        Path venue = Files.createDirectory(directory.resolve("no-json"));
        Path out = venue.resolve("capture.png");
        ReceiptScript script = new ReceiptScript(out, false);
        ReceiptRun run = runReceiptArgs(new ArrayList<>(List.of("https://example.invalid/receipt",
            "--artifact-sha256=true", "--artifact-sha256=false", "--size", "640x480",
            "--settle", "1", "-o", out.toString())), script, venue, 201);
        assertNull(run.failure());
        assertEquals(Integer.valueOf(0), run.code());
        org.junit.jupiter.api.Assertions.assertArrayEquals(script.png, Files.readAllBytes(out));
        try (var files = Files.list(venue)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".json")));
        }
    }

    @Test
    void invalidAttachedReceiptValuesRefuseBeforeBrowserWorkAndPreservePriorFiles(
            @TempDir Path directory) throws Exception {
        AtomicInteger lookups = new AtomicInteger(), launches = new AtomicInteger();
        Main.SessionLauncher saved = Main.sessionLauncherForTests;
        PrintStream savedErr = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Main.sessionLauncherForTests = new Main.SessionLauncher() {
            @Override public boolean available() { lookups.incrementAndGet(); return true; }
            @Override public BrewShot launch(int width, int height, Integer dpr) {
                launches.incrementAndGet();
                throw new AssertionError("invalid attached value reached launch");
            }
        };
        Path out = directory.resolve("capture.png"), json = directory.resolve("capture.json");
        byte[] priorArtifact = {8, 3, 1};
        Files.write(out, priorArtifact);
        Files.writeString(json, "prior-sidecar");
        try {
            System.setErr(new PrintStream(errors, true, java.nio.charset.StandardCharsets.UTF_8));
            for (String value : List.of("", "TRUE", "False", "0", "yes", "false=extra")) {
                for (boolean beforeInput : new boolean[] {false, true}) {
                    List<String> args = new ArrayList<>();
                    if (beforeInput) args.add("--artifact-sha256=" + value);
                    args.addAll(List.of("https://example.invalid/receipt", "-o", out.toString(),
                        "--json", json.toString()));
                    if (!beforeInput) args.add("--artifact-sha256=" + value);
                    errors.reset();
                    assertEquals(2, Main.run(args.toArray(String[]::new)));
                    assertTrue(errors.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains("accepts only attached =true or =false"));
                    assertEquals(0, lookups.get());
                    assertEquals(0, launches.get());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(priorArtifact, Files.readAllBytes(out));
                    assertEquals("prior-sidecar", Files.readString(json));
                }
            }
            try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
        } finally {
            System.setErr(savedErr);
            Main.sessionLauncherForTests = saved;
        }
    }

    /** Scripted CDP, real BrewShot client/encoder/write/close/manifest. No browser process. */
    private static final class ReceiptScript implements WebSocket {
        final Path out;
        final boolean replace;
        final byte[] png = ArtifactWriterTest.receiptFixture("png");
        final byte[] jpeg = ArtifactWriterTest.receiptFixture("jpeg");
        final byte[] pdf = ArtifactWriterTest.receiptFixture("pdf");
        final LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>(65);
        final BrewShot.Accumulator listener = new BrewShot.Accumulator(inbox, 1 << 20, 64);
        int navigations, captures;
        boolean waitPredicateReady;
        int waitPolls;
        boolean closed;
        byte[] expectedProduced;
        Exception oracleFailure;

        ReceiptScript(Path out, boolean replace) throws java.io.IOException {
            this.out = out; this.replace = replace;
        }

        void prepareExpected(ReceiptLane lane, Path reference) throws java.io.IOException {
            // Independent oracles precede the Main/JFR window. Close never reads the artifact.
            expectedProduced = switch (lane.extension()) {
                case "jpg" -> jpeg;
                case "pdf" -> pdf;
                case "gif" -> {
                    GifWriter.write(List.of(png, png, png), 1, reference);
                    yield Files.readAllBytes(reference);
                }
                default -> png;
            };
        }

        BrewShot session(Process process, Path profile) {
            return new BrewShot(process, profile, this, inbox, 1_000L);
        }

        @Override public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text.toString());
            int id = ((Number) sent.get("id")).intValue();
            String method = String.valueOf(sent.get("method"));
            Object result;
            switch (method) {
                case "Page.navigate" -> { navigations++; result = Map.of("frameId", "main"); }
                case "Runtime.evaluate" -> {
                    String expression = String.valueOf(MiniJson.get(sent, "params.expression"));
                    Object value;
                    if (expression.equals("!!(ready)")) {
                        waitPolls++;
                        // No response models an awaited predicate that never resolves.
                        if (!waitPredicateReady) return CompletableFuture.completedFuture(this);
                        value = true;
                    }
                    else if (expression.equals("window.innerWidth+'x'+window.innerHeight")) value = "0x0";
                    else if (expression.contains("document.querySelector(") && expression.contains("getBoundingClientRect")) value = "0,0,16,12";
                    else if (expression.equals("document.documentElement.scrollWidth")) value = 16.0;
                    else if (expression.equals("document.documentElement.scrollHeight")) value = 12.0;
                    else if (expression.contains("scrollWidth") && expression.contains("join(','")) value = "16,12";
                    else if (expression.equals("({x:0,y:0,w:16,h:12})")) value = Map.of("x", 0, "y", 0, "w", 16, "h", 12);
                    else throw new AssertionError("INSTRUMENT: unhandled CDP expression: " + expression);
                    result = Map.of("result", Map.of("value", value));
                }
                case "Page.captureScreenshot", "Page.printToPDF" -> {
                    captures++;
                    byte[] bytes = method.equals("Page.printToPDF") ? pdf
                        : "jpeg".equals(MiniJson.get(sent, "params.format")) ? jpeg : png;
                    result = Map.of("data", Base64.getEncoder().encodeToString(bytes));
                }
                default -> result = Map.of();
            }
            listener.onText(this, MiniJson.stringify(Map.of("id", id, "result", result)), true);
            if (method.equals("Page.navigate")) listener.onText(this,
                "{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":1}}", true);
            return CompletableFuture.completedFuture(this);
        }

        @Override public CompletableFuture<WebSocket> sendClose(int status, String reason) {
            if (closed) return CompletableFuture.completedFuture(this);
            closed = true;
            if (replace) try {
                if (!Files.isRegularFile(out) || Files.size(out) != expectedProduced.length) {
                    throw new java.io.IOException("INSTRUMENT: complete expected artifact must precede close");
                }
                byte[] replacement = new byte[expectedProduced.length + 19];
                java.util.Arrays.fill(replacement, (byte) 0x5a);
                Files.write(out, replacement);
            } catch (Exception failure) { oracleFailure = failure; }
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return closed; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { closed = true; }
    }

    @Test
    void helpStatesTheExactByteAndImageAllocationContracts() throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(new String[] {"--help"});
        } finally {
            System.setErr(original);
        }
        String help = errors.toString();
        assertEquals(0, code);
        assertTrue(help.contains("brewshot.maxCdpMessageBytes")
                && help.contains("exact UTF-8 bytes per complete CDP message"), help);
        assertTrue(help.contains("image-header dimension checks after Base64 decode"), help);
        assertTrue(help.contains("not total GIF encoder heap"), help);
        assertTrue(help.contains("--dpr") && help.contains("integer 1-4")
                && help.contains("distinct from") && help.contains("--scale"), help);
        assertTrue(help.contains("--wait-timeout") && help.contains("requires --wait-js"), help);
    }

    @Test
    void exitCodes() throws Exception {
        assertEquals(0, Main.run(new String[] {"--help"}));
        assertEquals(0, Main.run(new String[] {"--version"}));
        assertEquals(2, Main.run(new String[] {}));                       // no input
        assertEquals(2, Main.run(new String[] {"--unknown-flag", "x"}));  // unknown flag
        assertEquals(2, Main.run(new String[] {"no-such-file.html"}));    // not url/file/-
        assertEquals(2, Main.run(new String[] {"--size", "wrong", "x.html"})); // bad WxH
    }

    @Test
    void equalsFormFlagsParseLikeSpaceForm() throws Exception {
        // --flag=value must NOT be rejected as an unknown flag (Lattice, brewshot/8)
        assertEquals(0, Main.run(new String[] {"--help"}));
        // these reach the input-resolution stage (exit 2 = "no such file", not
        // "unknown flag") which proves the = form was parsed, not swallowed:
        assertEquals(2, Main.run(new String[] {"--out=x.png", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--size=1440x900", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--wait-js=true", "no-such-file.html"}));
        // a genuinely unknown =flag still errors
        assertEquals(2, Main.run(new String[] {"--bogus=1", "no-such-file.html"}));
    }

    @Test
    void orphanWaitTimeoutRefusesBeforeInputChromeOrArtifacts(@TempDir Path directory)
            throws Exception {
        Path output = directory.resolve("never-written.png");
        Path receipt = directory.resolve("never-written.json");
        String missing = directory.resolve("definitely-missing.html").toString();
        java.util.List<String[]> spellingsAndOrders = java.util.List.of(
            new String[] {"--wait-timeout", "10000", "-o", output.toString(),
                "--json", receipt.toString(), missing},
            new String[] {missing, "--wait-timeout", "10000", "-o", output.toString(),
                "--json", receipt.toString()},
            new String[] {"--wait-timeout=10000", "-o", output.toString(),
                "--json", receipt.toString(), missing},
            new String[] {missing, "--wait-timeout=10000", "-o", output.toString(),
                "--json", receipt.toString()}
        );

        for (String[] args : spellingsAndOrders) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            PrintStream original = System.err;
            int code;
            try {
                System.setErr(new PrintStream(errors));
                code = Main.run(args);
            } finally {
                System.setErr(original);
            }
            assertEquals(2, code, java.util.Arrays.toString(args));
            assertTrue(errors.toString().contains("--wait-timeout requires --wait-js"),
                errors.toString());
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(receipt));
            try (var files = Files.list(directory)) {
                assertEquals(0L, files.count(),
                    "the dependency refusal must precede every filesystem side effect");
            }
        }

        ByteArrayOutputStream pairedErrors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int pairedCode;
        try {
            System.setErr(new PrintStream(pairedErrors));
            pairedCode = Main.run(new String[] {
                "--wait-js", "true", "--wait-timeout=10000", missing});
        } finally {
            System.setErr(original);
        }
        assertEquals(2, pairedCode);
        assertTrue(pairedErrors.toString().contains("not a URL, an existing file, or '-'"),
            pairedErrors.toString());
        org.junit.jupiter.api.Assertions.assertFalse(
            pairedErrors.toString().contains("--wait-timeout requires --wait-js"),
            pairedErrors.toString());

        ByteArrayOutputStream missingInputErrors = new ByteArrayOutputStream();
        original = System.err;
        int missingInputCode;
        try {
            System.setErr(new PrintStream(missingInputErrors));
            missingInputCode = Main.run(new String[] {"--wait-timeout", "10000"});
        } finally {
            System.setErr(original);
        }
        assertEquals(2, missingInputCode);
        assertTrue(missingInputErrors.toString().contains("usage: brewshot"),
            missingInputErrors.toString());
        org.junit.jupiter.api.Assertions.assertFalse(
            missingInputErrors.toString().contains("--wait-timeout requires --wait-js"),
            "input presence validation must run before the dependency guard");
    }

    @Test
    void newFlagShapesValidate() throws Exception {
        assertEquals(2, Main.run(new String[] {"--cookie", "malformed", "x.html"}));
        assertEquals(2, Main.run(new String[] {"--header", "no-colon", "x.html"}));
    }

    @Test
    void clipFlagShapesValidate() throws Exception {
        // --clip-selector and --clip-js are exclusive clip sources
        assertEquals(2, Main.run(new String[] {
            "--clip-selector", "svg", "--clip-js", "({x:0,y:0,w:1,h:1})", "https://example.com"}));
        // --scale must be a positive finite number
        assertEquals(2, Main.run(new String[] {"--scale", "nope", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--scale", "0", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--scale", "-2", "https://example.com"}));
        // --clip-padding must be non-negative
        assertEquals(2, Main.run(new String[] {"--clip-padding", "-4", "https://example.com"}));
        // = forms parse through to input resolution (exit 2 = "no such file", not unknown flag)
        assertEquals(2, Main.run(new String[] {"--clip-selector=svg", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--scale=3", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--clip-padding=8", "no-such-file.html"}));
    }

    @Test
    void emulatedMediaFlagShapesValidate() throws Exception {
        // plan 02af3a3d: --color-scheme/--media are a fixed enum; a bad value is a usage
        // error (exit 2) caught during arg parsing, before Chrome is ever touched.
        assertEquals(2, Main.run(new String[] {"--color-scheme", "bogus", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--media", "bogus", "https://example.com"}));
        // = forms parse through to input resolution (exit 2 = "no such file", not unknown flag)
        assertEquals(2, Main.run(new String[] {"--color-scheme=dark", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--media=print", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--reduced-motion", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--timezone", " ", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--timezone=Asia/Tokyo", "no-such-file.html"}));
    }

    @Test
    void dprIsAnIntegerOneThroughFourAndRefusesBeforeArtifacts(@TempDir Path directory)
            throws Exception {
        Path output = directory.resolve("never-written.png");
        Path receipt = directory.resolve("never-written.json");
        for (String rejected : new String[] {"0", "-1", "1.5", "NaN", "Infinity", "5"}) {
            assertEquals(2, Main.run(new String[] {
                "--dpr", rejected, "-o", output.toString(), "--json", receipt.toString(),
                "https://example.com"}), rejected);
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output), rejected);
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(receipt), rejected);
        }
        assertEquals(2, Main.run(new String[] {"--dpr=2", "no-such-file.html"}),
            "equals form must parse and reach input resolution");
    }

    @Test
    void gifFlagShapesValidate() throws Exception {
        // plan 6cc2d9ec: every constraint is a LOUD exit 2 at parse, before Chrome.
        // modifiers without --gif N
        assertEquals(2, Main.run(new String[] {"--gif-element", "svg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif-delay", "40", "https://example.com"}));
        // frame count / delay must be positive numbers — and `--gif 0` must REFUSE,
        // never fall through to a silent still shot (the 0-sentinel trap)
        assertEquals(2, Main.run(new String[] {"--gif", "nope", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "0", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "-3", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--gif-delay", "0", "https://example.com"}));
        // an explicit non-.gif -o under --gif is refused (evidence honesty, mirrors the
        // .pdf-vs-raster refusal), including a .pdf target
        assertEquals(2, Main.run(new String[] {"--gif", "10", "-o", "out.png", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "-o", "out.pdf", "https://example.com"}));
        // still-shot-only flags are refused with --gif
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--clip-selector", "svg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--clip-js", "({})", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--clip-padding", "4", "https://example.com"}));
        // = forms parse through to input resolution (exit 2 = "no such file", not unknown flag)
        assertEquals(2, Main.run(new String[] {"--gif=10", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--gif=10", "--gif-element=svg", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--gif=10", "--gif-delay=25", "no-such-file.html"}));
        // F1 (review brewshot/126, live-repro'd): the STILL path with a .gif out must refuse —
        // the third direction of the misnamed-extension class (it wrote PNG bytes into a .gif,
        // exit 0). Case-insensitive like the guard itself.
        assertEquals(2, Main.run(new String[] {"-o", "out.gif", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"-o", "out.GIF", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--out=still.gif", "no-such-file.html"}));
    }

    @Test
    void gifOutputExtensionMatchesCaseInsensitively() {
        // The --gif output guard's unit: same normalization rule as isPdfOutput.
        org.junit.jupiter.api.Assertions.assertTrue(Main.isGifOutput(java.nio.file.Path.of("x.gif")));
        org.junit.jupiter.api.Assertions.assertTrue(Main.isGifOutput(java.nio.file.Path.of("x.GIF")));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isGifOutput(java.nio.file.Path.of("x.png")));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isGifOutput(java.nio.file.Path.of("gif")));
    }

    @Test
    void badFlagValuesExitCleanlyNotWithAStackTrace() throws Exception {
        // Lattice's four repros (brewshot/9): thrown parse/value exceptions must
        // route through err() -> exit 2, never escape as a stack trace + exit 1.
        assertEquals(2, Main.run(new String[] {"https://example.com", "-o"}));            // missing value
        assertEquals(2, Main.run(new String[] {"--size", "axb", "https://example.com"})); // WxH but non-numeric
        assertEquals(2, Main.run(new String[] {"https://example.com", "--settle", "nope"}));
        assertEquals(2, Main.run(new String[] {"https://example.com", "--wait-timeout", "nope"}));
        assertEquals(2, Main.run(new String[] {"--eval-file", "no-such-file.js", "https://example.com"}));
    }

    @Test
    void positiveParserNamesActuallyRejectZeroAndNegativeValues() throws Exception {
        assertEquals(2, Main.run(new String[] {"--size", "0x100", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--size", "100x-1", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--settle", "0", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--wait-timeout", "-1", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--mask", "0,0,0,1"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--fail-pixels", "-1"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--tolerance", "-1"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--tolerance", "255"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--fail-over", "100.0001"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--mask", "2147483647,0,1,1"}));

        // Zero is intentionally valid for non-negative diff thresholds and
        // coordinates; these parse and reach image IO (exit 1), not usage (2).
        assertEquals(1, Main.run(new String[] {
            "diff", "no-a.png", "no-b.png", "--mask", "0,0,1,1",
            "--fail-pixels", "0", "--tolerance", "0"}));
        assertEquals(1, Main.run(new String[] {
            "diff", "no-a.png", "no-b.png", "--fail-over", "100"}));
    }

    @Test
    void captureOutputAndManifestMustNotAlias(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("capture.png");
        Path normalizedAlias =
            directory.resolve("not-created").resolve("..").resolve("capture.png");
        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", output.toString(),
            "--json", normalizedAlias.toString()}));

        Path linkedOutput = directory.resolve("linked-capture.png");
        Path linkedManifest = directory.resolve("linked-manifest.json");
        Files.writeString(linkedOutput, "existing evidence");
        Files.createLink(linkedManifest, linkedOutput);
        org.junit.jupiter.api.Assertions.assertTrue(
            Files.isSameFile(linkedOutput, linkedManifest));

        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", linkedOutput.toString(),
            "--json", linkedManifest.toString()}));
        assertEquals("existing evidence", Files.readString(linkedOutput));
        assertEquals("existing evidence", Files.readString(linkedManifest));
    }

    @Test
    void absentCaseVariantCaptureOutputsRejectBeforeInputResolutionOrWrite(
            @TempDir Path directory) throws Exception {
        Path output = directory.resolve("Result.png");
        Path manifest = directory.resolve("result.png");
        Path missingInput = directory.resolve("missing-input.html");
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(manifest));

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(new String[] {
                missingInput.toString(), "-o", output.toString(),
                "--json", manifest.toString()});
        } finally {
            System.setErr(original);
        }

        assertEquals(2, code);
        org.junit.jupiter.api.Assertions.assertTrue(
            errors.toString().contains("must name different files"), errors.toString());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(manifest));
    }

    @Test
    void brokenManifestSymlinkCannotBecomeAnAliasAfterCaptureWrites(
            @TempDir Path directory) throws Exception {
        Path output = directory.resolve("future-capture.png");
        Path manifest = directory.resolve("future-manifest.json");
        try {
            Files.createSymbolicLink(manifest, output.getFileName());
        } catch (UnsupportedOperationException | SecurityException
                | java.io.IOException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links unavailable for this test: " + unavailable);
        }
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(manifest),
            "the setup link must be broken until the capture path is created");

        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", output.toString(),
            "--json", manifest.toString()}));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output),
            "alias rejection must happen before the capture can make the link valid");
        org.junit.jupiter.api.Assertions.assertTrue(Files.isSymbolicLink(manifest));
    }

    @Test
    void nonexistentOutputsUnderSymlinkedParentStillAlias(
            @TempDir Path directory) throws Exception {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path linkedDirectory = directory.resolve("linked");
        try {
            Files.createSymbolicLink(linkedDirectory, realDirectory.getFileName());
        } catch (UnsupportedOperationException | SecurityException
                | java.io.IOException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links unavailable for this test: " + unavailable);
        }
        Path output = realDirectory.resolve("future.png");
        Path manifest = linkedDirectory.resolve("future.png");

        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", output.toString(),
            "--json", manifest.toString()}));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
    }

    @Test
    void jpegExtensionsMatchCaseInsensitively() {
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isJpegOutput(java.nio.file.Path.of("x.jpg")));
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isJpegOutput(java.nio.file.Path.of("x.JPEG")));
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isJpegOutput(java.nio.file.Path.of("x.JpEg")));
        org.junit.jupiter.api.Assertions.assertFalse(
            Main.isJpegOutput(java.nio.file.Path.of("x.png")));
        org.junit.jupiter.api.Assertions.assertFalse(
            Main.isJpegOutput(java.nio.file.Path.of("jpg.png")));
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isPngOutput(java.nio.file.Path.of("x.PNG")));
    }

    @Test
    void jpegQualityIsBoundedAndOnlyAcceptedForJpegStills() throws Exception {
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "0", "-o", "out.jpg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "101", "-o", "out.jpeg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "80", "-o", "out.png", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "80", "-o", "out.pdf", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "80", "--gif", "2", "-o", "out.gif",
            "https://example.com"}));
    }

    @Test
    void unknownOutputAndMisnamedHeatmapExtensionsRefuseBeforeIo() throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int webp;
        try {
            System.setErr(new PrintStream(errors));
            webp = Main.run(new String[] {
                "-o", "out.webp", "https://example.com"});
        } finally {
            System.setErr(original);
        }
        assertEquals(2, webp);
        org.junit.jupiter.api.Assertions.assertTrue(
            errors.toString().contains("unsupported output extension"), errors.toString());
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--diff-out", "heat.bmp"}));
    }

    @Test
    void evalFileAndStdinCapsAreWiredIntoCli(@TempDir Path directory) throws Exception {
        Path oversizedEval = directory.resolve("large.js");
        Files.write(oversizedEval, new byte[Main.MAX_EVAL_FILE_BYTES + 1]);
        assertEquals(2, Main.run(new String[] {
            "--eval-file", oversizedEval.toString(), "https://example.com"}));

        byte[] oversizedHtml = new byte[Main.MAX_STDIN_HTML_BYTES + 1];
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(oversizedHtml));
            assertEquals(2, Main.run(new String[] {"-"}));
        } finally {
            System.setIn(original);
        }
    }

    // ---- --size auto (plan 982dcab9): every case here exits before Chrome ----------

    private static final String SVG_NS = "xmlns=\"http://www.w3.org/2000/svg\"";

    /** Run the CLI capturing stderr; returns {exit code, stderr}. */
    private static Object[] runCapturingStderr(String... args) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(args);
        } finally {
            System.setErr(original);
        }
        return new Object[] {code, errors.toString()};
    }

    @Test
    void helpDocumentsSizeAuto() throws Exception {
        Object[] result = runCapturingStderr("--help");
        String help = (String) result[1];
        assertEquals(0, result[0]);
        assertTrue(help.contains("[--size WxH|auto]") && help.contains("--size auto")
            && help.contains("viewBox") && help.contains("refuses (exit 2)"), help);
    }

    @Test
    void svgInputExtensionMatchesCaseInsensitively() {
        assertTrue(Main.isSvgInput("diagram.svg"));
        assertTrue(Main.isSvgInput("/tmp/Diagram.SVG"));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isSvgInput("diagram.svgz"));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isSvgInput("svg"));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isSvgInput("page.html"));
    }

    @Test
    void autoViewportComesFromTheSvgAndNamesItsSource(@TempDir Path directory)
            throws Exception {
        Path sized = directory.resolve("sized.svg");
        Files.writeString(sized, "<svg " + SVG_NS + " width=\"1000\" height=\"780\"/>");
        Path boxed = directory.resolve("BOXED.SVG");
        Files.writeString(boxed, "<svg " + SVG_NS + " viewBox=\"0 0 640 360\"/>");

        // Absent --size and an explicit --size auto resolve a sized SVG identically.
        for (boolean explicitAuto : new boolean[] {false, true}) {
            Main.Viewport fromAttributes =
                Main.resolveAutoViewport(explicitAuto, "file", sized.toString());
            assertEquals(1000, fromAttributes.width());
            assertEquals(780, fromAttributes.height());
            assertEquals("svg-width-height", fromAttributes.source());
            assertTrue(fromAttributes.notice().contains("viewport 1000x780")
                && fromAttributes.notice().contains(sized.toString()), fromAttributes.notice());

            Main.Viewport fromViewBox =
                Main.resolveAutoViewport(explicitAuto, "file", boxed.toString());
            assertEquals(640, fromViewBox.width());
            assertEquals(360, fromViewBox.height());
            assertEquals("svg-viewbox", fromViewBox.source());
        }
    }

    @Test
    void aStyleAttributeOnTheSvgRootIsNamedInTheViewportNotice(@TempDir Path directory)
            throws Exception {
        // Review brewshot 729 F3: the reported size may not be the drawn size when the root
        // carries a style attribute, so the notice says it was not read.
        Path styled = directory.resolve("styled.svg");
        Files.writeString(styled, "<svg " + SVG_NS
            + " width=\"100\" height=\"100\" style=\"width:800px;height:600px\"/>");
        Main.Viewport viewport = Main.resolveAutoViewport(false, "file", styled.toString());
        assertEquals(100, viewport.width());
        assertTrue(viewport.notice().contains("viewport 100x100")
            && viewport.notice().endsWith("; a style attribute is present and was not read"),
            viewport.notice());
    }

    @Test
    void nonSvgInputsKeepTheDefaultViewportUntouched(@TempDir Path directory)
            throws Exception {
        Path page = directory.resolve("page.html");
        // SVG-shaped CONTENT under a non-.svg name is still a page: the extension decides.
        Files.writeString(page, "<svg " + SVG_NS + " width=\"10\" height=\"10\"/>");
        org.junit.jupiter.api.Assertions.assertNull(
            Main.resolveAutoViewport(false, "file", page.toString()));
        org.junit.jupiter.api.Assertions.assertNull(
            Main.resolveAutoViewport(false, "url", "https://example.com/logo.svg"));
        org.junit.jupiter.api.Assertions.assertNull(
            Main.resolveAutoViewport(false, "stdin", "-"));
    }

    @Test
    void undeterminedSvgFallsBackLoudlyWithoutSizeButRefusesUnderSizeAuto(
            @TempDir Path directory) throws Exception {
        Path bare = directory.resolve("bare.svg");
        Files.writeString(bare, "<svg " + SVG_NS + "><rect width=\"5\" height=\"5\"/></svg>");
        Path relative = directory.resolve("relative.svg");
        Files.writeString(relative,
            "<svg " + SVG_NS + " width=\"50em\" height=\"30em\" viewBox=\"0 0 5 3\"/>");

        // No --size at all: the plan's fallback, never silent.
        Main.Viewport fallback = Main.resolveAutoViewport(false, "file", bare.toString());
        assertEquals(1280, fallback.width());
        assertEquals(900, fallback.height());
        assertEquals("default-fallback", fallback.source());
        assertTrue(fallback.notice().contains("SVG size not determined")
            && fallback.notice().contains("no absolute width or height")
            && fallback.notice().contains("using the default 1280x900 viewport")
            && fallback.notice().contains("--size WxH"), fallback.notice());
        assertTrue(Main.resolveAutoViewport(false, "file", relative.toString())
            .notice().contains("unit \"em\""));

        // --size auto: the caller asked for a size that cannot be delivered. Exit 2, named,
        // before Chrome and before any artifact.
        Path output = directory.resolve("never-written.png");
        Path receipt = directory.resolve("never-written.json");
        for (String[] args : java.util.List.of(
                new String[] {bare.toString(), "--size", "auto", "-o", output.toString(),
                    "--json", receipt.toString()},
                new String[] {"--size=auto", relative.toString(), "-o", output.toString(),
                    "--json", receipt.toString()})) {
            Object[] result = runCapturingStderr(args);
            String errors = (String) result[1];
            assertEquals(2, result[0], errors);
            assertTrue(errors.contains("--size auto cannot determine the size of")
                && errors.contains("pass --size WxH instead"), errors);
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(receipt));
        }
    }

    @Test
    void sizeAutoRefusesInputsThatAreNotALocalSvgBeforeStdinOrChrome(
            @TempDir Path directory) throws Exception {
        Path page = directory.resolve("page.html");
        Files.writeString(page, "<p>page</p>");
        Path output = directory.resolve("never-written.png");

        Object[] file = runCapturingStderr(
            page.toString(), "--size", "auto", "-o", output.toString());
        assertEquals(2, file[0], (String) file[1]);
        assertTrue(((String) file[1]).contains("--size auto reads the size from a local .svg file")
            && ((String) file[1]).contains("not an .svg file"), (String) file[1]);

        Object[] url = runCapturingStderr(
            "https://example.com/logo.svg", "--size", "auto", "-o", output.toString());
        assertEquals(2, url[0], (String) url[1]);
        assertTrue(((String) url[1]).contains("the input is a URL"), (String) url[1]);

        // stdin must be refused WITHOUT being read: a stream that fails on read proves it.
        InputStream originalIn = System.in;
        Object[] stdin;
        try {
            System.setIn(new InputStream() {
                @Override
                public int read() {
                    throw new AssertionError("--size auto must refuse before reading stdin");
                }
            });
            stdin = runCapturingStderr("-", "--size", "auto", "-o", output.toString());
        } finally {
            System.setIn(originalIn);
        }
        assertEquals(2, stdin[0], (String) stdin[1]);
        assertTrue(((String) stdin[1]).contains("the input is stdin"), (String) stdin[1]);
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
    }

    @Test
    void sizeSpellingsOtherThanWxHOrAutoStillRefuse() throws Exception {
        for (String bad : new String[] {"AUTO", "Auto", "autox", "auto,auto", "1000"}) {
            Object[] result = runCapturingStderr("--size", bad, "https://example.com");
            assertEquals(2, result[0], bad + ": " + result[1]);
        }
    }
}
