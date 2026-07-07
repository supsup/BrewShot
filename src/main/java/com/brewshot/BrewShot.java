package com.brewshot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BrewShot — Java brews screenshots. A self-contained Chrome DevTools Protocol
 * client over the JDK's built-in WebSocket: drives the locally installed
 * Chrome headless to open a URL or render direct HTML source, evaluate JS,
 * wait for page conditions, take full-page/clipped screenshots, record
 * looping GIFs, and read the page's console/errors. ZERO runtime dependencies
 * — pure JDK. (Provenance: extracted from the LatteX fx test harness; design
 * reviewed against playwright's chromium driver, whose screenshot/interaction
 * surface bottoms out in exactly these CDP messages: Page.navigate /
 * Page.setDocumentContent / Runtime.evaluate / Page.captureScreenshot.)
 *
 * <p>Single-threaded protocol handling: one command in flight at a time;
 * events are routed as they arrive (console/errors retained bounded, load
 * events awaitable, the rest dropped). That is all a harness needs.
 */
public final class BrewShot implements AutoCloseable {

    /** Library version — also printed by the CLI's {@code --version}. */
    public static final String VERSION = "0.3.0";

    private static final Pattern WS_LINE = Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final long DEFAULT_TIMEOUT_MS = 15_000;
    private static final int CONSOLE_CAP = 1_000;
    /** Poison message the listener enqueues on close/error so a blocked caller fails fast. */
    private static final String SOCKET_CLOSED = "{\"brewshotSocketClosed\":true}";

    /** One shared client for all launches — no selector-thread accumulation per launch. */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final Process chrome;
    private final Path profileDir;
    private final WebSocket ws;
    private final LinkedBlockingQueue<String> inbox;
    /** Only awaitable events are kept here (Page.loadEventFired) — bounded by design. */
    private final Deque<Map<String, Object>> pendingEvents = new ArrayDeque<>();
    /** Console messages + uncaught exceptions since the last open()/html(). Bounded. */
    private final List<String> consoleLog = new ArrayList<>();
    private final List<String> errorLog = new ArrayList<>();
    private final Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();
    private boolean captureConsole = true;
    private String sessionId; // null during browser-scope bootstrap, then the tab session
    private int nextId = 1;

    // ---- discovery ---------------------------------------------------------

