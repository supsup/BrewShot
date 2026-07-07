package com.brewshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BrewShot — Java brews screenshots. A self-contained Chrome DevTools Protocol
 * client over the JDK's built-in WebSocket: drives the locally installed
 * Chrome headless to open a URL or render direct HTML source, evaluate JS,
 * take full-page/clipped screenshots, and record looping GIFs. ZERO runtime
 * dependencies — pure JDK. (Provenance: extracted from the LatteX fx test
 * harness; design reviewed against playwright's chromium driver, whose
 * screenshot/interaction surface bottoms out in exactly these CDP messages:
 * Page.navigate / Page.setDocumentContent / Runtime.evaluate /
 * Page.captureScreenshot.)
 *
 * <p>Single-threaded protocol handling: one command in flight at a time;
 * events that arrive while a command waits are buffered for {@link #waitEvent}.
 * That is all a harness needs.
 */
public final class BrewShot implements AutoCloseable {

    private static final Pattern WS_LINE = Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final long DEFAULT_TIMEOUT_MS = 15_000;

    private final Process chrome;
    private final Path profileDir;
    private final WebSocket ws;
    private final LinkedBlockingQueue<String> inbox;
    private final Deque<Map<String, Object>> pendingEvents = new ArrayDeque<>();
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

    /** Launch headless Chrome with the given viewport and attach to a fresh tab. */
    public static BrewShot launch(int width, int height) throws IOException {
        String bin = findChrome();
        if (bin == null) { throw new IllegalStateException("no Chrome binary found"); }
        Path profile = Files.createTempDirectory("brewshot-");
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
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
        // Extra Chrome flags via env — the container hook (e.g. a Docker image
        // sets BREWSHOT_CHROME_ARGS=--no-sandbox: Chrome's sandbox needs
        // privileges containers don't grant by default). Space-separated.
        String extra = System.getenv("BREWSHOT_CHROME_ARGS");
        if (extra != null && !extra.isBlank()) {
            args.addAll(java.util.List.of(extra.trim().split("\\s+")));
        }
        args.add("about:blank");
        Process p = new ProcessBuilder(args).start();

        String wsUrl = awaitDevtoolsUrl(p);
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
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
    }

    private static String awaitDevtoolsUrl(Process p) throws IOException {
        BufferedReader err = new BufferedReader(
            new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        String line;
        while ((line = err.readLine()) != null) {
            Matcher m = WS_LINE.matcher(line);
            if (m.find()) {
                // Keep draining stderr so Chrome never blocks on a full pipe.
                BufferedReader keep = err;
                Thread drain = new Thread(() -> {
                    try { while (keep.readLine() != null) { /* discard */ } }
                    catch (IOException ignored) { /* process exiting */ }
                }, "cdp-stderr-drain");
                drain.setDaemon(true);
                drain.start();
                return m.group(1);
            }
            if (System.currentTimeMillis() > deadline) { break; }
        }
        p.destroyForcibly();
        throw new IOException("Chrome never printed a DevTools listening line");
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
        ws.sendText(msg, true).join();

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
            if (m.containsKey("method")) { pendingEvents.add(m); }
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
            if (m.containsKey("method")) { pendingEvents.add(m); }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nextMessage(long deadlineMillis, String waitingFor) {
        try {
            long wait = deadlineMillis - System.currentTimeMillis();
            String raw = wait > 0 ? inbox.poll(wait, TimeUnit.MILLISECONDS) : null;
            if (raw == null) {
                throw new IllegalStateException("CDP timeout waiting for " + waitingFor);
            }
            return (Map<String, Object>) MiniJson.parse(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // ---- the harness surface ----------------------------------------------

    /** Open an address (http/https/file URL) and block until load fires. */
    public void open(String url) {
        command("Page.navigate", "{\"url\":\"" + MiniJson.esc(url) + "\"}");
        waitEvent("Page.loadEventFired", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Render DIRECT HTML SOURCE — no server, no temp file. Replaces the main
     * frame's document (CDP Page.setDocumentContent, document.write semantics:
     * inline scripts execute, load fires).
     */
    public void html(String source) {
        Map<String, Object> tree = command("Page.getFrameTree", "{}");
        String frameId = (String) MiniJson.get(tree, "frameTree.frame.id");
        command("Page.setDocumentContent",
            "{\"frameId\":\"" + frameId + "\",\"html\":\"" + MiniJson.esc(source) + "\"}");
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

    private final java.util.Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();

    /**
     * Send an extra HTTP header on every request — e.g. basic/bearer auth:
     * {@code shot.header("Authorization", "Basic dXNlcjpwYXNz")}. Call before
     * {@link #open}; cumulative across calls (last value per name wins).
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
        Map<String, Object> r = command("Page.captureScreenshot",
            "{\"format\":\"png\",\"captureBeyondViewport\":true,\"clip\":{"
                + "\"x\":" + x + ",\"y\":" + y
                + ",\"width\":" + width + ",\"height\":" + height
                + ",\"scale\":1}}");
        return Base64.getDecoder().decode((String) r.get("data"));
    }

    /**
     * Record a page-coordinate rectangle as a looping GIF: {@code frames}
     * clipped shots at {@code frameDelayMs} cadence, assembled via the JDK's
     * ImageIO (no dependency). Trigger your animation first (eval/open), then
     * call this while it runs.
     */
    public void recordGif(double x, double y, double width, double height,
                          int frames, int frameDelayMs, Path out) throws IOException {
        java.util.List<byte[]> shots = new java.util.ArrayList<>(frames);
        for (int i = 0; i < frames; i++) {
            shots.add(screenshotClip(x, y, width, height));
            settle(frameDelayMs);
        }
        GifWriter.write(shots, frameDelayMs, out);
    }

    /**
     * Assemble already-captured PNG frames (from {@link #screenshotClip}) into
     * a looping GIF — for callers that need the frames in hand first (e.g.
     * asserting animation liveness before committing the artifact).
     */
    public static void gif(java.util.List<byte[]> pngFrames, int frameDelayMs, Path out)
            throws IOException {
        GifWriter.write(pngFrames, frameDelayMs, out);
    }

    /** Launch with a sensible default viewport (1280x900). */
    public static BrewShot launch() throws IOException {
        return launch(1280, 900);
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
        // best-effort temp profile cleanup
        try (var walk = Files.walk(profileDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /** WebSocket listener reassembling partial text frames into whole messages. */
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
    }
}
