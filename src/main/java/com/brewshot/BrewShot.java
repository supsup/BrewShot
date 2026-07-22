package com.brewshot;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;
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
    // Single source of truth for --version and the --json manifest. MUST move with every
    // release cut — it sat at 0.3.0 through the 0.4.x/0.5.x releases, so --version lied
    // about vendored-jar provenance (caught by Fixpoint, sirentide #121).
    public static final String VERSION = "0.9.0";

    private static final Pattern WS_LINE = Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final long DEFAULT_TIMEOUT_MS = 15_000;
    private static final int CONSOLE_CAP = 1_000;
    /** Poison message the listener enqueues on close/error so a blocked caller fails fast. */
    private static final String SOCKET_CLOSED = "{\"brewshotSocketClosed\":true}";

    /** One shared client for all launches — no selector-thread accumulation per launch. */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // Live instances, so ONE JVM-wide shutdown hook can force-clean any that never reached
    // close(). On SIGINT/SIGTERM the JVM runs hooks but does NOT unwind stacks, so a
    // try-with-resources/close() around a BrewShot never fires — Ctrl+C mid-capture is
    // precisely the case close() misses, and Java never reaps a child process on exit while
    // headless Chrome doesn't watch its parent, so the child + its brewshot-* temp profile
    // would leak. The hook is force-clean (destroyForcibly + delete), not the polite close():
    // a hook must be fast and the websocket may be wedged. SCOPE: this covers SIGINT/SIGTERM
    // and normal exit, NOT SIGKILL or a hard JVM crash (no hook runs then) — leaks are
    // reduced, not eliminated.
    private static final java.util.Set<BrewShot> LIVE =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (BrewShot b : LIVE) {
                // Kill-then-WAIT-then-delete: the ordering is load-bearing (F1-r2). The Chrome
                // Helper children are the late writers — destroyForcibly() returns immediately
                // and signals only the parent, so deleting while the tree is still flushing its
                // shutdown state loses the delete race and the profile dir survives. Descendants
                // first, then the parent, then a bounded reap (SIGKILL'd processes die in ms;
                // the bound only guards a wedged kernel), THEN delete — with one retry as the
                // belt for a helper that outlived the parent's wait.
                try {
                    b.chrome.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                    b.chrome.destroyForcibly();
                    b.chrome.waitFor(2, TimeUnit.SECONDS);
                } catch (RuntimeException ignored) {
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                deleteRecursively(b.profileDir);
                if (java.nio.file.Files.exists(b.profileDir)) {
                    deleteRecursively(b.profileDir);
                }
            }
        }, "brewshot-shutdown-cleanup"));
    }

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
    // Emulated media state (plan 02af3a3d) — null means "no override, whatever the browser
    // would naturally report." Applied immediately when a setter is called AND re-sent from
    // freshNavigation() before every open()/html(), so a caller who sets these before OR after
    // launch gets them in effect for every capture, not just the first.
    private String emulatedColorScheme;   // "dark" | "light" | "no-preference" | null
    private String emulatedMediaType;     // "print" | "screen" | null
    private String emulatedReducedMotion; // "reduce" | "no-preference" | null
    private String sessionId; // null during browser-scope bootstrap, then the tab session
    private int nextId = 1;
    // Network in-flight tracking for waitForNetworkIdle. A SET of live CDP
    // requestIds, not a counter: CDP reuses one requestId across a redirect
    // chain — a fresh requestWillBeSent (carrying redirectResponse) per hop but
    // only ONE terminal loadingFinished/loadingFailed. A counter would leak +N
    // per N-hop redirect and never reach idle (brewshot #82); adding the same id
    // is idempotent and remove-on-terminal is robust to duplicate/out-of-order
    // events. Mutated only in routeEvent on the single draining thread, so a
    // plain HashSet is safe. Cleared per navigation.
    private final Set<String> inFlightRequestIds = new HashSet<>();
    private long lastNetChangeMs;
    // Load/navigation wait budget (ms). Defaults from BREWSHOT_TIMEOUT_MS or the
    // 15s constant; override per-instance with navTimeout(). Governs open()/html()
    // and the ready-waits, so a slow page on a loaded CI runner isn't unraisable.
    private long navTimeoutMs = envTimeoutMs();
    // Per-CDP-CALL wait budget (ms) — a DIFFERENT axis from navTimeoutMs above. A single
    // full-page Page.captureScreenshot on a tall document can legitimately exceed the 15s
    // default, and before this it threw a spurious CDP timeout that no caller could raise.
    // Kept separate deliberately: collapsing it into navTimeoutMs would make one knob mean
    // two things, so a caller wanting a longer screenshot budget would also be loosening
    // every navigation wait. Same default source and setter shape as navTimeout, though —
    // one PATTERN, two values.
    private long commandTimeoutMs = envCommandTimeoutMs();
    // Recording heap budget (bytes of accumulated PNG frames). The recorders hold every frame
    // in memory until the GIF is encoded, so an unbounded recording is an OOM waiting for a
    // long enough page: 30fps of full-page PNGs reaches a gigabyte in well under a minute.
    // A bound that STOPS the recording is the honest failure — the alternative is dying with
    // an OutOfMemoryError that names nothing the caller can act on. Overridable per-instance
    // (recordingHeapBudget) or via BREWSHOT_MAX_RECORDING_BYTES.
    static final long DEFAULT_MAX_RECORDING_BYTES = 256L * 1024 * 1024;
    private long maxRecordingBytes = envMaxRecordingBytes();

    private static long envMaxRecordingBytes() {
        String v = System.getenv("BREWSHOT_MAX_RECORDING_BYTES");
        if (v != null) {
            try { long b = Long.parseLong(v.trim()); if (b > 0) { return b; } }
            catch (NumberFormatException ignored) { /* fall through to the default */ }
        }
        return DEFAULT_MAX_RECORDING_BYTES;
    }

    /// Accumulates captured frames against the heap budget. Returns false once the budget is
    /// spent, so a recorder STOPS and writes what it has instead of growing until the JVM dies.
    /// Truncation is announced on stderr and never silent: a short GIF that pretends to be the
    /// whole recording is the same class of quiet lie as a test that stops testing.
    private final class FrameBudget {
        private final List<byte[]> frames = new ArrayList<>();
        private long bytes;
        private boolean truncated;

        boolean add(byte[] frame) {
            // The FIRST frame is always kept — the budget governs GROWTH, and a
            // 1-frame GIF that announces its truncation beats an empty-output
            // error that names nothing (review brewshot 109: a sub-one-frame
            // budget used to surface as GifWriter's bare "no frames").
            if (!frames.isEmpty() && bytes + frame.length > maxRecordingBytes) {
                if (!truncated) {
                    truncated = true;
                    System.err.println("brewshot: recording stopped at " + frames.size()
                        + " frames / " + bytes + " bytes — heap budget "
                        + maxRecordingBytes + " reached. The GIF holds what was captured up to"
                        + " this point. Raise it with BREWSHOT_MAX_RECORDING_BYTES or"
                        + " recordingHeapBudget(), or record a shorter window / smaller maxWidth.");
                }
                return false;
            }
            frames.add(frame);
            bytes += frame.length;
            if (frames.size() == 1 && bytes > maxRecordingBytes && !truncated) {
                truncated = true;
                System.err.println("brewshot: recording stopped at 1 frame / " + bytes
                    + " bytes — a single frame already exceeds the heap budget "
                    + maxRecordingBytes + ". The GIF holds that one frame. Raise the budget or"
                    + " reduce the capture size/scale.");
            }
            return bytes <= maxRecordingBytes;
        }

        List<byte[]> frames() { return frames; }
        boolean truncated() { return truncated; }
        int size() { return frames.size(); }
    }

    private static long envCommandTimeoutMs() {
        String v = System.getenv("BREWSHOT_COMMAND_TIMEOUT_MS");
        if (v != null) {
            try { long ms = Long.parseLong(v.trim()); if (ms > 0) { return ms; } }
            catch (NumberFormatException ignored) { /* fall through to the shared default */ }
        }
        return envTimeoutMs();
    }

    private static long envTimeoutMs() {
        String v = System.getenv("BREWSHOT_TIMEOUT_MS");
        if (v != null) {
            try { long ms = Long.parseLong(v.trim()); if (ms > 0) { return ms; } }
            catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return DEFAULT_TIMEOUT_MS;
    }

    // ---- discovery ---------------------------------------------------------

    /**
     * Executable base names to look for on {@code PATH}, in preference order:
     * Chrome/Chromium first, then Edge (all Chromium-based, all driveable over
     * CDP). On Windows each is also tried with a {@code .exe} suffix.
     */
    static final String[] PATH_NAMES = {
        "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
        "chrome", "msedge", "microsoft-edge",
    };

    /**
     * Locate a Chrome/Chromium/Edge binary, or null. Precedence:
     * {@code BREWSHOT_CHROME} env override, then a scan of every {@code PATH}
     * entry for a known executable name, then common absolute install
     * locations (macOS / Linux / Windows). Override with BREWSHOT_CHROME.
     */
    public static String findChrome() {
        return findChrome(System.getenv(), isWindows());
    }

    /** True on Windows — gates the {@code .exe}-suffix PATH probe. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .startsWith("windows");
    }

    /**
     * Pure discovery over an injected environment (testable seam): reads
     * {@code BREWSHOT_CHROME}, {@code PATH}, and the Windows {@code ProgramFiles*}
     * / {@code LocalAppData} vars from {@code env} only — no {@code System.getenv}
     * — and does nothing but filesystem {@link Files#isExecutable} probes.
     */
    static String findChrome(Map<String, String> env, boolean windows) {
        String override = env.get("BREWSHOT_CHROME");
        if (override != null && Files.isExecutable(Path.of(override))) { return override; }
        String onPath = scanPath(env.get("PATH"), windows);
        if (onPath != null) { return onPath; }
        for (String c : knownLocations(env)) {
            if (Files.isExecutable(Path.of(c))) { return c; }
        }
        return null;
    }

    /**
     * Scan each entry of a {@code PATH} string (split on {@link File#pathSeparator})
     * for the first executable {@link #PATH_NAMES} match, or null. On Windows the
     * {@code .exe} suffix is tried too. Pure: PATH string in, path (or null) out.
     */
    static String scanPath(String path, boolean windows) {
        if (path == null || path.isEmpty()) { return null; }
        for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isEmpty()) { continue; }
            for (String name : PATH_NAMES) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) { return candidate.toString(); }
                if (windows) {
                    Path exe = Path.of(dir, name + ".exe");
                    if (Files.isExecutable(exe)) { return exe.toString(); }
                }
            }
        }
        return null;
    }

    /**
     * Common absolute install locations across macOS, Linux, and Windows. The
     * Windows {@code C:\...} strings are inert on other OSes (only probed via
     * {@link Files#isExecutable}, never resolved), and the {@code %ProgramFiles%}
     * / {@code %LocalAppData%} forms come from the injected {@code env} so they
     * work under any locale/drive.
     */
    private static List<String> knownLocations(Map<String, String> env) {
        List<String> l = new ArrayList<>();
        // macOS
        l.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        l.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
        l.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        // Linux
        l.add("/usr/bin/google-chrome");
        l.add("/usr/bin/google-chrome-stable");
        l.add("/usr/bin/chromium");
        l.add("/usr/bin/chromium-browser");
        l.add("/usr/bin/microsoft-edge");
        // Windows — fixed default install roots
        l.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        l.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
        l.add("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe");
        l.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        // Windows — env-based (locale-/drive-independent)
        addUnder(l, env.get("ProgramFiles"), "\\Google\\Chrome\\Application\\chrome.exe");
        addUnder(l, env.get("ProgramFiles(x86)"), "\\Google\\Chrome\\Application\\chrome.exe");
        addUnder(l, env.get("ProgramFiles(x86)"), "\\Microsoft\\Edge\\Application\\msedge.exe");
        addUnder(l, env.get("LocalAppData"), "\\Google\\Chrome\\Application\\chrome.exe");
        return l;
    }

    private static void addUnder(List<String> l, String base, String suffix) {
        if (base != null && !base.isBlank()) { l.add(base + suffix); }
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
        LIVE.add(this); // deregistered in close(); force-cleaned by the shutdown hook otherwise
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
            c.command("Network.enable", "{}"); // in-flight tracking for waitForNetworkIdle
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

    /** Send one CDP command WITHOUT waiting for its result (the id-only result
     *  message is later ignored by {@link #routeEvent}); for high-frequency
     *  protocol chatter like screencast frame acks where blocking would drop
     *  interleaved events. */
    private void fireAndForget(String method, String paramsJson) {
        int id = nextId++;
        StringBuilder msg = new StringBuilder(96)
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
    }

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

        long deadline = System.currentTimeMillis() + commandTimeoutMs;
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
            case "Network.requestWillBeSent" -> {
                // A redirect hop reuses the requestId (idempotent add); only a
                // genuinely new request grows the set. Either way it is activity.
                Object rid = MiniJson.get(m, "params.requestId");
                if (rid != null) { inFlightRequestIds.add(String.valueOf(rid)); }
                lastNetChangeMs = System.currentTimeMillis();
            }
            case "Network.loadingFinished", "Network.loadingFailed" -> {
                Object rid = MiniJson.get(m, "params.requestId");
                if (rid != null) { inFlightRequestIds.remove(String.valueOf(rid)); }
                lastNetChangeMs = System.currentTimeMillis();
            }
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
        waitEvent("Page.loadEventFired", navTimeoutMs);
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
        waitEvent("Page.loadEventFired", navTimeoutMs);
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
        inFlightRequestIds.clear();
        lastNetChangeMs = System.currentTimeMillis();
        // Re-send any active colorScheme/media/reducedMotion override BEFORE the navigation
        // command below, so the new document paints under it from the first frame — a no-op
        // when none was ever set (plan 02af3a3d: emulation must survive "any new page/navigation
        // the harness opens", not just the page open() was first called on).
        applyEmulatedMedia();
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

    /**
     * Force the page's {@code prefers-color-scheme} media feature — {@code "dark"},
     * {@code "light"}, or {@code "no-preference"} — via CDP {@code Emulation.setEmulatedMedia},
     * so a dark-mode-only stylesheet (or a light-only one) renders without an OS-level toggle.
     * Applied immediately (works whether called before or after {@link #open}/{@link #html}),
     * and re-sent on every subsequent navigation — see {@link #applyEmulatedMedia()}. Chainable
     * like {@link #navTimeout}/{@link #commandTimeout}/{@link #recordingHeapBudget}.
     */
    public BrewShot colorScheme(String scheme) {
        if (!"dark".equals(scheme) && !"light".equals(scheme) && !"no-preference".equals(scheme)) {
            throw new IllegalArgumentException(
                "colorScheme wants dark|light|no-preference, got: " + scheme);
        }
        this.emulatedColorScheme = scheme;
        applyEmulatedMedia();
        return this;
    }

    /**
     * Force the page's emulated media TYPE — {@code "print"} or {@code "screen"} — via CDP
     * {@code Emulation.setEmulatedMedia}, so {@code @media print} rules render (or a page that
     * hides content under {@code @media print} stays hidden) without an actual print dialog.
     * Same application/chaining contract as {@link #colorScheme}.
     */
    public BrewShot media(String type) {
        if (!"print".equals(type) && !"screen".equals(type)) {
            throw new IllegalArgumentException("media wants print|screen, got: " + type);
        }
        this.emulatedMediaType = type;
        applyEmulatedMedia();
        return this;
    }

    /**
     * Force the page's {@code prefers-reduced-motion} media feature — {@code "reduce"} or
     * {@code "no-preference"} — via CDP {@code Emulation.setEmulatedMedia}, so a CSS-guarded
     * animation ({@code @media (prefers-reduced-motion: reduce) { animation: none !important }})
     * is deterministically stilled for a stable capture. Same application/chaining contract as
     * {@link #colorScheme}.
     */
    public BrewShot reducedMotion(String preference) {
        if (!"reduce".equals(preference) && !"no-preference".equals(preference)) {
            throw new IllegalArgumentException(
                "reducedMotion wants reduce|no-preference, got: " + preference);
        }
        this.emulatedReducedMotion = preference;
        applyEmulatedMedia();
        return this;
    }

    /**
     * Send {@code Emulation.setEmulatedMedia} with whatever combination of
     * {@link #colorScheme}/{@link #media}/{@link #reducedMotion} has been set so far. A no-op
     * when none of the three has ever been called (nothing to override — never sends a command
     * that would reset an unrelated caller's state). Called both from each setter (immediate
     * effect) and from {@link #freshNavigation} (re-applied before every {@link #open}/
     * {@link #html}, since a fresh page is exactly the case this must not silently drop).
     */
    private void applyEmulatedMedia() {
        if (emulatedColorScheme == null && emulatedMediaType == null
                && emulatedReducedMotion == null) {
            return;
        }
        List<String> features = new ArrayList<>();
        if (emulatedColorScheme != null) {
            features.add("{\"name\":\"prefers-color-scheme\",\"value\":\""
                + emulatedColorScheme + "\"}");
        }
        if (emulatedReducedMotion != null) {
            features.add("{\"name\":\"prefers-reduced-motion\",\"value\":\""
                + emulatedReducedMotion + "\"}");
        }
        command("Emulation.setEmulatedMedia",
            "{\"media\":\"" + (emulatedMediaType != null ? emulatedMediaType : "")
                + "\",\"features\":[" + String.join(",", features) + "]}");
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

    /**
     * Set the load/navigation wait budget (ms) for {@link #open}/{@link #html}
     * and the ready-waits. Also settable via {@code BREWSHOT_TIMEOUT_MS}. A slow
     * dashboard on a loaded CI runner that needs &gt;15s is no longer unraisable.
     */
    public BrewShot navTimeout(long millis) {
        if (millis > 0) { this.navTimeoutMs = millis; }
        return this;
    }

    /**
     * Set the per-CDP-call wait budget (ms) — how long any single DevTools command may take
     * before it is treated as a timeout. Also settable via {@code BREWSHOT_COMMAND_TIMEOUT_MS},
     * falling back to {@code BREWSHOT_TIMEOUT_MS} and then the 15s default.
     *
     * <p>Distinct from {@link #navTimeout}: that governs how long a PAGE may take to load,
     * this governs how long one CDP round-trip may take. A full-page screenshot of a tall
     * document is the motivating case — it can exceed 15s on a slow runner while navigation
     * itself was fast, and raising the navigation budget would not have helped it.
     */
    public BrewShot commandTimeout(long millis) {
        if (millis > 0) { this.commandTimeoutMs = millis; }
        return this;
    }

    /**
     * Set the recording heap budget (bytes of accumulated PNG frames) for the GIF recorders.
     * Also settable via {@code BREWSHOT_MAX_RECORDING_BYTES}; defaults to
     * {@value #DEFAULT_MAX_RECORDING_BYTES} bytes.
     *
     * <p>The recorders hold every frame in memory until the GIF is encoded, so without a bound
     * a long enough recording is an OutOfMemoryError. On reaching the budget the recording
     * STOPS, writes the frames captured so far, and says so on stderr — a truncated GIF that
     * announces itself beats both an OOM and a silently short one.
     */
    public BrewShot recordingHeapBudget(long bytes) {
        if (bytes > 0) { this.maxRecordingBytes = bytes; }
        return this;
    }

    /**
     * Wait until no network request has been in flight for {@code quietMillis},
     * or {@code timeoutMillis} elapses (best-effort — a convenience wait, not a
     * hard gate). {@link #open}/{@link #html} return on {@code loadEventFired},
     * which fires BEFORE async XHR/fetch settle; this bridges that gap. Network
     * is tracked from launch and the in-flight count resets per navigation.
     */
    public void waitForNetworkIdle(long quietMillis, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            drainInboxNonBlocking();
            long now = System.currentTimeMillis();
            if (inFlightRequestIds.isEmpty() && now - lastNetChangeMs >= quietMillis) { return; }
            try { Thread.sleep(15); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * Wait until webfonts have finished loading ({@code document.fonts.ready}),
     * so captured text is the real face rather than a FOUT fallback. No-op on a
     * page without the Font Loading API.
     */
    public void waitForFontsReady() {
        eval("(document.fonts ? document.fonts.ready.then(function () { return true; }) : true)");
    }

    /**
     * Deterministic readiness: network-idle (500&nbsp;ms quiet) then fonts-ready.
     * The render-settled wait to prefer over a blind {@link #settle} for CI /
     * unattended shots and for stable visual diffs — it removes the FOUT/decode/
     * late-XHR race that makes a fixed sleep flaky.
     */
    public void waitReady() {
        waitForNetworkIdle(500, navTimeoutMs);
        waitForFontsReady();
    }

    /** Sleep helper for settle waits between eval steps. */
    public void settle(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Output image encoding for {@link #screenshot}/{@link #screenshotClip}.
     * PNG is lossless (the default everywhere); JPEG is lossy but far smaller
     * for photographic/gradient-heavy pages, and takes a {@code quality} knob.
     */
    public enum ImageFormat { PNG, JPEG }

    /**
     * The CDP {@code Page.captureScreenshot} format+quality JSON fragment for a
     * format selector — {@code "format":"png"} or {@code "format":"jpeg","quality":N}.
     * Fails loud with {@link IllegalArgumentException} when a JPEG quality is
     * outside 1-100 (CDP's valid range). Package-private so the validation is
     * unit-testable without a browser.
     */
    static String captureFormatParams(ImageFormat fmt, int quality) {
        if (fmt == ImageFormat.JPEG) {
            if (quality < 1 || quality > 100) {
                throw new IllegalArgumentException(
                    "jpeg quality must be 1-100, got " + quality);
            }
            return "\"format\":\"jpeg\",\"quality\":" + quality;
        }
        return "\"format\":\"png\"";
    }

    /** Full-page PNG (beyond the viewport), written to the given path. */
    public void screenshot(Path out) throws IOException {
        screenshot(out, ImageFormat.PNG, 0);
    }

    /**
     * Full-page screenshot in the given {@link ImageFormat}, written to the
     * given path. {@code quality} (1-100) applies to JPEG only and is ignored
     * for PNG; an out-of-range JPEG quality throws {@link IllegalArgumentException}.
     * The PNG default of {@link #screenshot(Path)} is unchanged.
     */
    public void screenshot(Path out, ImageFormat fmt, int quality) throws IOException {
        Map<String, Object> r = command("Page.captureScreenshot",
            "{" + captureFormatParams(fmt, quality) + ",\"captureBeyondViewport\":true}");
        String b64 = (String) r.get("data");
        Files.write(out, Base64.getDecoder().decode(b64));
    }

    /**
     * Options for {@link #pdf(Path, PdfOptions)} — paper size and margins in
     * INCHES (CDP's {@code Page.printToPDF} unit), plus background/scale/orientation.
     * {@link #defaults()} is a US-Letter, full-bleed (zero-margin), backgrounds-on
     * capture — the "this is what the page looks like, as a print artifact" default,
     * not the browser's own print defaults (which drop backgrounds and add margins).
     * Withers give ergonomic one-off tweaks; presets cover the common paper sizes.
     */
    public record PdfOptions(boolean landscape, boolean printBackground, double scale,
                             double paperWidthIn, double paperHeightIn,
                             double marginTopIn, double marginRightIn,
                             double marginBottomIn, double marginLeftIn) {
        /** US Letter, portrait, zero-margin, backgrounds on, scale 1.0. */
        public static PdfOptions defaults() {
            return new PdfOptions(false, true, 1.0, 8.5, 11.0, 0, 0, 0, 0);
        }

        /** A4 (8.27 × 11.69 in), otherwise like {@link #defaults()}. */
        public static PdfOptions a4() {
            return defaults().paper(8.27, 11.69);
        }

        public PdfOptions landscape(boolean v) {
            return new PdfOptions(v, printBackground, scale, paperWidthIn, paperHeightIn,
                marginTopIn, marginRightIn, marginBottomIn, marginLeftIn);
        }

        public PdfOptions printBackground(boolean v) {
            return new PdfOptions(landscape, v, scale, paperWidthIn, paperHeightIn,
                marginTopIn, marginRightIn, marginBottomIn, marginLeftIn);
        }

        public PdfOptions scale(double v) {
            return new PdfOptions(landscape, printBackground, v, paperWidthIn, paperHeightIn,
                marginTopIn, marginRightIn, marginBottomIn, marginLeftIn);
        }

        public PdfOptions paper(double widthIn, double heightIn) {
            return new PdfOptions(landscape, printBackground, scale, widthIn, heightIn,
                marginTopIn, marginRightIn, marginBottomIn, marginLeftIn);
        }

        /** Uniform margin on all four sides. */
        public PdfOptions margin(double inches) {
            return new PdfOptions(landscape, printBackground, scale, paperWidthIn, paperHeightIn,
                inches, inches, inches, inches);
        }
    }

    /**
     * The CDP {@code Page.printToPDF} parameter fragment for {@code opts}. Validates
     * the numeric envelope CDP accepts — positive paper dimensions, non-negative
     * margins, and a scale in CDP's 0.1–2.0 range — failing loud with
     * {@link IllegalArgumentException} rather than emitting a request Chrome would
     * reject opaquely. Package-private so the validation is unit-testable without a
     * browser (mirrors {@link #captureFormatParams(ImageFormat, int)}).
     */
    static String printPdfParams(PdfOptions o) {
        if (o.paperWidthIn() <= 0 || o.paperHeightIn() <= 0) {
            throw new IllegalArgumentException(
                "pdf paper size must be positive inches, got "
                    + o.paperWidthIn() + "x" + o.paperHeightIn());
        }
        if (o.marginTopIn() < 0 || o.marginRightIn() < 0
                || o.marginBottomIn() < 0 || o.marginLeftIn() < 0) {
            throw new IllegalArgumentException("pdf margins must be non-negative inches");
        }
        if (o.scale() < 0.1 || o.scale() > 2.0) {
            throw new IllegalArgumentException("pdf scale must be 0.1-2.0, got " + o.scale());
        }
        return "\"landscape\":" + o.landscape()
            + ",\"printBackground\":" + o.printBackground()
            + ",\"scale\":" + o.scale()
            + ",\"paperWidth\":" + o.paperWidthIn()
            + ",\"paperHeight\":" + o.paperHeightIn()
            + ",\"marginTop\":" + o.marginTopIn()
            + ",\"marginRight\":" + o.marginRightIn()
            + ",\"marginBottom\":" + o.marginBottomIn()
            + ",\"marginLeft\":" + o.marginLeftIn();
    }

    /** The page as a PDF at {@link PdfOptions#defaults()}, written to {@code out}. */
    public void pdf(Path out) throws IOException {
        pdf(out, PdfOptions.defaults());
    }

    /**
     * The page as a print-fidelity PDF via CDP {@code Page.printToPDF}, written to
     * {@code out}. Unlike GIF, this rides no ImageIO/AWT — {@code printToPDF} returns
     * base64 PDF bytes — so it works on the native binary too. {@code opts} controls
     * paper size, margins, background, scale, and orientation.
     */
    public void pdf(Path out, PdfOptions opts) throws IOException {
        Map<String, Object> r = command("Page.printToPDF", "{" + printPdfParams(opts) + "}");
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
        return screenshotClip(x, y, width, height, scale, ImageFormat.PNG, 0);
    }

    /**
     * Clipped screenshot with OUTPUT SCALING and an explicit {@link ImageFormat}
     * — the JPEG-capable form of {@link #screenshotClip(double, double, double,
     * double, double)}. {@code quality} (1-100) applies to JPEG only (ignored for
     * PNG); an out-of-range JPEG quality throws {@link IllegalArgumentException}.
     * The GIF recorders keep using the PNG path (GifWriter assembles PNG frames).
     */
    public byte[] screenshotClip(double x, double y, double width, double height,
                                 double scale, ImageFormat fmt, int quality) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || !Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("non-finite/invalid clip: "
                + x + "," + y + " " + width + "x" + height + " @" + scale);
        }
        Map<String, Object> r = command("Page.captureScreenshot",
            "{" + captureFormatParams(fmt, quality)
                + ",\"captureBeyondViewport\":true,\"clip\":{"
                + "\"x\":" + x + ",\"y\":" + y
                + ",\"width\":" + width + ",\"height\":" + height
                + ",\"scale\":" + scale + "}}");
        return Base64.getDecoder().decode((String) r.get("data"));
    }

    /**
     * Record a page-coordinate rectangle as a looping GIF: {@code frames}
     * clipped shots at {@code frameDelayMs} cadence, assembled via the JDK's
     * ImageIO (no dependency). Trigger your animation first (eval/open), then
     * call this while it runs. Capture cadence == playback cadence here (≈
     * real time); to play back slower/faster than you sample, use the
     * {@code captureDelayMs, playbackDelayMs} overload below.
     */
    public void recordGif(double x, double y, double width, double height,
                          int frames, int frameDelayMs, Path out) throws IOException {
        recordGif(x, y, width, height, frames, frameDelayMs, frameDelayMs, out);
    }

    /**
     * Record a rectangle as a looping GIF with SEPARATE capture and playback
     * cadence — the two are independent knobs:
     * <ul>
     *   <li>{@code captureDelayMs} — how long to wait between shots. This is the
     *       real-time SAMPLING rate: smaller = denser sampling of a fast
     *       animation (more detail, but Chrome's shot time floors it ≈20-30ms).</li>
     *   <li>{@code playbackDelayMs} — the per-frame display duration stamped into
     *       the GIF: this is the SPEED. {@code playbackDelayMs > captureDelayMs}
     *       plays it back in slow motion; {@code <} speeds it up; {@code ==} is
     *       ≈ real time. FPS = {@code 1000 / playbackDelayMs}.</li>
     * </ul>
     * Sample a fast effect densely and replay it readably:
     * {@code recordGif(..., 60, 25, 75, out)} — 60 shots ~25ms apart, played at
     * 75ms/frame (≈13fps slow-mo). See the README "GIF playback speed" table.
     */
    public void recordGif(double x, double y, double width, double height,
                          int frames, int captureDelayMs, int playbackDelayMs, Path out)
            throws IOException {
        recordGif(x, y, width, height, frames, captureDelayMs, playbackDelayMs, NO_HOOK, out);
    }

    /** The no-op frame hook the hookless recorders ride — one shared loop. */
    private static final IntConsumer NO_HOOK = i -> { };

    /**
     * Record a rectangle as a looping GIF, driving the page BETWEEN frames:
     * {@code beforeFrame} is invoked with the frame index (0-based) immediately
     * before that frame is captured — trigger the animation at {@code i == 0},
     * advance deterministic state ({@code shot.eval("step()")}), or perturb
     * mid-recording ({@code shot.click(...)}, {@code shot.hover(...)}). The hook
     * runs on the recording thread against this instance (single-threaded
     * protocol — interact freely); an exception it throws aborts the recording.
     */
    public void recordGif(double x, double y, double width, double height,
                          int frames, int captureDelayMs, int playbackDelayMs,
                          IntConsumer beforeFrame, Path out) throws IOException {
        // The frame COUNT bounds the loop but not the BYTES — full-page frames at a
        // large count are the same OOM the screencast recorder guards against, so
        // both recorder families ride the ONE FrameBudget (the write-side twin the
        // screencast-only fix would have left open).
        FrameBudget budget = new FrameBudget();
        for (int i = 0; i < frames; i++) {
            beforeFrame.accept(i);
            if (!budget.add(screenshotClip(x, y, width, height))) {
                break; // heap budget spent — encode what we have (announced on stderr)
            }
            settle(captureDelayMs);
        }
        GifWriter.write(budget.frames(), playbackDelayMs, out);
    }

    /**
     * Page-coordinate bounding box of the FIRST element matching
     * {@code cssSelector}, as <code>{x, y, width, height}</code> in document
     * coordinates (scroll offset already folded in, so it feeds
     * {@link #screenshotClip}/{@link #recordGif} directly). Throws if nothing
     * matches. The building block for element-targeted capture.
     */
    public double[] elementBox(String cssSelector) {
        String sel = "'" + cssSelector.replace("\\", "\\\\").replace("'", "\\'") + "'";
        Object v = eval("(function(){var e=document.querySelector(" + sel + ");"
            + "if(!e)return 'none';var r=e.getBoundingClientRect();"
            + "return [r.left+window.scrollX,r.top+window.scrollY,r.width,r.height].join(',');})()");
        if (!(v instanceof String s) || s.equals("none")) {
            throw new IllegalArgumentException("no element matches selector: " + cssSelector);
        }
        String[] p = s.split(",");
        return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                             Double.parseDouble(p[2]), Double.parseDouble(p[3])};
    }

    // ---- input dispatch ----------------------------------------------------

    /**
     * Move the mouse to a DOCUMENT coordinate — the same coordinate space as
     * {@link #elementBox}/{@link #screenshotClip}, so the capture and input
     * surfaces compose ({@code mouse(box[0]+box[2]/2, box[1]+box[3]/2)}). The
     * scroll offset is subtracted internally because CDP dispatches input in
     * viewport coordinates. This is a REAL trusted browser event: mousemove/
     * mouseover handlers fire and {@code :hover} styles engage — nothing a
     * page-side synthetic event can fake.
     */
    public void mouse(double x, double y) {
        double[] v = viewportPoint(x, y);
        dispatchMouse("mouseMoved", v[0], v[1], "none", 0);
    }

    /**
     * Click (left button, single) at a DOCUMENT coordinate: move, press,
     * release — the sequence real users produce, so hover-then-click handlers
     * and {@code event.isTrusted} checks behave as in a real session.
     */
    public void click(double x, double y) {
        double[] v = viewportPoint(x, y);
        dispatchMouse("mouseMoved", v[0], v[1], "none", 0);
        dispatchMouse("mousePressed", v[0], v[1], "left", 1);
        dispatchMouse("mouseReleased", v[0], v[1], "left", 1);
    }

    /** Click the FIRST element matching {@code cssSelector} — scrolls it into
     *  view first (Puppeteer semantics: {@code click(css)} means "click the
     *  element", wherever it currently is), then dispatches at its visible
     *  center. Throws if nothing matches. Below-fold elements HIT (B1
     *  fold-blocker, brewshot 75) — they are never silently missed. */
    public void click(String cssSelector) {
        double[] v = visibleCenter(cssSelector);
        dispatchMouse("mouseMoved", v[0], v[1], "none", 0);
        dispatchMouse("mousePressed", v[0], v[1], "left", 1);
        dispatchMouse("mouseReleased", v[0], v[1], "left", 1);
    }

    /**
     * Hover the FIRST element matching {@code cssSelector}: scrolled into view
     * first, then the mouse MOVES to its visible center and STAYS — subsequent
     * captures see the hovered state ({@code :hover} styles, tooltips, JS
     * mouseenter effects). Pair with the per-frame recording hook to film
     * hover-triggered animations.
     */
    public void hover(String cssSelector) {
        double[] v = visibleCenter(cssSelector);
        dispatchMouse("mouseMoved", v[0], v[1], "none", 0);
    }

    /**
     * Scroll the selector's element into view (centered) and return its
     * post-scroll VIEWPORT center, clamped into the viewport for elements
     * larger than it — one atomic eval, so the rect can't race a scrolling
     * page. scrollIntoView is layout-synchronous: the rect read after it in
     * the same eval is already post-scroll.
     */
    private double[] visibleCenter(String cssSelector) {
        String sel = "'" + cssSelector.replace("\\", "\\\\").replace("'", "\\'") + "'";
        Object v = eval("(function(){var e=document.querySelector(" + sel + ");"
            + "if(!e)return 'none';e.scrollIntoView({block:'center',inline:'center'});"
            + "var r=e.getBoundingClientRect();"
            + "var cx=Math.min(Math.max(r.left+r.width/2,Math.max(r.left,0)+1),"
            + "Math.min(r.right,window.innerWidth)-1);"
            + "var cy=Math.min(Math.max(r.top+r.height/2,Math.max(r.top,0)+1),"
            + "Math.min(r.bottom,window.innerHeight)-1);"
            + "return [cx,cy].join(',');})()");
        if (!(v instanceof String s) || s.equals("none")) {
            throw new IllegalArgumentException("no element matches selector: " + cssSelector);
        }
        String[] p = s.split(",");
        return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
    }

    /** Document → viewport coordinates (CDP input wants viewport CSS px).
     *  FAIL-LOUD when the mapped point lands outside the viewport (B1
     *  fold-blocker, brewshot 75): a click dispatched into nowhere is a silent
     *  no-op the caller cannot detect — raw-coordinate callers must scroll
     *  first (or use the selector form, which auto-scrolls). Scroll offsets
     *  and viewport bounds are read in ONE eval, atomic against a scrolling
     *  page. */
    private double[] viewportPoint(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("non-finite input point: " + x + "," + y);
        }
        Object v = eval("[window.scrollX,window.scrollY,window.innerWidth,window.innerHeight].join(',')");
        String[] p = String.valueOf(v).split(",");
        double sx = Double.parseDouble(p[0]);
        double sy = Double.parseDouble(p[1]);
        double vw = Double.parseDouble(p[2]);
        double vh = Double.parseDouble(p[3]);
        double vx = x - sx;
        double vy = y - sy;
        if (vx < 0 || vy < 0 || vx >= vw || vy >= vh) {
            throw new IllegalArgumentException("document point " + x + "," + y
                + " maps outside the viewport (viewport " + vx + "," + vy + " in " + vw + "x" + vh
                + " at scroll " + sx + "," + sy + ") — the event would silently miss;"
                + " scroll the page first, or use the selector form (auto-scrolls into view)");
        }
        return new double[] {vx, vy};
    }

    /** One CDP Input.dispatchMouseEvent — the single seam all input rides. */
    private void dispatchMouse(String type, double vx, double vy, String button, int clickCount) {
        command("Input.dispatchMouseEvent",
            "{\"type\":\"" + type + "\",\"x\":" + vx + ",\"y\":" + vy
                + ",\"button\":\"" + button + "\",\"clickCount\":" + clickCount + "}");
    }

    /** Clipped PNG of the element matching {@code cssSelector} — the
     *  selector-based {@link #screenshotClip}. */
    public byte[] screenshotElement(String cssSelector, double scale) {
        return screenshotElement(cssSelector, scale, 0);
    }

    /**
     * Clipped PNG of the element matching {@code cssSelector} with
     * {@code paddingPx} of breathing room inflated around its box (CSS px,
     * pre-scale) — capture mechanics so consumers stop wrapping elements in
     * padding divs just to avoid a tight crop. The rect is clamped at the
     * page's top-left; padding past the right/bottom page edge renders as
     * background (Chrome's clip semantics), which is the honest behavior for
     * an element flush against the edge.
     */
    public byte[] screenshotElement(String cssSelector, double scale, double paddingPx) {
        if (!Double.isFinite(paddingPx) || paddingPx < 0) {
            throw new IllegalArgumentException("invalid paddingPx: " + paddingPx);
        }
        double[] b = elementBox(cssSelector);
        return screenshotClip(Math.max(0, b[0] - paddingPx), Math.max(0, b[1] - paddingPx),
            b[2] + 2 * paddingPx, b[3] + 2 * paddingPx, scale);
    }

    /**
     * Record the element matching {@code cssSelector} as a looping GIF — the
     * selector-based {@link #recordGif}. Resolves the element's box ONCE, then
     * films that fixed region so an animation moving <em>within</em> the element
     * (glyph jitter, a spinner) is captured cleanly. Trigger your animation
     * first (open/eval), then call this while it runs.
     */
    public void recordGifElement(String cssSelector, int frames, int frameDelayMs,
                                 double scale, Path out) throws IOException {
        recordGifElement(cssSelector, frames, frameDelayMs, frameDelayMs, scale, out);
    }

    /**
     * Record one element as a GIF with SEPARATE capture and playback cadence —
     * see {@link #recordGif(double, double, double, double, int, int, int, Path)}
     * for the two-knob model. Sample a fast effect densely, play it back
     * readably: {@code recordGifElement(".fx", 60, 25, 75, 1.3, out)}.
     */
    public void recordGifElement(String cssSelector, int frames, int captureDelayMs,
                                 int playbackDelayMs, double scale, Path out) throws IOException {
        recordGifElement(cssSelector, frames, captureDelayMs, playbackDelayMs,
                         playbackDelayMs, scale, out);
    }

    /**
     * Record one element as a GIF that HOLDS its first frame for
     * {@code firstFrameDelayMs} before the animation runs — so the viewer sees the
     * opening state (an intact equation, a button at rest) for a beat, then it
     * plays at {@code playbackDelayMs}. Capture the animation the same way as
     * {@link #recordGifElement(String, int, int, int, double, Path)}. Set
     * {@code firstFrameDelayMs == playbackDelayMs} for no hold.
     */
    public void recordGifElement(String cssSelector, int frames, int captureDelayMs,
                                 int playbackDelayMs, int firstFrameDelayMs,
                                 double scale, Path out) throws IOException {
        recordGifElement(cssSelector, frames, captureDelayMs, playbackDelayMs,
                         firstFrameDelayMs, scale, NO_HOOK, out);
    }

    /**
     * Record one element as a GIF, driving the page BETWEEN frames — the
     * element-targeted sibling of the
     * {@link #recordGif(double, double, double, double, int, int, int, IntConsumer, Path)}
     * hook overload (same hook contract: 0-based index, invoked before each
     * capture, exceptions abort). The element's box is resolved ONCE, before
     * the first hook call, so a hook that moves things around cannot shift the
     * filmed region mid-recording.
     */
    public void recordGifElement(String cssSelector, int frames, int captureDelayMs,
                                 int playbackDelayMs, double scale,
                                 IntConsumer beforeFrame, Path out) throws IOException {
        recordGifElement(cssSelector, frames, captureDelayMs, playbackDelayMs,
                         playbackDelayMs, scale, beforeFrame, out);
    }

    /**
     * The full-knob element recorder: per-frame hook AND a first-frame hold
     * (film the intact opening state for a beat, then let the hook drive) —
     * see {@link #recordGifElement(String, int, int, int, int, double, Path)}
     * for the hold semantics and the hook overloads above for the hook contract.
     */
    public void recordGifElement(String cssSelector, int frames, int captureDelayMs,
                                 int playbackDelayMs, int firstFrameDelayMs, double scale,
                                 IntConsumer beforeFrame, Path out) throws IOException {
        double[] b = elementBox(cssSelector);
        // brewshot 109: EVERY accumulating recorder rides the one FrameBudget.
        FrameBudget budget = new FrameBudget();
        for (int i = 0; i < frames; i++) {
            beforeFrame.accept(i);
            if (!budget.add(screenshotClip(b[0], b[1], b[2], b[3], scale))) {
                break;
            }
            settle(captureDelayMs);
        }
        GifWriter.write(budget.frames(), playbackDelayMs, firstFrameDelayMs, out);
    }

    /**
     * Record a SCROLL-PAN down a tall page as a looping GIF — the camera glides
     * from the top of the document to the bottom, one viewport-height window per
     * frame, with smoothstep ease-in/out so it accelerates and settles rather
     * than lurching. Unlike {@link #recordGifFullPage} (which re-shoots the whole
     * page each frame), this pans a fixed-height window DOWN the document, so a
     * long static page becomes a smooth guided tour. {@code holdFrames} pauses at
     * the top and bottom so the loop reads. {@code scale} downsizes for byte sanity.
     * The launch viewport height is the window height.
     */
    public void recordGifScroll(int panFrames, int holdFrames, int playbackDelayMs,
                                double scale, Path out) throws IOException {
        double w = ((Number) eval("document.documentElement.scrollWidth")).doubleValue();
        double h = ((Number) eval("document.documentElement.scrollHeight")).doubleValue();
        double vh = ((Number) eval("window.innerHeight")).doubleValue();
        double maxY = Math.max(0, h - vh);
        FrameBudget budget = new FrameBudget();
        record: {
            for (int i = 0; i < holdFrames; i++) {
                if (!budget.add(screenshotClip(0, 0, w, vh, scale))) break record;
            }
            for (int i = 0; i < panFrames; i++) {
                double t = panFrames <= 1 ? 1 : i / (double) (panFrames - 1);
                double eased = t * t * (3 - 2 * t);
                if (!budget.add(screenshotClip(0, eased * maxY, w, vh, scale))) break record;
            }
            for (int i = 0; i < holdFrames; i++) {
                if (!budget.add(screenshotClip(0, maxY, w, vh, scale))) break record;
            }
        }
        GifWriter.write(budget.frames(), playbackDelayMs, out);
    }

    /**
     * Record the WHOLE PAGE as a looping GIF — every viewport, not just the
     * first. {@code scale} keeps the file sane (0.35-0.5 is usually right:
     * a 6000px-tall page at 0.4 ≈ readable thumbnails, tolerable bytes).
     */
    public void recordGifFullPage(int frames, int frameDelayMs, double scale, Path out)
            throws IOException {
        double w = ((Number) eval("document.documentElement.scrollWidth")).doubleValue();
        double h = ((Number) eval("document.documentElement.scrollHeight")).doubleValue();
        FrameBudget budget = new FrameBudget();
        for (int i = 0; i < frames; i++) {
            if (!budget.add(screenshotClip(0, 0, w, h, scale))) {
                break;
            }
            settle(frameDelayMs);
        }
        GifWriter.write(budget.frames(), frameDelayMs, out);
    }

    /**
     * Screenshot a FRACTIONAL REGION of the document — "the top half" is
     * {@code region(0, 0.5, scale)}, "the middle" {@code region(0.25, 0.75, ...)},
     * "the bottom third" {@code region(2.0/3, 1, ...)}. Fractions of total
     * document height; returns PNG bytes.
     */
    public byte[] screenshotRegion(double fromFraction, double toFraction, double scale) {
        checkFractions(fromFraction, toFraction);
        double w = ((Number) eval("document.documentElement.scrollWidth")).doubleValue();
        double h = ((Number) eval("document.documentElement.scrollHeight")).doubleValue();
        return screenshotClip(0, h * fromFraction, w, h * (toFraction - fromFraction), scale);
    }

    /**
     * Record a FRACTIONAL REGION of the document as a looping GIF — the
     * region-targeted sibling of {@link #recordGifFullPage}: e.g.
     * {@code recordGifRegion(0.5, 1.0, 30, 120, 0.6, out)} films the bottom half.
     */
    public void recordGifRegion(double fromFraction, double toFraction,
                                int frames, int frameDelayMs, double scale, Path out)
            throws IOException {
        checkFractions(fromFraction, toFraction);
        double w = ((Number) eval("document.documentElement.scrollWidth")).doubleValue();
        double h = ((Number) eval("document.documentElement.scrollHeight")).doubleValue();
        double y = h * fromFraction;
        double regionH = h * (toFraction - fromFraction);
        FrameBudget budget = new FrameBudget();
        for (int i = 0; i < frames; i++) {
            if (!budget.add(screenshotClip(0, y, w, regionH, scale))) {
                break;
            }
            settle(frameDelayMs);
        }
        GifWriter.write(budget.frames(), frameDelayMs, out);
    }

    private static void checkFractions(double from, double to) {
        if (!(from >= 0 && to <= 1 && from < to)) {
            throw new IllegalArgumentException(
                "region fractions want 0 <= from < to <= 1, got " + from + ".." + to);
        }
    }

    /** Streamed viewport GIF at the compositor's own pace; see the full overload. */
    public int recordGifStream(int durationMs, int playbackDelayMs, Path out) throws IOException {
        return recordGifStream(durationMs, playbackDelayMs, playbackDelayMs, 0, out);
    }

    /**
     * Record the VIEWPORT as a looping GIF from a CDP screencast STREAM
     * ({@code Page.startScreencast} → {@code Page.screencastFrame} events)
     * instead of polling {@code Page.captureScreenshot}. Chrome pushes a frame
     * whenever the compositor produces one, so a fast animation samples at the
     * pace it actually rendered — denser and smoother than the poll recorders,
     * whose per-shot cost floors the capture cadence at ≈20-30ms.
     * <ul>
     *   <li>{@code durationMs} — how long to keep the stream open (real time).</li>
     *   <li>{@code playbackDelayMs} / {@code firstFrameDelayMs} — the same
     *       playback knobs as {@link #recordGif}: per-frame display duration
     *       stamped into the GIF, with the poster-frame hold.</li>
     *   <li>{@code maxWidth} — downscale bound in device px ({@code 0} keeps the
     *       viewport's natural size). Chrome preserves aspect ratio.</li>
     * </ul>
     * Returns the number of frames captured. Screencast frames are
     * VIEWPORT-ONLY (no clip, no beyond-viewport capture) — scroll the subject
     * into view first, or stay with the poll recorders for element/region
     * targeting. A page that never composites during the window produces no
     * frames, which throws rather than writing an empty GIF: a static page is
     * a caller bug (there was nothing to film), not a quiet success.
     */
    public int recordGifStream(int durationMs, int playbackDelayMs, int firstFrameDelayMs,
                               int maxWidth, Path out) throws IOException {
        if (durationMs <= 0 || playbackDelayMs <= 0 || firstFrameDelayMs <= 0 || maxWidth < 0) {
            throw new IllegalArgumentException("recordGifStream wants durationMs/delays > 0"
                + " and maxWidth >= 0, got " + durationMs + "/" + playbackDelayMs + "/"
                + firstFrameDelayMs + "/" + maxWidth);
        }
        FrameBudget budget = new FrameBudget();
        command("Page.startScreencast", maxWidth > 0
            ? "{\"format\":\"png\",\"everyNthFrame\":1,\"maxWidth\":" + maxWidth + "}"
            : "{\"format\":\"png\",\"everyNthFrame\":1}");
        try {
            long deadline = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> m;
                try {
                    m = nextMessage(deadline, "Page.screencastFrame");
                } catch (IllegalStateException quietWindow) {
                    // A deadline lapse on a live Chrome just means the page stopped
                    // compositing before the window closed — that ends the recording.
                    // Anything else (dead Chrome, closed socket) stays fatal.
                    if (chrome.isAlive()
                            && String.valueOf(quietWindow.getMessage()).startsWith("CDP timeout")) {
                        break;
                    }
                    throw quietWindow;
                }
                if ("Page.screencastFrame".equals(m.get("method"))) {
                    if (!budget.add(Base64.getDecoder().decode(
                            String.valueOf(MiniJson.get(m, "params.data"))))) {
                        break; // heap budget spent — stop filming, keep what we have
                    }
                    // Ack immediately or Chrome stops pushing after a few frames — but
                    // fire-and-forget: blocking for the ack RESULT would route any frame
                    // that arrives mid-wait into routeEvent's drop branch and lose it.
                    // The unawaited result later surfaces as an id-only message, which
                    // this loop hands to routeEvent, which ignores method-less messages.
                    Object sid = MiniJson.get(m, "params.sessionId");
                    fireAndForget("Page.screencastFrameAck",
                        "{\"sessionId\":" + ((Double) sid).intValue() + "}");
                } else {
                    routeEvent(m);
                }
            }
        } finally {
            command("Page.stopScreencast", "{}");
        }
        if (budget.size() == 0) {
            throw new IllegalStateException("screencast produced no frames in " + durationMs
                + "ms — screencast only emits when the page composites; a static page has"
                + " nothing to film (use screenshot()/recordGif for stills)");
        }
        GifWriter.write(budget.frames(), playbackDelayMs, firstFrameDelayMs, out);
        return budget.size();
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
        LIVE.remove(this); // graceful close owns the teardown now; the hook needn't touch it
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
        // walkFileTree, NOT Files.walk: a live Chrome tears down its own profile
        // concurrently — its `.com.google.Chrome.<rand>` singleton lock can vanish
        // between directory enumeration and visitation. Files.walk's lazy stream
        // surfaces that as an UncheckedIOException(NoSuchFileException) mid-forEach,
        // which escapes a plain `catch (IOException)` and fails the caller (seen only
        // on Linux CI, not macOS — different Chrome file lifecycle). walkFileTree routes
        // a vanished entry through visitFileFailed instead, so we continue and still
        // best-effort delete everything that remains.
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Already gone (Chrome removed it) — that's the desired end state.
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    try { Files.deleteIfExists(d); } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }
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