    /** Locate a Chrome/Chromium binary, or null. Override with BREWSHOT_CHROME. */
    public static String findChrome() {
        String env = System.getenv("BREWSHOT_CHROME");
        if (env != null && Files.isExecutable(Path.of(env))) { return env; }
        String[] candidates = {
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
        };
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) { return c; }
        }
        return null;
    }

    /** True when a driveable Chrome exists — tests gate on this (assumeTrue). */
    public static boolean available() {
        return findChrome() != null;
    }

    // ---- lifecycle ---------------------------------------------------------

    private BrewShot(Process chrome, Path profileDir, WebSocket ws,
                     LinkedBlockingQueue<String> inbox) {
        this.chrome = chrome;
        this.profileDir = profileDir;
        this.ws = ws;
        this.inbox = inbox;
    }

    /** Launch with a sensible default viewport (1280x900). */
    public static BrewShot launch() throws IOException {
        return launch(1280, 900);
    }

    /** Launch headless Chrome with the given viewport and attach to a fresh tab. */
    public static BrewShot launch(int width, int height) throws IOException {
        String bin = findChrome();
        if (bin == null) { throw new IllegalStateException("no Chrome binary found"); }
        Path profile = Files.createTempDirectory("brewshot-");
        List<String> args = new ArrayList<>(List.of(
            bin,
            "--headless",
            "--disable-gpu",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--window-size=" + width + "," + height,
            "--remote-debugging-port=0",
            "--user-data-dir=" + profile,
            "--no-first-run",
            "--no-default-browser-check"));
        // Extra Chrome flags via env — the container hook (e.g. the Docker
        // image sets BREWSHOT_CHROME_ARGS=--no-sandbox: Chrome's sandbox needs
        // privileges containers don't grant by default). Space-separated.
        String extra = System.getenv("BREWSHOT_CHROME_ARGS");
        if (extra != null && !extra.isBlank()) {
            args.addAll(List.of(extra.trim().split("\\s+")));
        }
        args.add("about:blank");
        Process p = new ProcessBuilder(args)
            // stdout is never read — discard it so a chatty binary can't
            // deadlock on a full 64KB pipe.
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();

        // Everything after the process starts must clean up on failure, or a
        // headless Chrome + temp profile leaks per failed launch.
        try {
            String wsUrl = awaitDevtoolsUrl(p);
            LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
            WebSocket socket = HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new Accumulator(inbox))
                .join();
            BrewShot c = new BrewShot(p, profile, socket, inbox);
            // Browser-scope bootstrap (sessionId == null): open a tab, attach flat.
            Map<String, Object> created =
                c.command("Target.createTarget", "{\"url\":\"about:blank\"}");
            String targetId = (String) MiniJson.get(created, "targetId");
            Map<String, Object> attached = c.command("Target.attachToTarget",
                "{\"targetId\":\"" + targetId + "\",\"flatten\":true}");
            c.sessionId = (String) MiniJson.get(attached, "sessionId");
            c.command("Page.enable", "{}");
            c.command("Runtime.enable", "{}");
            return c;
        } catch (RuntimeException | IOException | Error e) {
            p.destroyForcibly();
            deleteRecursively(profile);
            throw e;
        }
    }

    /**
     * Read Chrome's stderr for the "DevTools listening on ws://..." line on a
     * helper thread, so the 15s deadline holds even when the process stays
     * alive without printing anything (a bare readLine would block forever).
     * The thread keeps draining stderr afterwards so Chrome never blocks on a
     * full pipe.
     */
    private static String awaitDevtoolsUrl(Process p) throws IOException {
        CompletableFuture<String> found = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (var err = new java.io.BufferedReader(new java.io.InputStreamReader(
                    p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    if (!found.isDone()) {
                        Matcher m = WS_LINE.matcher(line);
                        if (m.find()) { found.complete(m.group(1)); }
                    }
                }
                found.completeExceptionally(
                    new IOException("Chrome exited without a DevTools listening line"));
            } catch (IOException e) {
                found.completeExceptionally(e);
            }
        }, "brewshot-stderr");
        reader.setDaemon(true);
        reader.start();
        try {
            return found.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Chrome never printed a DevTools listening line within "
                + DEFAULT_TIMEOUT_MS + "ms");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(String.valueOf(e.getCause().getMessage()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for Chrome", e);
        }
    }

    // ---- protocol ----------------------------------------------------------

    /** Send one CDP command and block for its id-matched result. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> command(String method, String paramsJson) {
        int id = nextId++;
        StringBuilder msg = new StringBuilder(128)
            .append("{\"id\":").append(id)
            .append(",\"method\":\"").append(method).append('"')
            .append(",\"params\":").append(paramsJson);
        if (sessionId != null) {
            msg.append(",\"sessionId\":\"").append(sessionId).append('"');
        }
        msg.append('}');
        try {
            ws.sendText(msg, true).join();
        } catch (java.util.concurrent.CompletionException e) {
            throw new IllegalStateException(chromeDeathReason("sending " + method), e);
        }

        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while (true) {
            Map<String, Object> m = nextMessage(deadline, method);
            Object mid = m.get("id");
            if (mid instanceof Double d && d.intValue() == id) {
                if (m.containsKey("error")) {
                    throw new IllegalStateException(method + " failed: " + m.get("error"));
                }
                return (Map<String, Object>) m.getOrDefault("result", Map.of());
            }
            routeEvent(m);
        }
    }

    /** Block until a given CDP event (e.g. Page.loadEventFired) is seen. */
    private void waitEvent(String method, long timeoutMs) {
        for (Map<String, Object> e : pendingEvents) {
            if (method.equals(e.get("method"))) { pendingEvents.remove(e); return; }
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Map<String, Object> m = nextMessage(deadline, "event " + method);
            if (method.equals(m.get("method"))) { return; }
            routeEvent(m);
        }
    }

    /**
     * Route a non-matching message: console/exception events are retained
     * (bounded, switchable via {@link #captureConsole}) for {@link #console()}
     * / {@link #errors()}; load events stay awaitable; everything else is
     * dropped so a chatty page can't grow the buffers for the session's life.
     */
    private void routeEvent(Map<String, Object> m) {
        Object method = m.get("method");
        if (method == null) { return; }
        switch ((String) method) {
            case "Runtime.consoleAPICalled" -> {
                if (!captureConsole) { return; }
                String type = String.valueOf(MiniJson.get(m, "params.type"));
                String text = consoleArgsText(m);
                bounded(consoleLog, type + ": " + text);
                if ("error".equals(type)) { bounded(errorLog, "console.error: " + text); }
            }
            case "Runtime.exceptionThrown" -> {
                if (!captureConsole) { return; }
                Object desc = MiniJson.get(m,
                    "params.exceptionDetails.exception.description");
                if (desc == null) { desc = MiniJson.get(m, "params.exceptionDetails.text"); }
                bounded(errorLog, "uncaught: " + desc);
            }
            case "Page.loadEventFired" -> pendingEvents.add(m);
            default -> { /* drop: nothing awaits it, nothing reads it */ }
        }
    }

    @SuppressWarnings("unchecked")
    private static String consoleArgsText(Map<String, Object> m) {
        Object args = MiniJson.get(m, "params.args");
        if (!(args instanceof List<?> l)) { return ""; }
        StringBuilder b = new StringBuilder();
        for (Object a : l) {
            if (b.length() > 0) { b.append(' '); }
            Object v = ((Map<String, Object>) a).get("value");
            if (v == null) { v = ((Map<String, Object>) a).get("description"); }
            // JSON numbers parse as Double; print integral ones the way the
            // page wrote them (42, not 42.0).
            if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
                b.append((long) (double) d);
            } else {
                b.append(v);
            }
        }
        return b.toString();
    }

    private static void bounded(List<String> log, String entry) {
        if (log.size() < CONSOLE_CAP) { log.add(entry); }
        else if (log.size() == CONSOLE_CAP) { log.add("... (capped at " + CONSOLE_CAP + ")"); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nextMessage(long deadlineMillis, String waitingFor) {
        try {
            long wait = deadlineMillis - System.currentTimeMillis();
            String raw = wait > 0 ? inbox.poll(wait, TimeUnit.MILLISECONDS) : null;
            if (raw == null) {
                // Distinguish a dead Chrome from a merely slow page.
                if (!chrome.isAlive()) {
                    throw new IllegalStateException("Chrome exited (code "
                        + chrome.exitValue() + ") while " + waitingFor);
                }
                throw new IllegalStateException("CDP timeout waiting for " + waitingFor);
            }
            if (SOCKET_CLOSED.equals(raw)) {
                throw new IllegalStateException(chromeDeathReason(waitingFor));
            }
            return (Map<String, Object>) MiniJson.parse(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** A caller-facing reason that distinguishes a dead Chrome from a closed socket. */
    private String chromeDeathReason(String doing) {
        if (!chrome.isAlive()) {
            return "Chrome exited (code " + chrome.exitValue() + ") while " + doing;
        }
        return "DevTools socket closed while " + doing;
    }

    // ---- the harness surface ----------------------------------------------

    /**
     * Open an address (http/https/file URL) and block until load fires.
     * Fails fast with Chrome's own error (net::ERR_CONNECTION_REFUSED,
     * ERR_NAME_NOT_RESOLVED, ...) instead of timing out.
     */
    public void open(String url) {
        freshNavigation();
        Map<String, Object> r = command("Page.navigate",
            "{\"url\":\"" + MiniJson.esc(url) + "\"}");
        Object err = r.get("errorText");
        if (err != null && !String.valueOf(err).isEmpty()) {
            throw new IllegalStateException("navigation to " + url + " failed: " + err);
        }
        waitEvent("Page.loadEventFired", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Render DIRECT HTML SOURCE — no server, no temp file. Replaces the main
     * frame's document (CDP Page.setDocumentContent, document.write semantics:
     * inline scripts execute, load fires — and is consumed here, so a stale
     * load event can never satisfy a later {@link #open}).
     */
    public void html(String source) {
        freshNavigation();
        Map<String, Object> tree = command("Page.getFrameTree", "{}");
        String frameId = (String) MiniJson.get(tree, "frameTree.frame.id");
        command("Page.setDocumentContent",
            "{\"frameId\":\"" + frameId + "\",\"html\":\"" + MiniJson.esc(source) + "\"}");
        waitEvent("Page.loadEventFired", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Start a navigation from a clean slate: drop any stale load event (a
     * buffered one from a previous page must never satisfy THIS navigation —
     * that is how a screenshot harness silently shoots the wrong page) and
     * reset the console/error capture to "since this page".
     */
    private void freshNavigation() {
        drainInboxNonBlocking();
        pendingEvents.removeIf(e -> "Page.loadEventFired".equals(e.get("method")));
        consoleLog.clear();
        errorLog.clear();
    }

    /**
     * Poll a JS predicate until it's truthy — the deterministic alternative to
     * {@link #settle} guesses: {@code shot.waitFor("document.querySelector('.done')", 5000)}.
     * Fails loud with the predicate text on timeout.
     */
    public void waitFor(String jsPredicate, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Object v = eval("!!(" + jsPredicate + ")");
            if (Boolean.TRUE.equals(v)) { return; }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "waitFor timed out after " + timeoutMs + "ms: " + jsPredicate);
            }
            settle(60);
        }
    }

    /**
     * Send an extra HTTP header on every request — e.g. basic/bearer auth:
     * {@code shot.header("Authorization", "Basic dXNlcjpwYXNz")}. Call before
     * {@link #open}; cumulative across calls (last value per name wins).
     * NOTE: applies to EVERY request the page makes, including cross-origin
     * subresources — for credentials scoped to one host, prefer
     * {@link #cookie} (the browser applies its own same-domain rules).
     */
    public void header(String name, String value) {
        extraHeaders.put(name, value);
        command("Network.enable", "{}");
        StringBuilder json = new StringBuilder("{\"headers\":{");
        boolean first = true;
        for (var e : extraHeaders.entrySet()) {
            if (!first) { json.append(','); }
            first = false;
            json.append('"').append(MiniJson.esc(e.getKey())).append("\":\"")
                .append(MiniJson.esc(e.getValue())).append('"');
        }
        json.append("}}");
        command("Network.setExtraHTTPHeaders", json.toString());
    }

    /**
     * Set a cookie before {@link #open} — session-auth shape:
     * {@code shot.cookie("SESSION", token, "localhost")}. Applies to every
     * path on the domain.
     */
    public void cookie(String name, String value, String domain) {
        Map<String, Object> r = command("Network.setCookie",
            "{\"name\":\"" + MiniJson.esc(name)
                + "\",\"value\":\"" + MiniJson.esc(value)
                + "\",\"domain\":\"" + MiniJson.esc(domain)
                + "\",\"path\":\"/\"}");
        if (Boolean.FALSE.equals(r.get("success"))) {
            throw new IllegalStateException("cookie rejected: " + name + " @ " + domain);
        }
    }

    /** Toggle console/error capture (default ON; bounded either way). */
    public void captureConsole(boolean on) {
        this.captureConsole = on;
        if (!on) {
            consoleLog.clear();
            errorLog.clear();
        }
    }

    /**
     * Console messages emitted since the last {@link #open}/{@link #html}
     * ("log: hi", "error: boom", ...). Bounded at 1000 entries.
     */
    public List<String> console() {
        drainInboxNonBlocking();
        return List.copyOf(consoleLog);
    }

    /**
     * Uncaught page exceptions + console.error entries since the last
     * navigation — the one-line health assertion:
     * {@code assertEquals(List.of(), shot.errors())}.
     */
    public List<String> errors() {
        drainInboxNonBlocking();
        return List.copyOf(errorLog);
    }

    /** Pull any already-arrived messages through the router without blocking. */
    private void drainInboxNonBlocking() {
        String raw;
        while ((raw = inbox.poll()) != null) {
            if (SOCKET_CLOSED.equals(raw)) { return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) MiniJson.parse(raw);
            if (m.get("id") == null) { routeEvent(m); }
        }
    }

    /**
     * Evaluate a JS expression in the page; returns the JSON-serializable value
     * (String / Double / Boolean / Map / List / null). Promises are awaited.
     * Throws with the page-side description on an uncaught exception.
     */
    public Object eval(String expression) {
        Map<String, Object> r = command("Runtime.evaluate",
            "{\"expression\":\"" + MiniJson.esc(expression)
                + "\",\"returnByValue\":true,\"awaitPromise\":true}");
        Object ex = r.get("exceptionDetails");
        if (ex != null) {
            Object desc = MiniJson.get(r, "exceptionDetails.exception.description");
            throw new IllegalStateException("page JS threw: " + (desc != null ? desc : ex));
        }
        return MiniJson.get(r, "result.value");
    }

    /** Sleep helper for settle waits between eval steps. */
    public void settle(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Full-page PNG (beyond the viewport), written to the given path. */
    public void screenshot(Path out) throws IOException {
        Map<String, Object> r = command("Page.captureScreenshot",
            "{\"format\":\"png\",\"captureBeyondViewport\":true}");
        String b64 = (String) r.get("data");
        Files.write(out, Base64.getDecoder().decode(b64));
    }

    /**
     * Clipped PNG of a page-coordinate rectangle (e.g. one card), as bytes —
     * the frame primitive for animation capture (GIF assembly).
     */
    public byte[] screenshotClip(double x, double y, double width, double height) {
        return screenshotClip(x, y, width, height, 1.0);
    }

    /**
     * Clipped PNG with OUTPUT SCALING — Chrome renders the rect at
     * {@code scale} (0.5 = quarter the pixels), which is what makes full-page
     * animation capture practical: the downscale is free, in-browser, and
     * native-image-clean (no AWT).
     */
    public byte[] screenshotClip(double x, double y, double width, double height, double scale) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || !Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("non-finite/invalid clip: "
                + x + "," + y + " " + width + "x" + height + " @" + scale);
        }
        Map<String, Object> r = command("Page.captureScreenshot",
            "{\"format\":\"png\",\"captureBeyondViewport\":true,\"clip\":{"
                + "\"x\":" + x + ",\"y\":" + y
                + ",\"width\":" + width + ",\"height\":" + height
                + ",\"scale\":" + scale + "}}");
        return Base64.getDecoder().decode((String) r.get("data"));
    }

    /**
     * Record a page-coordinate rectangle as a looping GIF: {@code frames}
     * clipped shots at {@code frameDelayMs} cadence, assembled via the JDK's
     * ImageIO (no dependency). Trigger your animation first (eval/open), then
     * call this while it runs. (Playback delay is stamped as frameDelayMs;
     * actual capture cadence adds shot time, so fast animations play back
     * slightly faster than real time.)
     */
    public void recordGif(double x, double y, double width, double height,
                          int frames, int frameDelayMs, Path out) throws IOException {
        List<byte[]> shots = new ArrayList<>(frames);
        for (int i = 0; i < frames; i++) {
            shots.add(screenshotClip(x, y, width, height));
            settle(frameDelayMs);
        }
        GifWriter.write(shots, frameDelayMs, out);
    }

    /**
     * Record the WHOLE PAGE as a looping GIF — every viewport, not just the
     * first. {@code scale} keeps the file sane (0.35-0.5 is usually right:
     * a 6000px-tall page at 0.4 ≈ readable thumbnails, tolerable bytes).
     */
    public void recordGifFullPage(int frames, int frameDelayMs, double scale, Path out)
            throws IOException {
        double w = ((Double) eval("document.documentElement.scrollWidth")).doubleValue();
        double h = ((Double) eval("document.documentElement.scrollHeight")).doubleValue();
        List<byte[]> shots = new ArrayList<>(frames);
        for (int i = 0; i < frames; i++) {
            shots.add(screenshotClip(0, 0, w, h, scale));
            settle(frameDelayMs);
        }
        GifWriter.write(shots, frameDelayMs, out);
    }

    /**
     * Assemble already-captured PNG frames (from {@link #screenshotClip}) into
     * a looping GIF — for callers that need the frames in hand first (e.g.
     * asserting animation liveness before committing the artifact).
     */
    public static void gif(List<byte[]> pngFrames, int frameDelayMs, Path out)
            throws IOException {
        GifWriter.write(pngFrames, frameDelayMs, out);
    }

    @Override
    public void close() {
        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
        catch (Exception ignored) { /* already closing */ }
        chrome.destroy();
        try {
            if (!chrome.waitFor(3, TimeUnit.SECONDS)) { chrome.destroyForcibly(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chrome.destroyForcibly();
        }
        deleteRecursively(profileDir);
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * WebSocket listener reassembling partial text frames into whole messages.
     * On close/error it enqueues a poison message so a blocked caller fails
     * fast ("Chrome exited") instead of sleeping out the full timeout.
     */
    private static final class Accumulator implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> sink;
        private final StringBuilder buf = new StringBuilder();

        Accumulator(LinkedBlockingQueue<String> sink) { this.sink = sink; }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                sink.add(buf.toString());
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sink.add(SOCKET_CLOSED);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            sink.add(SOCKET_CLOSED);
        }
    }
}
