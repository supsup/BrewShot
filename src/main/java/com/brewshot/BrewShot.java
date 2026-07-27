package com.brewshot;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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

    private static final Pattern DEVTOOLS_LINE =
        Pattern.compile("DevTools listening on\\s+(\\S+)");
    private static final Pattern DEVTOOLS_BROWSER_PATH =
        Pattern.compile("/devtools/browser/[A-Za-z0-9._-]+");
    private static final Pattern STARTUP_URL_TOKEN =
        Pattern.compile("(?i)\\b(?:ws|https?)://\\S+");
    private static final Pattern STARTUP_COMMAND_LINE = Pattern.compile(
        "(?i)^.*\\b(?:command(?:\\s+line)?|argv|args|launch(?:ing)?)\\s*[:=].*$");
    private static final Pattern STARTUP_SENSITIVE_HEADER = Pattern.compile(
        "(?i).*\\b(?:authorization|proxy-authorization|cookie|set-cookie)\\s*[:=].*$");
    private static final Pattern STARTUP_BEARER_CREDENTIAL = Pattern.compile(
        "(?i)\\b(bearer|basic)\\s+[^\\s|;,]+");
    private static final Pattern STARTUP_QUOTED_ABSOLUTE_PATH = Pattern.compile(
        "([\\\"'])(?:[A-Za-z]:[\\\\/]|/)[^\\\"'\\r\\n]*\\1");
    private static final Pattern STARTUP_ABSOLUTE_PATH = Pattern.compile(
        "(?<![A-Za-z0-9])(?:[A-Za-z]:[\\\\/]|/|~[\\\\/])[^\\s|;,)\\]]*");
    private static final Pattern STARTUP_FLAG_ASSIGNMENT = Pattern.compile(
        "(--[A-Za-z0-9][A-Za-z0-9_-]*)=\\S+");
    private static final Pattern STARTUP_FLAG_VALUE = Pattern.compile(
        "(--[A-Za-z0-9][A-Za-z0-9_-]*)\\s++(?!--[A-Za-z0-9])"
            + "(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");
    private static final Pattern STARTUP_SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)\\b((?=[A-Za-z0-9_]*(?:TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|"
            + "COOKIE|AUTH|API_KEY))[A-Za-z_][A-Za-z0-9_]*)=\\S+");
    private static final Pattern STARTUP_ENV_ASSIGNMENT = Pattern.compile(
        "\\b([A-Z][A-Z0-9_]{1,63})=\\S+");
    private static final long DEFAULT_TIMEOUT_MS = 15_000;
    private static final long BOOTSTRAP_POLL_MS = 20;
    private static final long BOOTSTRAP_WITNESS_SETTLE_MS = 100;
    private static final int BOOTSTRAP_STREAM_LINE_CAP = 4_096;
    private static final int BOOTSTRAP_TAIL_LINE_CAP = 240;
    private static final int BOOTSTRAP_TAIL_LINES = 4;
    private static final int DEVTOOLS_ACTIVE_PORT_FILE_CAP = 4_096;
    private static final long DEFAULT_CLOSE_TIMEOUT_MS = 1_000;
    private static final long PROCESS_CLOSE_TIMEOUT_MS = 3_000;
    private static final long PROCESS_FORCE_REAP_TIMEOUT_MS = 2_000;
    private static final long SHUTDOWN_CLEANUP_TIMEOUT_MS = 5_000;
    private static final long SHUTDOWN_ATTEMPT_TIMEOUT_MS = 500;
    private static final int SHUTDOWN_CLEANUP_MAX_PASSES = 3;
    private static final int CONSOLE_CAP = 1_000;
    /** Poison message the listener enqueues on close/error so a blocked caller fails fast. */
    private static final String SOCKET_CLOSED = "{\"brewshotSocketClosed\":true}";

    /**
     * DURABLE record that the transport reached its terminal state.
     *
     * <p>The reserved-slot invariant guarantees the close sentinel always gets INTO the
     * inbox. It did not guarantee a caller could ever OBSERVE it: every nonblocking
     * drain — reached from console(), consoleDropped(), errors(), errorsDropped(),
     * freshNavigation() and waitForNetworkIdle() — used to poll the sentinel and return,
     * consuming the only copy. A later command then waited out its full timeout while
     * Chrome was still alive, instead of failing fast with a closed-socket reason. That
     * is the exact stall the bound exists to prevent (review brewshot/249).
     *
     * <p>Latched rather than re-queued: a queue slot can be consumed exactly once, so a
     * flag is the only representation that survives an arbitrary number of drains by an
     * arbitrary number of callers. Once true it never clears — the socket does not reopen.
     */
    private volatile boolean socketClosed;

    /** One shared client for all launches — no selector-thread accumulation per launch. */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // Launch admission, ProcessBuilder.start(), the first live process-tree snapshot, and
    // lease registration share one monitor. Once shutdown closes admission and snapshots
    // LIVE under that monitor, it is impossible for an already-admitted process to appear
    // after the hook finishes. The SAME lease remains registered through discovery,
    // bootstrap, and close. ProcessHandle snapshots cannot prove that a child created
    // during teardown did not reparent after the final snapshot, so successful cleanup
    // also requires an independently-owned containment proof before profile deletion or
    // deregistration.
    private static final Object OWNERSHIP_LOCK = new Object();
    private static final Set<ResourceLease> LIVE =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static boolean shutdownStarted;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> cleanupOwnedResources(true), "brewshot-shutdown-cleanup"));
    }

    private final ResourceLease lease;
    private final Process chrome;
    // Kept as a direct field for ShutdownHookProbeMain's stable reflection contract.
    private final Path profileDir;
    private final WebSocket ws;
    private final LinkedBlockingQueue<String> inbox;
    /** Shared with the production Accumulator so dequeues return retained-byte capacity. */
    private final InboxBudget inboxBudget;
    private final long closeTimeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Only awaitable events are kept here (Page.loadEventFired) — bounded by design. */
    private final Deque<Map<String, Object>> pendingEvents = new ArrayDeque<>();
    /** Console messages + uncaught exceptions since the last open()/html(). Bounded by
     *  BOTH entry count (CONSOLE_CAP) and an encoded-byte budget (brewshot.maxConsoleBytes),
     *  so a single multi-MB console entry can no longer be retained whole. */
    private final BoundedLog consoleLog = new BoundedLog();
    private final BoundedLog errorLog = new BoundedLog();
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
    private long lastNetChangeNanos = System.nanoTime();
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

    // ---- resource bounds: capture size, CDP ingress, console retention ------
    // System-property-backed limits, read FRESH at the point of use so a -D
    // override (or a test's System.setProperty) takes effect. These are read
    // INDEPENDENTLY here — deliberately NOT sharing a file with the diff side,
    // which owns its own copies of the same-named screenshot limits.

    /** Default max px per axis for a captured screenshot. -Dbrewshot.maxImageDimension. */
    static final int DEFAULT_MAX_IMAGE_DIMENSION = 16_384;
    /** Default max total pixels (w*h) for a captured screenshot, 64 MP. -Dbrewshot.maxImagePixels. */
    static final long DEFAULT_MAX_IMAGE_PIXELS = 67_108_864L;
    /** Default per-CDP-message exact UTF-8 byte ceiling, 32 MiB. */
    static final long DEFAULT_MAX_CDP_MESSAGE_BYTES = 33_554_432L;
    /** Default CUMULATIVE cap on queued (undrained) CDP messages, 4096. -Dbrewshot.maxInboxMessages.
     *  Bounds the whole ingress queue, not just each message: a chatty page emitting a flood
     *  of individually-small messages while no thread is draining can no longer grow the inbox
     *  without bound. See {@link Accumulator}. */
    static final int DEFAULT_MAX_INBOX_MESSAGES = 4096;
    /** Default exact UTF-8 bytes retained across all undrained regular CDP messages. */
    static final long DEFAULT_MAX_INBOX_BYTES = DEFAULT_MAX_CDP_MESSAGE_BYTES;
    /** Default per-log retained-byte budget for console/error text, 1 MB. -Dbrewshot.maxConsoleBytes. */
    static final long DEFAULT_MAX_CONSOLE_BYTES = 1_048_576L;

    private static int intProp(String key, int dflt) {
        String v = System.getProperty(key);
        if (v != null) {
            try { int n = Integer.parseInt(v.trim()); if (n > 0) { return n; } }
            catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return dflt;
    }

    private static long longProp(String key, long dflt) {
        String v = System.getProperty(key);
        if (v != null) {
            try { long n = Long.parseLong(v.trim()); if (n > 0) { return n; } }
            catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return dflt;
    }

    private static int boundedPositiveIntProp(String key, int dflt, int max) {
        String raw = System.getProperty(key);
        if (raw == null) { return dflt; }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw invalidBound(key, raw, "an integer from 1 through " + max);
        }
        if (value <= 0 || value > max) {
            throw invalidBound(key, raw, "an integer from 1 through " + max);
        }
        return value;
    }

    private static long positiveLongProp(String key, long dflt) {
        String raw = System.getProperty(key);
        if (raw == null) { return dflt; }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw invalidBound(key, raw, "a positive integer");
        }
        if (value <= 0) {
            throw invalidBound(key, raw, "a positive integer");
        }
        return value;
    }

    private static IllegalArgumentException invalidBound(
            String key, String raw, String expected) {
        return new IllegalArgumentException(
            key + " must be " + expected + "; got \"" + raw + "\"");
    }

    private static int maxImageDimension() {
        return intProp("brewshot.maxImageDimension", DEFAULT_MAX_IMAGE_DIMENSION);
    }

    private static long maxImagePixels() {
        return longProp("brewshot.maxImagePixels", DEFAULT_MAX_IMAGE_PIXELS);
    }

    private static long maxCdpMessageBytes() {
        return positiveLongProp("brewshot.maxCdpMessageBytes", DEFAULT_MAX_CDP_MESSAGE_BYTES);
    }

    static int maxInboxMessages() {
        // finishLaunch reserves one physical queue slot for SOCKET_CLOSED.
        return boundedPositiveIntProp("brewshot.maxInboxMessages",
            DEFAULT_MAX_INBOX_MESSAGES, Integer.MAX_VALUE - 1);
    }

    private static long maxInboxBytes() {
        return positiveLongProp("brewshot.maxInboxBytes", DEFAULT_MAX_INBOX_BYTES);
    }

    private static long maxConsoleBytes() {
        return longProp("brewshot.maxConsoleBytes", DEFAULT_MAX_CONSOLE_BYTES);
    }

    /**
     * Reject a captured image whose DECODED dimensions exceed the configured
     * ceiling, inspected via an {@link ImageReader} on the RETURNED bytes — only
     * the header is read, no full pixel array is allocated — so the refusal is
     * loud and cheap and lands BEFORE any downstream full-pixel decode. Enforced
     * on the {@code screenshot}/{@code screenshotClip} capture paths. Bytes whose
     * header is unreadable are left alone (decodability is a different concern,
     * handled by the consumer). Package-private for browser-free unit testing.
     */
    static void enforceCaptureBounds(byte[] imageBytes) {
        int maxDim = maxImageDimension();
        long maxPixels = maxImagePixels();
        int w;
        int h;
        try (ImageInputStream iis =
                 ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = iis == null ? null : ImageIO.getImageReaders(iis);
            if (readers == null || !readers.hasNext()) { return; }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                w = reader.getWidth(0);
                h = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException unreadable) {
            return; // not a size problem; leave decode errors to the consumer
        }
        if (w > maxDim || h > maxDim) {
            throw new IllegalStateException("capture refused: image is " + w + "x" + h
                + ", exceeds max axis " + maxDim + " (brewshot.maxImageDimension)");
        }
        if ((long) w * h > maxPixels) {
            throw new IllegalStateException("capture refused: image is " + w + "x" + h + " = "
                + ((long) w * h) + " px, exceeds " + maxPixels + " (brewshot.maxImagePixels)");
        }
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
     * Return a fixed, sanitized refusal for the known-denied macOS Codex
     * Seatbelt context, or {@code null} when launch may proceed. Chrome 150's
     * unified headless bootstrap calls into LaunchServices there and aborts
     * before publishing a DevTools endpoint, sometimes queuing an operator
     * crash dialog. The separately installed {@code chrome-headless-shell}
     * does not use that unified browser bootstrap and remains an explicit
     * opt-in escape hatch through {@code BREWSHOT_CHROME}.
     */
    static String launchContextRefusal(Map<String, String> env, String osName,
                                       String chromeBinary) {
        boolean mac = osName != null
            && osName.toLowerCase(java.util.Locale.ROOT).startsWith("mac");
        String sandbox = env.get("CODEX_SANDBOX");
        boolean seatbelt = sandbox != null
            && "seatbelt".equalsIgnoreCase(sandbox.trim());
        String override = env.get("BREWSHOT_CHROME");
        boolean explicitHeadlessShell = override != null
            && override.equals(chromeBinary) && isHeadlessShell(chromeBinary);
        if (!mac || !seatbelt || explicitHeadlessShell) { return null; }
        return "BrewShot refused to launch unified Chrome from the macOS Codex Seatbelt "
            + "context because LaunchServices bootstrap is denied there. Run BrewShot from "
            + "a normal Terminal or supported container, or explicitly point "
            + "BREWSHOT_CHROME at chrome-headless-shell. No Chrome process or profile was "
            + "created.";
    }

    private static boolean isHeadlessShell(String chromeBinary) {
        if (chromeBinary == null || chromeBinary.isBlank()) { return false; }
        String name;
        try { name = Path.of(chromeBinary).getFileName().toString(); }
        catch (RuntimeException invalidPath) { return false; }
        return "chrome-headless-shell".equalsIgnoreCase(name)
            || "chrome-headless-shell.exe".equalsIgnoreCase(name);
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
        this(chrome, profileDir, ws, inbox, DEFAULT_CLOSE_TIMEOUT_MS);
    }

    /** Package-private timeout seam for pure transport tests (no Chrome process). */
    BrewShot(Process chrome, Path profileDir, WebSocket ws,
             LinkedBlockingQueue<String> inbox, long closeTimeoutMs) {
        this(chrome, profileDir, ws, inbox, closeTimeoutMs, InboxBudget.untracked());
    }

    /** Package-private shared-budget seam for pure transport tests (no Chrome process). */
    BrewShot(Process chrome, Path profileDir, WebSocket ws,
             LinkedBlockingQueue<String> inbox, long closeTimeoutMs,
             InboxBudget inboxBudget) {
        this(registerContainedLaunchLeaseForTests(
                chrome, profileDir, BrewShot::deleteRecursively),
            ws, inbox, closeTimeoutMs, inboxBudget);
    }

    BrewShot(ResourceLease lease, WebSocket ws,
             LinkedBlockingQueue<String> inbox, long closeTimeoutMs) {
        this(lease, ws, inbox, closeTimeoutMs, InboxBudget.untracked());
    }

    BrewShot(ResourceLease lease, WebSocket ws,
             LinkedBlockingQueue<String> inbox, long closeTimeoutMs,
             InboxBudget inboxBudget) {
        this.lease = lease;
        this.chrome = lease.process;
        this.profileDir = lease.profileDir;
        this.ws = ws;
        this.inbox = inbox;
        this.inboxBudget = Objects.requireNonNull(inboxBudget, "inboxBudget");
        this.closeTimeoutMs = Math.max(1, closeTimeoutMs);
        lease.transferToClient();
    }

    @FunctionalInterface
    interface WebSocketConnector {
        CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener,
                                             Duration connectTimeout);
    }

    private static final WebSocketConnector HTTP_CONNECTOR =
        (uri, listener, connectTimeout) -> connectWebSocket(
            HTTP.newWebSocketBuilder(), uri, listener, connectTimeout);

    static CompletableFuture<WebSocket> connectWebSocket(
            WebSocket.Builder builder, URI uri, WebSocket.Listener listener,
            Duration connectTimeout) {
        return builder.connectTimeout(connectTimeout).buildAsync(uri, listener);
    }

    @FunctionalInterface
    interface ProfileDeleter {
        void delete(Path profileDir);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    /**
     * External proof that process-tree membership is closed and every member
     * outside the retained JDK handles has exited. A
     * {@link ProcessHandle#descendants()} snapshot is deliberately not such a
     * proof: a child can be created after the snapshot and reparent before
     * another enumeration observes it.
     */
    @FunctionalInterface
    interface ProcessTreeReleaseProof {
        boolean releaseSafe();
    }

    @FunctionalInterface
    interface ProfileAbsenceProbe {
        boolean absent(Path profileDir);
    }

    private static final ProcessTreeReleaseProof UNPROVEN_PROCESS_TREE_RELEASE =
        () -> false;
    private static final ProcessTreeReleaseProof PROVEN_PROCESS_TREE_RELEASE_FOR_TESTS =
        () -> true;
    private static final ProfileAbsenceProbe REAL_PROFILE_ABSENCE_PROBE =
        BrewShot::profileAbsent;

    /**
     * JVM-lifetime ownership of the process/profile pair. Cleanup passes
     * serialize, but failed passes do not release ownership: a later close or
     * shutdown pass in this JVM can retry a process that ignored SIGKILL or a
     * profile deletion that raced a late helper writer. The static registry is
     * reclaimed when the JVM exits; it is not a cross-JVM ownership journal.
     */
    static final class ResourceLease {
        private enum Owner { LAUNCH, CLIENT, RELEASED }

        private final Process process;
        private final Path profileDir;
        private final ProfileDeleter profileDeleter;
        private final ProcessTreeReleaseProof processTreeReleaseProof;
        private final ProfileAbsenceProbe profileAbsenceProbe;
        private ProcessHandle parentHandle;
        private final List<ProcessHandle> descendantHandles = new ArrayList<>();
        private Owner owner = Owner.LAUNCH;
        /** The argv sweep enumerates every process on the host (~30ms here), so
         *  it runs ONCE per lease rather than on every retry pass: reparenting
         *  happens at most once per launch, and a per-pass sweep would spend a
         *  meaningful slice of the bounded cleanup budget re-proving it. */
        private boolean orphanSweepDone;

        private ResourceLease(Process process, Path profileDir,
                              ProfileDeleter profileDeleter,
                              ProcessTreeReleaseProof processTreeReleaseProof,
                              ProfileAbsenceProbe profileAbsenceProbe) {
            this.process = process;
            this.profileDir = profileDir;
            this.profileDeleter = profileDeleter;
            this.processTreeReleaseProof = processTreeReleaseProof;
            this.profileAbsenceProbe = profileAbsenceProbe;
            refreshProcessTreeSnapshot();
        }

        synchronized void transferToClient() {
            refreshProcessTreeSnapshot();
            if (owner != Owner.LAUNCH || !isRegistered(this) || !isAlive(process)) {
                throw new IllegalStateException(
                    "Chrome resources were cleaned up while launch was in progress");
            }
            owner = Owner.CLIENT;
        }

        synchronized void requireOwned() {
            refreshProcessTreeSnapshot();
            if (owner == Owner.RELEASED || !isRegistered(this) || !isAlive(process)) {
                throw new IllegalStateException(
                    "Chrome resources were cleaned up while launch was in progress");
            }
        }

        synchronized void cleanup(boolean gracefulFirst) {
            long timeoutMs = gracefulFirst
                ? PROCESS_CLOSE_TIMEOUT_MS + PROCESS_FORCE_REAP_TIMEOUT_MS
                : PROCESS_FORCE_REAP_TIMEOUT_MS;
            cleanup(gracefulFirst, Deadline.afterMillis(timeoutMs));
        }

        synchronized boolean cleanup(boolean gracefulFirst, Deadline deadline) {
            if (owner == Owner.RELEASED) { return true; }

            // Refresh while the parent is still alive and before any controlled
            // signal. Retained handles survive parent exit/reparenting.
            refreshProcessTreeSnapshot();
            boolean processTreeReaped = terminateProcess(
                process, parentHandle, List.copyOf(descendantHandles),
                gracefulFirst, deadline);
            // Runs BEFORE the release gate and the delete: a helper that was
            // reparented out of every handle snapshot is invisible to
            // terminateProcess, and it recreates the directory we are about to
            // remove. Its liveness also gates release — a known argv-matching
            // survivor is positive evidence that containment is NOT closed.
            boolean orphansReaped = true;
            if (!orphanSweepDone) {
                orphanSweepDone = true;
                orphansReaped =
                    allHandlesDead(sweepOrphansByProfilePath(profileDir, deadline));
            }
            boolean releaseProven =
                processTreeReaped && orphansReaped && processTreeReleaseSafe();
            if (releaseProven) {
                try { profileDeleter.delete(profileDir); }
                catch (RuntimeException ignored) {
                    // Ownership remains registered; a later close/hook retries.
                }
            }

            if (releaseProven && profileAbsenceProven()) {
                owner = Owner.RELEASED;
                deregister(this);
            }
            return owner == Owner.RELEASED;
        }

        synchronized boolean isOwned() {
            return owner != Owner.RELEASED && isRegistered(this);
        }

        synchronized void refreshOwnershipCheckpoint() {
            if (owner != Owner.RELEASED) { refreshProcessTreeSnapshot(); }
        }

        private boolean processTreeReleaseSafe() {
            try { return processTreeReleaseProof.releaseSafe(); }
            catch (RuntimeException ignored) {
                // A failed proof is no proof. Retain the profile and lease.
                return false;
            }
        }

        private boolean profileAbsenceProven() {
            try { return profileAbsenceProbe.absent(profileDir); }
            catch (RuntimeException ignored) {
                // An indeterminate pathname result is no proof of absence.
                return false;
            }
        }

        /**
         * Retain every identity an enumeration exposes so cleanup can still
         * signal it after reparenting. This snapshot is useful termination input,
         * never evidence that membership is complete.
         */
        private void refreshProcessTreeSnapshot() {
            ProcessHandle observedParent = null;
            List<ProcessHandle> observedDescendants = List.of();
            try {
                observedParent = process.toHandle();
                try (var descendants = observedParent.descendants()) {
                    observedDescendants = descendants.toList();
                }
            } catch (RuntimeException ignored) {
                // Best effort only; the external containment gate stays closed.
            }

            if (observedParent != null) {
                if (parentHandle == null) { parentHandle = observedParent; }
                for (ProcessHandle handle : observedDescendants) {
                    if (!handle.equals(parentHandle)
                            && !descendantHandles.contains(handle)) {
                        descendantHandles.add(handle);
                    }
                }
            }
        }
    }

    /**
     * Admit and register a newly started process atomically with respect to JVM
     * shutdown. The callback is a deterministic test seam for the otherwise
     * instruction-sized start-return/registration interval.
     */
    static ResourceLease startOwnedProcess(Path profileDir, ProcessStarter starter,
                                           ProfileDeleter profileDeleter,
                                           Runnable afterStartBeforeRegistration)
            throws IOException {
        return startOwnedProcess(
            profileDir, starter, profileDeleter, afterStartBeforeRegistration,
            UNPROVEN_PROCESS_TREE_RELEASE);
    }

    private static ResourceLease startOwnedProcess(
            Path profileDir, ProcessStarter starter,
            ProfileDeleter profileDeleter,
            Runnable afterStartBeforeRegistration,
            ProcessTreeReleaseProof processTreeReleaseProof)
            throws IOException {
        ResourceLease admitted = null;
        try {
            synchronized (OWNERSHIP_LOCK) {
                if (shutdownStarted) {
                    throw new IllegalStateException(
                        "cannot launch Chrome after JVM shutdown has started");
                }
                Process process = starter.start();
                ResourceLease lease =
                    new ResourceLease(
                        process, profileDir, profileDeleter,
                        processTreeReleaseProof, REAL_PROFILE_ABSENCE_PROBE);
                admitted = lease;
                try {
                    afterStartBeforeRegistration.run();
                } finally {
                    LIVE.add(lease);
                }
                return lease;
            }
        } catch (IOException | RuntimeException | Error e) {
            if (admitted != null) {
                // A post-start failure already has a JVM-lifetime lease. Its
                // cleanup alone owns profile deletion; if reap or containment
                // proof fails, ownership and the profile remain intact for a
                // later pass in this JVM.
                admitted.cleanup(false);
            } else {
                // Shutdown rejection or starter failure created no process
                // reference, so no lease exists to own the unstarted profile.
                try { profileDeleter.delete(profileDir); }
                catch (RuntimeException ignored) { }
            }
            throw e;
        }
    }

    static ResourceLease startLaunchProcess(Path profileDir, ProcessStarter starter,
                                            Runnable afterStartBeforeRegistration)
            throws IOException {
        return startOwnedProcess(
            profileDir, starter, BrewShot::deleteRecursively,
            afterStartBeforeRegistration);
    }

    static ResourceLease startContainedOwnedProcessForTests(
            Path profileDir, ProcessStarter starter,
            ProfileDeleter profileDeleter,
            Runnable afterStartBeforeRegistration)
            throws IOException {
        return startOwnedProcess(
            profileDir, starter, profileDeleter, afterStartBeforeRegistration,
            PROVEN_PROCESS_TREE_RELEASE_FOR_TESTS);
    }

    static ResourceLease startContainedLaunchProcessForTests(
            Path profileDir, ProcessStarter starter,
            Runnable afterStartBeforeRegistration)
            throws IOException {
        return startContainedOwnedProcessForTests(
            profileDir, starter, BrewShot::deleteRecursively,
            afterStartBeforeRegistration);
    }

    /** Attach an already-started process; production launch uses the admission fence above. */
    static ResourceLease registerLaunchLease(Process process, Path profileDir,
                                             ProfileDeleter profileDeleter) {
        return registerLaunchLease(
            process, profileDir, profileDeleter,
            UNPROVEN_PROCESS_TREE_RELEASE);
    }

    private static ResourceLease registerLaunchLease(
            Process process, Path profileDir,
            ProfileDeleter profileDeleter,
            ProcessTreeReleaseProof processTreeReleaseProof) {
        return registerLaunchLease(
            process, profileDir, profileDeleter, processTreeReleaseProof,
            REAL_PROFILE_ABSENCE_PROBE);
    }

    private static ResourceLease registerLaunchLease(
            Process process, Path profileDir,
            ProfileDeleter profileDeleter,
            ProcessTreeReleaseProof processTreeReleaseProof,
            ProfileAbsenceProbe profileAbsenceProbe) {
        ResourceLease lease = new ResourceLease(
            process, profileDir, profileDeleter, processTreeReleaseProof,
            profileAbsenceProbe);
        boolean cleanupImmediately;
        synchronized (OWNERSHIP_LOCK) {
            LIVE.add(lease);
            cleanupImmediately = shutdownStarted;
        }
        if (cleanupImmediately) { lease.cleanup(false); }
        return lease;
    }

    static ResourceLease registerContainedLaunchLeaseForTests(
            Process process, Path profileDir,
            ProfileDeleter profileDeleter) {
        return registerLaunchLease(
            process, profileDir, profileDeleter,
            PROVEN_PROCESS_TREE_RELEASE_FOR_TESTS);
    }

    static ResourceLease registerContainedLaunchLeaseForTests(
            Process process, Path profileDir) {
        return registerContainedLaunchLeaseForTests(
            process, profileDir, BrewShot::deleteRecursively);
    }

    static ResourceLease registerLaunchLeaseWithReleaseProofForTests(
            Process process, Path profileDir,
            ProfileDeleter profileDeleter,
            ProcessTreeReleaseProof processTreeReleaseProof) {
        return registerLaunchLease(
            process, profileDir, profileDeleter, processTreeReleaseProof);
    }

    static ResourceLease registerContainedLaunchLeaseWithProfileAbsenceProbeForTests(
            Process process, Path profileDir,
            ProfileDeleter profileDeleter,
            ProfileAbsenceProbe profileAbsenceProbe) {
        return registerLaunchLease(
            process, profileDir, profileDeleter,
            PROVEN_PROCESS_TREE_RELEASE_FOR_TESTS, profileAbsenceProbe);
    }

    static ResourceLease registerLaunchLease(Process process, Path profileDir) {
        return registerLaunchLease(process, profileDir, BrewShot::deleteRecursively);
    }

    private static boolean isRegistered(ResourceLease lease) {
        synchronized (OWNERSHIP_LOCK) {
            return LIVE.contains(lease);
        }
    }

    private static void deregister(ResourceLease lease) {
        synchronized (OWNERSHIP_LOCK) {
            LIVE.remove(lease);
        }
    }

    private static void cleanupOwnedResources(boolean beginJvmShutdown) {
        List<ResourceLease> initialSnapshot;
        synchronized (OWNERSHIP_LOCK) {
            if (beginJvmShutdown) { shutdownStarted = true; }
            initialSnapshot = List.copyOf(LIVE);
        }
        // Admission-lock wait is deliberately outside this budget: an admitted
        // ProcessBuilder.start must return and register before shutdown may
        // finish. Once that fence is acquired, all process waits and retry
        // admission share this one deadline.
        Deadline deadline = Deadline.afterMillis(SHUTDOWN_CLEANUP_TIMEOUT_MS);

        // Synchronous filesystem deletion is not an interruptible Java
        // operation and is not falsely claimed to be covered by the wait bound.
        int maxPasses = beginJvmShutdown ? SHUTDOWN_CLEANUP_MAX_PASSES : 1;
        for (int pass = 0; pass < maxPasses && !deadline.expired(); pass++) {
            List<ResourceLease> snapshot = initialSnapshot;
            if (pass > 0) {
                synchronized (OWNERSHIP_LOCK) {
                    snapshot = List.copyOf(LIVE);
                }
            }
            if (snapshot.isEmpty()) { return; }
            for (ResourceLease lease : snapshot) {
                if (deadline.expired()) { return; }
                lease.cleanup(false,
                    deadline.cappedAtMillis(SHUTDOWN_ATTEMPT_TIMEOUT_MS));
            }
        }
    }

    static void runShutdownCleanupForTests() {
        cleanupOwnedResources(false);
    }

    static void runJvmShutdownCleanupForTests() {
        try {
            cleanupOwnedResources(true);
        } finally {
            synchronized (OWNERSHIP_LOCK) {
                shutdownStarted = false;
            }
        }
    }

    static boolean ownsResources(Process process, Path profileDir) {
        synchronized (OWNERSHIP_LOCK) {
            for (ResourceLease lease : LIVE) {
                if (lease.process == process && lease.profileDir.equals(profileDir)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Launch with a sensible default viewport (1280x900). */
    public static BrewShot launch() throws IOException {
        return launch(1280, 900);
    }

    /** Launch headless Chrome with the given viewport and attach to a fresh tab. */
    public static BrewShot launch(int width, int height) throws IOException {
        Validation.positiveInt("viewport width", width);
        Validation.positiveInt("viewport height", height);
        String bin = findChrome();
        if (bin == null) { throw new IllegalStateException("no Chrome binary found"); }
        String refusal = launchContextRefusal(
            System.getenv(), System.getProperty("os.name", ""), bin);
        if (refusal != null) { throw new IOException(refusal); }
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
            "--no-default-browser-check",
            // macOS 26 + Chrome 150 (plan ba9dafd7, 2026-07-22): without this flag, ~1/3 of
            // rapid headless launches spawned a doomed secondary Chrome that abort()ed inside
            // TransformProcessType -> _RegisterApplication. It reduced the original observed
            // storm from 5/15 aborts to 0/15, but later Seatbelt evidence proved it is not a
            // universal crash-dialog fix; launchContextRefusal handles that known-denied context.
            // The flag remains harmless on Linux/CI.
            "--no-startup-window"));
        // Extra Chrome flags via env — the container hook (e.g. the Docker
        // image sets BREWSHOT_CHROME_ARGS=--no-sandbox: Chrome's sandbox needs
        // privileges containers don't grant by default). Space-separated.
        String extra = System.getenv("BREWSHOT_CHROME_ARGS");
        if (extra != null && !extra.isBlank()) {
            args.addAll(List.of(extra.trim().split("\\s+")));
        }
        args.add("about:blank");
        ResourceLease lease = startLaunchProcess(
            profile,
            () -> new ProcessBuilder(args)
                // Both startup streams are bounded and continuously drained by
                // the endpoint witness observer below. Chrome versions and
                // wrappers have published the DevTools line on either stream.
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start(),
            () -> { });
        Process p = lease.process;

        String wsUrl;
        try {
            wsUrl = awaitDevtoolsUrl(p, profile);
            lease.refreshOwnershipCheckpoint();
        } catch (RuntimeException | IOException | Error e) {
            lease.cleanup(false);
            throw e;
        }
        return finishLaunch(lease, wsUrl, HTTP_CONNECTOR, envTimeoutMs());
    }

    /**
     * Complete the post-process-start half of launch. The connector seam keeps
     * the never-completing-connect discriminator pure: no Chrome is launched.
     */
    static BrewShot finishLaunch(Process p, Path profile, String wsUrl,
                                 WebSocketConnector connector, long connectTimeoutMs)
            throws IOException {
        ResourceLease lease =
            registerContainedLaunchLeaseForTests(
                p, profile, BrewShot::deleteRecursively);
        return finishLaunch(lease, wsUrl, connector, connectTimeoutMs);
    }

    static BrewShot finishLaunch(ResourceLease lease, String wsUrl,
                                 WebSocketConnector connector, long connectTimeoutMs)
            throws IOException {
        // F-01 (audit): the CDP inbox is the one unbounded ingress point — Chrome is
        // the producer and it is not rate-limited by us, so an unbounded queue lets a
        // chatty or hostile page grow it without ceiling. Capacity is cap + 1: the
        // Accumulator enqueues at most `inboxCap` regular messages, keeping the last
        // physical slot reserved so the close/error sentinel (SOCKET_CLOSED) can NEVER
        // be lost to a full inbox — a blocked caller still fails fast instead of
        // sleeping out the timeout. See Accumulator.
        WebSocket socket = null;
        BrewShot c = null;
        try {
            // Validate every configured bound before allocating or contacting Chrome.
            int inboxCap = maxInboxMessages();
            long maxMessageBytes = maxCdpMessageBytes();
            long maxRetainedBytes = maxInboxBytes();
            InboxBudget inboxBudget = new InboxBudget(maxRetainedBytes);
            LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>(inboxCap + 1);
            long boundedConnectTimeoutMs = Math.max(1, connectTimeoutMs);
            Deadline connectDeadline = Deadline.afterMillis(boundedConnectTimeoutMs);
            CompletableFuture<WebSocket> connecting =
                connector.connect(URI.create(wsUrl),
                    new Accumulator(inbox, maxMessageBytes, inboxCap, inboxBudget),
                    Duration.ofMillis(boundedConnectTimeoutMs));
            socket = awaitConnection(
                connecting, connectDeadline, boundedConnectTimeoutMs);
            c = new BrewShot(
                lease, socket, inbox, DEFAULT_CLOSE_TIMEOUT_MS, inboxBudget);
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
            lease.requireOwned();
            return c;
        } catch (RuntimeException | IOException | Error e) {
            abort(socket);
            lease.cleanup(false);
            throw e;
        }
    }

    static WebSocket awaitConnection(CompletableFuture<WebSocket> connecting,
                                     long timeoutMs) throws IOException {
        long boundedTimeoutMs = Math.max(1, timeoutMs);
        return awaitConnection(
            connecting, Deadline.afterMillis(boundedTimeoutMs), boundedTimeoutMs);
    }

    private static WebSocket awaitConnection(CompletableFuture<WebSocket> connecting,
                                             Deadline deadline, long timeoutMs)
            throws IOException {
        try {
            return await(connecting, deadline);
        } catch (TimeoutException e) {
            cancelConnection(connecting);
            throw new IOException("DevTools WebSocket connect timed out after "
                + timeoutMs + "ms", e);
        } catch (InterruptedException e) {
            cancelConnection(connecting);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted connecting to DevTools WebSocket", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("could not connect to DevTools WebSocket: "
                + cause.getMessage(), cause);
        } catch (CancellationException e) {
            throw new IOException("DevTools WebSocket connect was cancelled", e);
        }
    }

    private static void cancelConnection(CompletableFuture<WebSocket> connecting) {
        // If completion wins the timeout/cancel race, abort the otherwise-unowned socket.
        connecting.whenComplete((socket, failure) -> {
            if (socket != null) { abort(socket); }
        });
        connecting.cancel(true);
    }

    enum DevToolsWitnessSource { STDOUT, STDERR, PROFILE }

    enum DevToolsBootstrapOutcome {
        ENDPOINT,
        PROCESS_EXITED,
        ALIVE_TIMEOUT,
        MALFORMED_ENDPOINT,
        DISAGREEING_ENDPOINTS
    }

    record DevToolsBootstrapResult(
        DevToolsBootstrapOutcome outcome,
        String webSocketUrl,
        Set<DevToolsWitnessSource> sources,
        int exitCode,
        long elapsedMillis,
        String stdoutTail,
        String stderrTail
    ) {
        DevToolsBootstrapResult {
            sources = sources.isEmpty()
                ? Set.of()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(sources));
        }
    }

    private record DevToolsEndpoint(String canonicalKey, String connectUrl) { }

    private enum ActivePortState { ABSENT, INCOMPLETE, VALID, MALFORMED }

    private record ActivePortProbe(ActivePortState state, DevToolsEndpoint endpoint) {
        static ActivePortProbe absent() {
            return new ActivePortProbe(ActivePortState.ABSENT, null);
        }

        static ActivePortProbe incomplete() {
            return new ActivePortProbe(ActivePortState.INCOMPLETE, null);
        }

        static ActivePortProbe malformed() {
            return new ActivePortProbe(ActivePortState.MALFORMED, null);
        }

        static ActivePortProbe valid(DevToolsEndpoint endpoint) {
            return new ActivePortProbe(ActivePortState.VALID, endpoint);
        }
    }

    private record BootstrapEvent(
        DevToolsWitnessSource source,
        DevToolsEndpoint endpoint,
        boolean malformed,
        boolean closed
    ) {
        static BootstrapEvent endpoint(DevToolsWitnessSource source,
                                       DevToolsEndpoint endpoint) {
            return new BootstrapEvent(source, endpoint, false, false);
        }

        static BootstrapEvent malformed(DevToolsWitnessSource source) {
            return new BootstrapEvent(source, null, true, false);
        }

        static BootstrapEvent closed(DevToolsWitnessSource source) {
            return new BootstrapEvent(source, null, false, true);
        }
    }

    /** A fixed-size sanitized tail; startup output never grows retained state. */
    private static final class BootstrapTail {
        private final Deque<String> lines = new ArrayDeque<>();

        synchronized void add(String line) {
            if (line == null || line.isBlank()) { return; }
            while (lines.size() >= BOOTSTRAP_TAIL_LINES) { lines.removeFirst(); }
            lines.addLast(line);
        }

        synchronized String snapshot() {
            return String.join(" | ", lines);
        }
    }

    /**
     * Observe Chrome bootstrap through three independently bounded witnesses:
     * stdout, stderr, and the generated profile's {@code DevToolsActivePort}.
     * All observers share one monotonic deadline. Stream readers remain daemon
     * drains after success, preventing a live Chrome from filling either pipe.
     */
    static DevToolsBootstrapResult observeDevToolsEndpoint(
            Process process, Path profileDir, long timeoutMs) throws IOException {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(profileDir, "profileDir");
        long boundedTimeoutMs = Math.max(1, timeoutMs);
        long startedNanos = System.nanoTime();
        Deadline deadline = Deadline.afterMillis(boundedTimeoutMs);
        var events = new LinkedBlockingQueue<BootstrapEvent>(32);
        BootstrapTail stdoutTail = new BootstrapTail();
        BootstrapTail stderrTail = new BootstrapTail();
        startBootstrapDrain(
            "brewshot-stdout", process.getInputStream(),
            DevToolsWitnessSource.STDOUT, profileDir, events, stdoutTail);
        startBootstrapDrain(
            "brewshot-stderr", process.getErrorStream(),
            DevToolsWitnessSource.STDERR, profileDir, events, stderrTail);

        EnumMap<DevToolsWitnessSource, DevToolsEndpoint> endpoints =
            new EnumMap<>(DevToolsWitnessSource.class);
        EnumSet<DevToolsWitnessSource> malformed =
            EnumSet.noneOf(DevToolsWitnessSource.class);
        EnumSet<DevToolsWitnessSource> closed =
            EnumSet.noneOf(DevToolsWitnessSource.class);
        boolean activePortObserved = false;
        long firstWitnessNanos = -1;
        long profileNonValidSinceNanos = -1;

        while (true) {
            ActivePortProbe fileProbe = probeDevToolsActivePort(profileDir);
            if (fileProbe.state() != ActivePortState.ABSENT) {
                activePortObserved = true;
            }
            if (fileProbe.state() == ActivePortState.VALID) {
                profileNonValidSinceNanos = -1;
                malformed.remove(DevToolsWitnessSource.PROFILE);
                if (!recordEndpoint(
                        endpoints, DevToolsWitnessSource.PROFILE,
                        fileProbe.endpoint())) {
                    return bootstrapResult(
                        DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS,
                        null, endpoints.keySet(), process, startedNanos,
                        stdoutTail, stderrTail);
                }
                if (firstWitnessNanos < 0) { firstWitnessNanos = System.nanoTime(); }
            } else if (fileProbe.state() == ActivePortState.INCOMPLETE
                    || fileProbe.state() == ActivePortState.MALFORMED) {
                long now = System.nanoTime();
                if (profileNonValidSinceNanos < 0) {
                    profileNonValidSinceNanos = now;
                } else if (now - profileNonValidSinceNanos
                        >= TimeUnit.MILLISECONDS.toNanos(
                            BOOTSTRAP_WITNESS_SETTLE_MS)) {
                    // A generated file can be visible between create and its
                    // second-line write. Only a non-valid file that persists
                    // for the witness-settle window is a malformed witness.
                    malformed.add(DevToolsWitnessSource.PROFILE);
                }
            } else {
                profileNonValidSinceNanos = -1;
                malformed.remove(DevToolsWitnessSource.PROFILE);
            }

            BootstrapEvent event;
            while ((event = events.poll()) != null) {
                if (event.closed()) { closed.add(event.source()); }
                if (event.malformed()) { malformed.add(event.source()); }
                if (event.endpoint() != null) {
                    malformed.remove(event.source());
                    if (!recordEndpoint(endpoints, event.source(), event.endpoint())) {
                        return bootstrapResult(
                            DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS,
                            null, endpoints.keySet(), process, startedNanos,
                            stdoutTail, stderrTail);
                    }
                    if (firstWitnessNanos < 0) { firstWitnessNanos = System.nanoTime(); }
                }
            }

            if (!endpoints.isEmpty() && !malformed.isEmpty()) {
                EnumSet<DevToolsWitnessSource> observed =
                    EnumSet.copyOf(endpoints.keySet());
                observed.addAll(malformed);
                return bootstrapResult(
                    DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
                    null, observed, process, startedNanos,
                    stdoutTail, stderrTail);
            }

            boolean streamsClosed =
                closed.contains(DevToolsWitnessSource.STDOUT)
                    && closed.contains(DevToolsWitnessSource.STDERR);
            boolean witnessSettled = firstWitnessNanos >= 0
                && System.nanoTime() - firstWitnessNanos
                    >= TimeUnit.MILLISECONDS.toNanos(BOOTSTRAP_WITNESS_SETTLE_MS);
            boolean profileWriteSettled = profileNonValidSinceNanos < 0
                || System.nanoTime() - profileNonValidSinceNanos
                    >= TimeUnit.MILLISECONDS.toNanos(BOOTSTRAP_WITNESS_SETTLE_MS);
            if (!endpoints.isEmpty() && witnessSettled && profileWriteSettled) {
                DevToolsEndpoint endpoint = endpoints.values().iterator().next();
                return bootstrapResult(
                    DevToolsBootstrapOutcome.ENDPOINT,
                    endpoint.connectUrl(), endpoints.keySet(), process,
                    startedNanos, stdoutTail, stderrTail);
            }

            if (!process.isAlive() && streamsClosed) {
                DevToolsBootstrapOutcome outcome =
                    !malformed.isEmpty() || activePortObserved
                        ? DevToolsBootstrapOutcome.MALFORMED_ENDPOINT
                        : DevToolsBootstrapOutcome.PROCESS_EXITED;
                EnumSet<DevToolsWitnessSource> observed =
                    EnumSet.noneOf(DevToolsWitnessSource.class);
                observed.addAll(malformed);
                if (activePortObserved) { observed.add(DevToolsWitnessSource.PROFILE); }
                return bootstrapResult(
                    outcome, null, observed, process, startedNanos,
                    stdoutTail, stderrTail);
            }

            if (deadline.expired()) {
                if (!endpoints.isEmpty()) {
                    DevToolsEndpoint endpoint = endpoints.values().iterator().next();
                    return bootstrapResult(
                        DevToolsBootstrapOutcome.ENDPOINT,
                        endpoint.connectUrl(), endpoints.keySet(), process,
                        startedNanos, stdoutTail, stderrTail);
                }
                DevToolsBootstrapOutcome outcome =
                    !malformed.isEmpty() || activePortObserved
                        ? DevToolsBootstrapOutcome.MALFORMED_ENDPOINT
                        : process.isAlive()
                            ? DevToolsBootstrapOutcome.ALIVE_TIMEOUT
                            : DevToolsBootstrapOutcome.PROCESS_EXITED;
                EnumSet<DevToolsWitnessSource> observed =
                    EnumSet.noneOf(DevToolsWitnessSource.class);
                observed.addAll(malformed);
                if (activePortObserved) { observed.add(DevToolsWitnessSource.PROFILE); }
                return bootstrapResult(
                    outcome, null, observed, process, startedNanos,
                    stdoutTail, stderrTail);
            }

            long sleepNanos = Math.min(
                deadline.remainingNanos(),
                TimeUnit.MILLISECONDS.toNanos(BOOTSTRAP_POLL_MS));
            try { TimeUnit.NANOSECONDS.sleep(Math.max(1, sleepNanos)); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for Chrome bootstrap", e);
            }
        }
    }

    private static DevToolsBootstrapResult bootstrapResult(
            DevToolsBootstrapOutcome outcome, String webSocketUrl,
            Set<DevToolsWitnessSource> sources, Process process,
            long startedNanos, BootstrapTail stdoutTail,
            BootstrapTail stderrTail) {
        long elapsedNanos = Math.max(0, System.nanoTime() - startedNanos);
        int exitCode = -1;
        if (!process.isAlive()) {
            try { exitCode = process.exitValue(); }
            catch (IllegalThreadStateException ignored) { /* raced alive */ }
        }
        return new DevToolsBootstrapResult(
            outcome, webSocketUrl, sources, exitCode,
            TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
            stdoutTail.snapshot(), stderrTail.snapshot());
    }

    private static boolean recordEndpoint(
            EnumMap<DevToolsWitnessSource, DevToolsEndpoint> endpoints,
            DevToolsWitnessSource source, DevToolsEndpoint endpoint) {
        DevToolsEndpoint prior = endpoints.putIfAbsent(source, endpoint);
        if (prior != null && !prior.canonicalKey().equals(endpoint.canonicalKey())) {
            return false;
        }
        for (DevToolsEndpoint observed : endpoints.values()) {
            if (!observed.canonicalKey().equals(endpoint.canonicalKey())) {
                return false;
            }
        }
        return true;
    }

    private static void startBootstrapDrain(
            String threadName, InputStream stream, DevToolsWitnessSource source,
            Path profileDir, LinkedBlockingQueue<BootstrapEvent> events,
            BootstrapTail tail) {
        Thread reader = new Thread(
            () -> drainBootstrapStream(stream, source, profileDir, events, tail),
            threadName);
        reader.setDaemon(true);
        reader.start();
    }

    private static void drainBootstrapStream(
            InputStream stream, DevToolsWitnessSource source, Path profileDir,
            LinkedBlockingQueue<BootstrapEvent> events, BootstrapTail tail) {
        int endpointEvents = 0;
        try (InputStreamReader reader =
                 new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[512];
            StringBuilder line = new StringBuilder();
            boolean truncated = false;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    char c = buffer[i];
                    if (c == '\n') {
                        if (handleBootstrapLine(
                                line.toString(), truncated, source,
                                profileDir, events, tail, endpointEvents)) {
                            endpointEvents++;
                        }
                        line.setLength(0);
                        truncated = false;
                    } else if (c != '\r') {
                        if (line.length() < BOOTSTRAP_STREAM_LINE_CAP) {
                            line.append(c);
                        } else {
                            truncated = true;
                        }
                    }
                }
            }
            if (!line.isEmpty() || truncated) {
                handleBootstrapLine(
                    line.toString(), truncated, source, profileDir,
                    events, tail, endpointEvents);
            }
        } catch (IOException e) {
            tail.add("startup stream read failed ("
                + e.getClass().getSimpleName() + ")");
        } finally {
            events.offer(BootstrapEvent.closed(source));
        }
    }

    private static boolean handleBootstrapLine(
            String rawLine, boolean truncated, DevToolsWitnessSource source,
            Path profileDir, LinkedBlockingQueue<BootstrapEvent> events,
            BootstrapTail tail, int endpointEvents) {
        tail.add(sanitizeBootstrapLine(
            truncated ? rawLine + " <truncated>" : rawLine, profileDir));
        Matcher marker = DEVTOOLS_LINE.matcher(rawLine);
        if (!marker.find()) { return false; }
        if (endpointEvents >= 8) { return true; }
        if (truncated) {
            events.offer(BootstrapEvent.malformed(source));
            return true;
        }
        DevToolsEndpoint endpoint = parseStreamDevToolsEndpoint(marker.group(1));
        events.offer(endpoint != null
            ? BootstrapEvent.endpoint(source, endpoint)
            : BootstrapEvent.malformed(source));
        return true;
    }

    private static DevToolsEndpoint parseStreamDevToolsEndpoint(String raw) {
        if (raw == null || raw.length() > 2_048) { return null; }
        try {
            URI uri = URI.create(raw);
            if (!"ws".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() < 1 || uri.getPort() > 65_535
                    || !DEVTOOLS_BROWSER_PATH.matcher(uri.getRawPath()).matches()) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) { return null; }
            String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
            if (!("127.0.0.1".equals(normalizedHost)
                    || "localhost".equals(normalizedHost)
                    || "::1".equals(normalizedHost)
                    || "[::1]".equals(normalizedHost)
                    || "0:0:0:0:0:0:0:1".equals(normalizedHost))) {
                return null;
            }
            String canonical = "loopback:" + uri.getPort() + uri.getRawPath();
            return new DevToolsEndpoint(canonical, uri.toASCIIString());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static ActivePortProbe probeDevToolsActivePort(Path profileDir) {
        Path activePort = profileDir.resolve("DevToolsActivePort");
        try {
            if (Files.notExists(activePort, LinkOption.NOFOLLOW_LINKS)) {
                return ActivePortProbe.absent();
            }
            if (!Files.isRegularFile(activePort, LinkOption.NOFOLLOW_LINKS)) {
                return ActivePortProbe.malformed();
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(
                    activePort, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                // The limit is enforced on the opened handle. A size check followed
                // by readAllLines would be a grow-between-check-and-read allocation
                // race and would not actually bound this witness.
                bytes = input.readNBytes(DEVTOOLS_ACTIVE_PORT_FILE_CAP + 1);
            }
            if (bytes.length == 0) { return ActivePortProbe.incomplete(); }
            if (bytes.length > DEVTOOLS_ACTIVE_PORT_FILE_CAP) {
                return ActivePortProbe.malformed();
            }
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
                return ActivePortProbe.malformed();
            }
            List<String> lines = content.lines().toList();
            if (lines.size() < 2) { return ActivePortProbe.incomplete(); }
            if (lines.size() != 2) { return ActivePortProbe.malformed(); }
            String portLine = lines.get(0).trim();
            String path = lines.get(1).trim();
            int port;
            try { port = Integer.parseInt(portLine); }
            catch (NumberFormatException invalidPort) {
                return ActivePortProbe.malformed();
            }
            if (port < 1 || port > 65_535
                    || !DEVTOOLS_BROWSER_PATH.matcher(path).matches()) {
                return ActivePortProbe.malformed();
            }
            String canonical = "loopback:" + port + path;
            return ActivePortProbe.valid(new DevToolsEndpoint(
                canonical, "ws://127.0.0.1:" + port + path));
        } catch (java.nio.file.NoSuchFileException race) {
            return ActivePortProbe.absent();
        } catch (IOException race) {
            // Chrome creates then fills the file. A transient read/replace race
            // is incomplete until the shared bootstrap deadline proves otherwise.
            return ActivePortProbe.incomplete();
        }
    }

    private static String sanitizeBootstrapLine(String raw, Path profileDir) {
        String source = raw == null ? "" : raw;
        StringBuilder printable = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            printable.append(Character.isISOControl(c) ? ' ' : c);
        }
        String line = printable.toString();
        if (STARTUP_COMMAND_LINE.matcher(line).matches()) {
            return "<command line redacted>";
        }
        line = line.replace(profileDir.toString(), "<profile>");
        line = STARTUP_URL_TOKEN.matcher(line).replaceAll("<url>");
        if (STARTUP_SENSITIVE_HEADER.matcher(line).matches()) {
            return "<sensitive startup line redacted>";
        }
        line = STARTUP_QUOTED_ABSOLUTE_PATH.matcher(line).replaceAll("<path>");
        // An unquoted path may itself contain spaces, so replacing only the
        // first token can disclose the remainder. Redact that diagnostic line
        // wholesale rather than guessing where the host path ends.
        if (STARTUP_ABSOLUTE_PATH.matcher(line).find()) {
            return "<host path line redacted>";
        }
        line = STARTUP_FLAG_ASSIGNMENT.matcher(line).replaceAll("$1=<redacted>");
        line = STARTUP_FLAG_VALUE.matcher(line).replaceAll("$1 <redacted>");
        line = STARTUP_SECRET_ASSIGNMENT.matcher(line).replaceAll("$1=<redacted>");
        line = STARTUP_ENV_ASSIGNMENT.matcher(line).replaceAll("$1=<redacted>");
        line = STARTUP_BEARER_CREDENTIAL.matcher(line).replaceAll("$1 <redacted>");
        String sanitized = line.strip();
        if (sanitized.length() > BOOTSTRAP_TAIL_LINE_CAP) {
            return sanitized.substring(0, BOOTSTRAP_TAIL_LINE_CAP) + "…";
        }
        return sanitized;
    }

    private static String awaitDevtoolsUrl(Process process, Path profileDir)
            throws IOException {
        DevToolsBootstrapResult result = observeDevToolsEndpoint(
            process, profileDir, DEFAULT_TIMEOUT_MS);
        if (result.outcome() == DevToolsBootstrapOutcome.ENDPOINT) {
            return result.webSocketUrl();
        }
        throw new IOException(bootstrapFailureMessage(result));
    }

    static String bootstrapFailureMessage(DevToolsBootstrapResult result) {
        String headline = switch (result.outcome()) {
            case PROCESS_EXITED -> "Chrome exited before publishing a DevTools endpoint"
                + (result.exitCode() >= 0 ? " (exit " + result.exitCode() + ")" : "");
            case ALIVE_TIMEOUT -> "Chrome remained alive without publishing a DevTools endpoint";
            case MALFORMED_ENDPOINT ->
                "Chrome published a malformed DevTools endpoint witness via "
                    + result.sources();
            case DISAGREEING_ENDPOINTS ->
                "Chrome published disagreeing DevTools endpoint witnesses via "
                    + result.sources();
            case ENDPOINT -> throw new IllegalArgumentException(
                "successful bootstrap has no failure message");
        };
        StringBuilder message = new StringBuilder(headline)
            .append(" after ").append(result.elapsedMillis()).append("ms");
        if (!result.stdoutTail().isBlank()) {
            message.append("; sanitized stdout tail: ").append(result.stdoutTail());
        }
        if (!result.stderrTail().isBlank()) {
            message.append("; sanitized stderr tail: ").append(result.stderrTail());
        }
        return message.toString();
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
        Deadline deadline = Deadline.afterMillis(commandTimeoutMs);
        sendWithinDeadline(msg, method, deadline);
    }

    /** Send one CDP command and block for its id-matched result. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> command(String method, String paramsJson) {
        // FAIL FAST on a transport already known dead. Without this the latch would be
        // a flag nobody reads: a caller whose drain consumed the sentinel would still
        // spend the full commandTimeout waiting for a response that can never arrive.
        // The point of the reserved slot was never "the sentinel is in the queue" — it
        // was "a blocked caller fails fast instead of sleeping out the timeout".
        if (socketClosed) {
            throw new IllegalStateException(chromeDeathReason(method));
        }
        int id = nextId++;
        StringBuilder msg = new StringBuilder(128)
            .append("{\"id\":").append(id)
            .append(",\"method\":\"").append(method).append('"')
            .append(",\"params\":").append(paramsJson);
        if (sessionId != null) {
            msg.append(",\"sessionId\":\"").append(sessionId).append('"');
        }
        msg.append('}');
        // One budget for the complete round-trip. Starting it before send is
        // load-bearing: a backpressured WebSocket send must spend the same
        // commandTimeout budget as the response it is waiting to produce.
        Deadline deadline = Deadline.afterMillis(commandTimeoutMs);
        sendWithinDeadline(msg, method, deadline);

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

    private void sendWithinDeadline(CharSequence message, String method, Deadline deadline) {
        CompletableFuture<WebSocket> sending;
        try {
            sending = ws.sendText(message, true);
            await(sending, deadline);
        } catch (TimeoutException e) {
            // A timed-out send leaves ordering unknowable. Cancel the operation
            // and abort the transport rather than letting a late write leak into
            // a later command.
            throw commandSendTimeout(method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(ws);
            throw new IllegalStateException("interrupted sending " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(chromeDeathReason("sending " + method), cause);
        } catch (RuntimeException e) {
            throw new IllegalStateException(chromeDeathReason("sending " + method), e);
        }
    }

    private IllegalStateException commandSendTimeout(String method, TimeoutException cause) {
        // sendText() futures are cancellable; abort is the deterministic fallback
        // for implementations whose cancellation cannot retract an in-flight frame.
        abort(ws);
        return new IllegalStateException("CDP timeout sending " + method, cause);
    }

    private static <T> T await(CompletableFuture<T> future, Deadline deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline.remainingNanos();
        if (remaining <= 0) {
            future.cancel(true);
            throw new TimeoutException();
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * A monotonic elapsed-time budget. It stores start + duration separately,
     * instead of adding an absolute nano deadline, so Long.MAX_VALUE millisecond
     * overrides saturate safely rather than wrapping into an immediate timeout.
     */
    private static final class Deadline {
        private final long startedNanos;
        private final long budgetNanos;

        private Deadline(long budgetNanos) {
            this.startedNanos = System.nanoTime();
            this.budgetNanos = budgetNanos;
        }

        static Deadline afterMillis(long timeoutMs) {
            long nanos = timeoutMs <= 0 ? 0 : TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            return new Deadline(nanos);
        }

        long remainingNanos() {
            long elapsed = System.nanoTime() - startedNanos;
            if (elapsed <= 0) { return budgetNanos; }
            return elapsed >= budgetNanos ? 0 : budgetNanos - elapsed;
        }

        Deadline cappedAtMillis(long timeoutMs) {
            long cap = timeoutMs <= 0
                ? 0 : TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            return new Deadline(Math.min(remainingNanos(), cap));
        }

        boolean expired() { return remainingNanos() <= 0; }
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
                consoleLog.record(type + ": " + text);
                if ("error".equals(type)) { errorLog.record("console.error: " + text); }
            }
            case "Runtime.exceptionThrown" -> {
                if (!captureConsole) { return; }
                Object desc = MiniJson.get(m,
                    "params.exceptionDetails.exception.description");
                if (desc == null) { desc = MiniJson.get(m, "params.exceptionDetails.text"); }
                errorLog.record("uncaught: " + desc);
            }
            case "Page.loadEventFired" -> pendingEvents.add(m);
            case "Network.requestWillBeSent" -> {
                // A redirect hop reuses the requestId (idempotent add); only a
                // genuinely new request grows the set. Either way it is activity.
                Object rid = MiniJson.get(m, "params.requestId");
                if (rid != null) { inFlightRequestIds.add(String.valueOf(rid)); }
                lastNetChangeNanos = System.nanoTime();
            }
            case "Network.loadingFinished", "Network.loadingFailed" -> {
                Object rid = MiniJson.get(m, "params.requestId");
                if (rid != null) { inFlightRequestIds.remove(String.valueOf(rid)); }
                lastNetChangeNanos = System.nanoTime();
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

    /**
     * A console/error buffer bounded on TWO axes: the entry count (CONSOLE_CAP)
     * AND the retained encoded-byte total (brewshot.maxConsoleBytes). The
     * byte-axis is the F-01 fix — the old entry-only cap retained a single
     * multi-MB console string whole, so 1000 huge entries was a gigabyte. A
     * too-large entry is truncated to the remaining byte budget on a character
     * boundary and stamped with a marker; once either axis trips, further entries
     * are dropped and counted. {@code dropped} is exposed via
     * {@link #consoleDropped()}/{@link #errorsDropped()}.
     */
    static final class BoundedLog {
        private final List<String> entries = new ArrayList<>();
        private long bytes;
        private boolean truncated;
        private long dropped;

        void record(String entry) {
            long maxBytes = maxConsoleBytes();
            if (truncated || entries.size() >= CONSOLE_CAP) {
                if (!truncated) {
                    entries.add("... (capped at " + CONSOLE_CAP + " entries)");
                    truncated = true;
                }
                dropped++;
                return;
            }
            long entryBytes = entry.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + entryBytes > maxBytes) {
                long remaining = Math.max(0, maxBytes - bytes);
                String clamped = truncateToBytes(entry, remaining);
                entries.add(clamped + "... (console byte budget " + maxBytes + " reached)");
                bytes = maxBytes;
                truncated = true;
                dropped++;
                return;
            }
            entries.add(entry);
            bytes += entryBytes;
        }

        void clear() {
            entries.clear();
            bytes = 0;
            truncated = false;
            dropped = 0;
        }

        List<String> view() { return List.copyOf(entries); }

        long dropped() { return dropped; }
    }

    /** Longest prefix of {@code s} whose UTF-8 encoding is {@code <= maxBytes}, on a char boundary. */
    private static String truncateToBytes(String s, long maxBytes) {
        long acc = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cw = Character.charCount(cp);
            long cb = s.substring(i, i + cw).getBytes(StandardCharsets.UTF_8).length;
            if (acc + cb > maxBytes) { break; }
            acc += cb;
            i += cw;
        }
        return s.substring(0, i);
    }

    private Map<String, Object> nextMessage(Deadline deadline, String waitingFor) {
        return nextMessageNanos(deadline.remainingNanos(), waitingFor);
    }

    private Map<String, Object> nextMessage(long deadlineMillis, String waitingFor) {
        long waitMillis = deadlineMillis - System.currentTimeMillis();
        long waitNanos = waitMillis > 0 ? TimeUnit.MILLISECONDS.toNanos(waitMillis) : 0;
        return nextMessageNanos(waitNanos, waitingFor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nextMessageNanos(long waitNanos, String waitingFor) {
        try {
            String raw = waitNanos > 0
                ? inbox.poll(waitNanos, TimeUnit.NANOSECONDS) : null;
            if (raw == null) {
                // Distinguish a dead Chrome from a merely slow page.
                if (!chrome.isAlive()) {
                    throw new IllegalStateException("Chrome exited (code "
                        + chrome.exitValue() + ") while " + waitingFor);
                }
                throw new IllegalStateException("CDP timeout waiting for " + waitingFor);
            }
            releaseInboxBudget(raw);
            if (SOCKET_CLOSED.equals(raw)) {
                socketClosed = true;
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

    /** Return a regular dequeued message's exact reservation; the sentinel was unreserved. */
    private void releaseInboxBudget(String raw) {
        if (!SOCKET_CLOSED.equals(raw)) {
            inboxBudget.release(utf8Length(raw));
        }
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
        lastNetChangeNanos = System.nanoTime();
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
        Validation.positiveLong("waitFor timeoutMs", timeoutMs);
        Deadline deadline = Deadline.afterMillis(timeoutMs);
        while (true) {
            Object v = eval("!!(" + jsPredicate + ")");
            if (Boolean.TRUE.equals(v)) { return; }
            if (deadline.expired()) {
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
        return consoleLog.view();
    }

    /** Count of console entries dropped/truncated by the entry+byte bounds since the last navigation. */
    public long consoleDropped() {
        drainInboxNonBlocking();
        return consoleLog.dropped();
    }

    /**
     * Uncaught page exceptions + console.error entries since the last
     * navigation — the one-line health assertion:
     * {@code assertEquals(List.of(), shot.errors())}.
     */
    public List<String> errors() {
        drainInboxNonBlocking();
        return errorLog.view();
    }

    /** Count of error entries dropped/truncated by the entry+byte bounds since the last navigation. */
    public long errorsDropped() {
        drainInboxNonBlocking();
        return errorLog.dropped();
    }

    /**
     * Pull any already-arrived messages through the router without blocking.
     *
     * <p>Seeing the close sentinel LATCHES {@link #socketClosed} before returning. It
     * used to just return, which consumed the queue's only sentinel and left every
     * later caller unable to learn the socket had closed.
     */
    private void drainInboxNonBlocking() {
        String raw;
        while ((raw = inbox.poll()) != null) {
            releaseInboxBudget(raw);
            if (SOCKET_CLOSED.equals(raw)) { socketClosed = true; return; }
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
        this.navTimeoutMs = Validation.positiveLong("navTimeout millis", millis);
        return this;
    }

    /**
     * Set the per-CDP-call wait budget (ms) — how long the outbound WebSocket send plus its
     * matching DevTools response may take before they are treated as a timeout. Also settable
     * via {@code BREWSHOT_COMMAND_TIMEOUT_MS}, falling back to {@code BREWSHOT_TIMEOUT_MS}
     * and then the 15s default.
     *
     * <p>Distinct from {@link #navTimeout}: that governs how long a PAGE may take to load,
     * this governs how long one CDP round-trip may take. A full-page screenshot of a tall
     * document is the motivating case — it can exceed 15s on a slow runner while navigation
     * itself was fast, and raising the navigation budget would not have helped it.
     */
    public BrewShot commandTimeout(long millis) {
        this.commandTimeoutMs = Validation.positiveLong("commandTimeout millis", millis);
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
        this.maxRecordingBytes = Validation.positiveLong("recordingHeapBudget bytes", bytes);
        return this;
    }

    /**
     * Wait until no network request has been in flight for {@code quietMillis},
     * or {@code timeoutMillis} elapses (best-effort — a convenience wait, not a
     * hard gate). {@link #open}/{@link #html} return on {@code loadEventFired},
     * which fires BEFORE async XHR/fetch settle; this bridges that gap. Network
     * is tracked from launch and the in-flight count resets per navigation.
     * A zero timeout is an intentional no-op; negative values are rejected.
     */
    public void waitForNetworkIdle(long quietMillis, long timeoutMillis) {
        Validation.nonNegativeLong("network-idle quietMillis", quietMillis);
        Validation.nonNegativeLong("network-idle timeoutMillis", timeoutMillis);
        Deadline deadline = Deadline.afterMillis(timeoutMillis);
        long quietNanos = TimeUnit.MILLISECONDS.toNanos(quietMillis);
        while (!deadline.expired()) {
            drainInboxNonBlocking();
            long quietForNanos = System.nanoTime() - lastNetChangeNanos;
            if (inFlightRequestIds.isEmpty()
                    && quietForNanos >= quietNanos) {
                return;
            }
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

    /** Sleep helper for settle waits between eval steps. Zero is an intentional no-op. */
    public void settle(long millis) {
        Validation.nonNegativeLong("settle millis", millis);
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
        Objects.requireNonNull(fmt, "fmt");
        if (fmt == ImageFormat.JPEG) {
            return "\"format\":\"jpeg\",\"quality\":"
                + Validation.intRange("jpeg quality", quality, 1, 100);
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
        byte[] bytes = Base64.getDecoder().decode(b64);
        enforceCaptureBounds(bytes); // loud, header-only, before writing the file
        // Bound FIRST, then hand the accepted bytes to main's atomic writer: an
        // over-bound capture must be refused before anything reaches the target,
        // so a rejected re-shoot still leaves the previous good artifact intact.
        ArtifactWriter.writeBytes(out, bytes);
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
        public PdfOptions {
            Validation.finiteRange("pdf scale", scale, 0.1, 2.0);
            Validation.positiveFinite("pdf paper width", paperWidthIn);
            Validation.positiveFinite("pdf paper height", paperHeightIn);
            Validation.nonNegativeFinite("pdf top margin", marginTopIn);
            Validation.nonNegativeFinite("pdf right margin", marginRightIn);
            Validation.nonNegativeFinite("pdf bottom margin", marginBottomIn);
            Validation.nonNegativeFinite("pdf left margin", marginLeftIn);
        }

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
        Objects.requireNonNull(o, "opts");
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
        ArtifactWriter.writeBytes(out, Base64.getDecoder().decode(b64));
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
        validateClipGeometry(x, y, width, height, scale);
        Map<String, Object> r = command("Page.captureScreenshot",
            "{" + captureFormatParams(fmt, quality)
                + ",\"captureBeyondViewport\":true,\"clip\":{"
                + "\"x\":" + x + ",\"y\":" + y
                + ",\"width\":" + width + ",\"height\":" + height
                + ",\"scale\":" + scale + "}}");
        byte[] bytes = Base64.getDecoder().decode((String) r.get("data"));
        enforceCaptureBounds(bytes); // loud, header-only, before the frame enters any GIF buffer
        return bytes;
    }

    static void validateClipGeometry(double x, double y, double width,
                                     double height, double scale) {
        Validation.finite("clip x", x);
        Validation.finite("clip y", y);
        Validation.positiveFinite("clip width", width);
        Validation.positiveFinite("clip height", height);
        Validation.positiveFinite("clip scale", scale);
    }

    static void validateFrameRecorder(int frames, int captureDelayMs,
                                      int playbackDelayMs) {
        Validation.positiveInt("frames", frames);
        Validation.positiveInt("captureDelayMs", captureDelayMs);
        effectiveGifDelayMs(playbackDelayMs);
    }

    /**
     * Effective per-frame delay a GIF can encode. GIF stores centiseconds, so
     * requested milliseconds are rounded to the nearest 10&nbsp;ms (half up);
     * the existing 20&nbsp;ms minimum is retained. For example, 75&nbsp;ms
     * encodes as 80&nbsp;ms. Values beyond the GIF unsigned-16-bit delay field
     * are rejected instead of wrapping.
     */
    public static int effectiveGifDelayMs(int requestedDelayMs) {
        // 65,535 centiseconds is the largest representable GIF delay.
        // Requests through 655,354 ms still round to that value; 655,355 ms
        // would round to the unrepresentable 65,536 centiseconds.
        Validation.intRange("GIF delayMs", requestedDelayMs, 1, 655_354);
        long centiseconds = Math.max(2L, (requestedDelayMs + 5L) / 10L);
        return Math.toIntExact(centiseconds * 10L);
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
     *       ≈ real time. GIF rounds to centiseconds; exact FPS is
     *       {@code 1000 / effectiveGifDelayMs(playbackDelayMs)}.</li>
     * </ul>
     * Sample a fast effect densely and replay it readably:
     * {@code recordGif(..., 60, 25, 75, out)} — 60 shots ~25ms apart, played at
     * 75ms requested (80ms encoded, 12.5fps slow-mo). See the README
     * "GIF playback speed" table.
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
        validateFrameRecorder(frames, captureDelayMs, playbackDelayMs);
        validateClipGeometry(x, y, width, height, 1.0);
        Objects.requireNonNull(beforeFrame, "beforeFrame");
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
        String sel = jsStringLiteral(cssSelector);
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

    /**
     * Quote a Java string as one JavaScript source string literal. JSON string
     * syntax is also valid here; {@link MiniJson#esc} covers quotes,
     * backslashes, controls, CR/LF, and the JavaScript line separators U+2028
     * and U+2029. Package-private for injection-focused tests.
     */
    static String jsStringLiteral(String value) {
        Objects.requireNonNull(value, "value");
        return '"' + MiniJson.esc(value) + '"';
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
        String sel = jsStringLiteral(cssSelector);
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
        Validation.positiveFinite("clip scale", scale);
        Validation.nonNegativeFinite("paddingPx", paddingPx);
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
        validateFrameRecorder(frames, captureDelayMs, playbackDelayMs);
        effectiveGifDelayMs(firstFrameDelayMs);
        Validation.positiveFinite("recording scale", scale);
        Objects.requireNonNull(beforeFrame, "beforeFrame");
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
        Validation.positiveInt("panFrames", panFrames);
        Validation.nonNegativeInt("holdFrames", holdFrames);
        effectiveGifDelayMs(playbackDelayMs);
        Validation.positiveFinite("recording scale", scale);
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
        validateFrameRecorder(frames, frameDelayMs, frameDelayMs);
        Validation.positiveFinite("recording scale", scale);
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
        Validation.positiveFinite("region scale", scale);
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
        validateFrameRecorder(frames, frameDelayMs, frameDelayMs);
        Validation.positiveFinite("recording scale", scale);
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
        Validation.finite("region fromFraction", from);
        Validation.finite("region toFraction", to);
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
        Validation.positiveInt("durationMs", durationMs);
        effectiveGifDelayMs(playbackDelayMs);
        effectiveGifDelayMs(firstFrameDelayMs);
        Validation.nonNegativeInt("maxWidth", maxWidth);
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
        boolean closeTransport = closed.compareAndSet(false, true);
        try {
            if (closeTransport) { closeWebSocket(); }
        } finally {
            // Every call retries resource cleanup. sendClose remains once-only,
            // but a prior SIGKILL/delete failure must not make close() a no-op.
            lease.cleanup(true);
        }
    }

    private void closeWebSocket() {
        Deadline deadline = Deadline.afterMillis(closeTimeoutMs);
        CompletableFuture<WebSocket> closing;
        try {
            closing = ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            await(closing, deadline);
        } catch (TimeoutException e) {
            abort(ws);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(ws);
        } catch (ExecutionException | RuntimeException e) {
            // Already closing, peer disappeared, or the implementation rejected
            // close. abort() is idempotent and guarantees no transport remains.
            abort(ws);
        }
    }

    private static void abort(WebSocket socket) {
        if (socket == null) { return; }
        try { socket.abort(); }
        catch (RuntimeException ignored) { /* best-effort transport teardown */ }
    }

    /**
     * Bounded retained-process-tree teardown shared by launch failure, close,
     * and shutdown. Every captured handle waits inside one caller-supplied
     * deadline; the caller's interrupt status is restored before return.
     */
    private static boolean terminateProcess(
            Process process, ProcessHandle parentHandle,
            List<ProcessHandle> descendantHandles,
            boolean gracefulFirst, Deadline deadline) {
        boolean[] interrupted = { Thread.interrupted() };
        try {
            if (gracefulFirst && isAlive(process) && !deadline.expired()) {
                try { process.destroy(); }
                catch (RuntimeException ignored) { }
                awaitHandles(
                    process,
                    parentHandle == null ? List.of() : List.of(parentHandle),
                    deadline.cappedAtMillis(PROCESS_CLOSE_TIMEOUT_MS),
                    interrupted);
            }

            if (!allProcessesDead(process, parentHandle, descendantHandles)
                    && !deadline.expired()) {
                // Signal every retained identity even if the parent has since
                // exited and descendants() now reports an empty snapshot.
                for (ProcessHandle handle : descendantHandles) {
                    if (handleAlive(handle)) {
                        try { handle.destroyForcibly(); }
                        catch (RuntimeException ignored) { }
                    }
                }
                if (isAlive(process)) {
                    try { process.destroyForcibly(); }
                    catch (RuntimeException ignored) { }
                } else if (parentHandle != null && handleAlive(parentHandle)) {
                    try { parentHandle.destroyForcibly(); }
                    catch (RuntimeException ignored) { }
                }
                List<ProcessHandle> tree = new ArrayList<>(descendantHandles);
                if (parentHandle != null) { tree.add(parentHandle); }
                awaitHandles(process, tree, deadline, interrupted);
            }

            // This proves only that the identities Java actually observed are
            // dead. ResourceLease separately requires external proof that no
            // unobserved/reparented member survives before releasing a profile.
            return allProcessesDead(process, parentHandle, descendantHandles);
        } finally {
            if (interrupted[0]) { Thread.currentThread().interrupt(); }
        }
    }

    private static void awaitHandles(Process process, List<ProcessHandle> handles,
                                     Deadline deadline, boolean[] interrupted) {
        List<CompletableFuture<?>> exits = new ArrayList<>();
        for (ProcessHandle handle : handles) {
            if (!handleAlive(handle)) { continue; }
            try { exits.add(handle.onExit()); }
            catch (RuntimeException ignored) { }
        }
        if (exits.isEmpty() && isAlive(process)) {
            try { exits.add(process.onExit()); }
            catch (RuntimeException ignored) { }
        }
        if (exits.isEmpty()) { return; }

        CompletableFuture<Void> all =
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new));
        while (!deadline.expired()) {
            try {
                all.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
                return;
            } catch (InterruptedException e) {
                interrupted[0] = true;
                // InterruptedException clears the flag. Continue only inside
                // this fixed cleanup deadline, then restore it in the caller.
            } catch (ExecutionException | CancellationException e) {
                return;
            } catch (TimeoutException e) {
                return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    private static boolean isAlive(Process process) {
        // A handle-only teardown (the orphan sweep) has no Process reference;
        // "no process" is dead, not indeterminate.
        if (process == null) { return false; }
        try { return process.isAlive(); }
        catch (RuntimeException ignored) { return true; }
    }

    private static boolean handleAlive(ProcessHandle handle) {
        try { return handle.isAlive(); }
        catch (RuntimeException ignored) { return true; }
    }

    /**
     * Reparented-orphan sweep, keyed on this launch's unique profile path.
     *
     * <p>The retained-handle snapshot in {@link ResourceLease} covers every
     * member an enumeration ever OBSERVED. It cannot cover a helper that was
     * already reparented before the first observation — the shape of a FAILED
     * bootstrap (Marlow's report, brewshot room 140): Chrome's launcher
     * re-execs or spawns helpers and the direct child exits ("Chrome exited
     * without a DevTools listening line") before any {@code descendants()}
     * walk runs. No walk from a dead root finds those helpers, yet they still
     * hold and rewrite the profile directory.
     *
     * <p>What DOES still identify them is argv: every process of this launch
     * carries {@code --user-data-dir=<profile>}, and the profile is a fresh
     * {@link Files#createTempDirectory} name — long, random, never reused — so
     * a full-path match cannot collide with an unrelated process. Matching on
     * the path, never on a process name, is what keeps this from being a
     * "kill anything called chrome" sweep.
     *
     * <p>Enumeration and the returned liveness are best effort:
     * {@code info().commandLine()} is empty for other users' processes, so a
     * clean sweep is evidence, not proof — which is why the caller still
     * requires an independent containment proof before releasing the profile.
     *
     * @return the handles this sweep signalled, for the caller's liveness gate
     */
    private static List<ProcessHandle> sweepOrphansByProfilePath(
            Path profileDir, Deadline deadline) {
        String needle;
        try { needle = profileDir.toAbsolutePath().toString(); }
        catch (RuntimeException ignored) { return List.of(); }
        if (needle.isEmpty()) { return List.of(); }

        ProcessHandle self = ProcessHandle.current();
        List<ProcessHandle> orphans = new ArrayList<>();
        try (var all = ProcessHandle.allProcesses()) {
            all.filter(ph -> !ph.equals(self))
                .filter(ph -> commandLineContains(ph, needle))
                .forEach(orphans::add);
        } catch (RuntimeException ignored) {
            // A racing exit mid-enumeration must not break the rest of cleanup;
            // whatever was collected before the fault is still worth killing.
        }
        for (ProcessHandle orphan : orphans) {
            try { orphan.destroyForcibly(); }
            catch (RuntimeException ignored) { /* already gone */ }
        }
        if (!orphans.isEmpty()) {
            boolean[] interrupted = { false };
            awaitHandles(null, orphans, deadline, interrupted);
            if (interrupted[0]) { Thread.currentThread().interrupt(); }
        }
        return orphans;
    }

    private static boolean commandLineContains(ProcessHandle handle, String needle) {
        try {
            return handle.info().commandLine()
                .map(commandLine -> commandLine.contains(needle))
                .orElse(false);
        } catch (RuntimeException ignored) {
            // Unreadable process info is not a match claim.
            return false;
        }
    }

    private static boolean allHandlesDead(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            if (handleAlive(handle)) { return false; }
        }
        return true;
    }

    private static boolean allProcessesDead(
            Process process, ProcessHandle parentHandle,
            List<ProcessHandle> descendantHandles) {
        if (isAlive(process)) { return false; }
        if (parentHandle != null && handleAlive(parentHandle)) { return false; }
        for (ProcessHandle handle : descendantHandles) {
            if (handleAlive(handle)) { return false; }
        }
        return true;
    }

    private static boolean profileAbsent(Path profileDir) {
        try { return Files.notExists(profileDir, LinkOption.NOFOLLOW_LINKS); }
        catch (RuntimeException ignored) { return false; }
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

    /** Number of bytes the JDK UTF-8 encoder emits for an unpaired surrogate. */
    private static final int UTF8_REPLACEMENT_BYTES =
        StandardCharsets.UTF_8.newEncoder().replacement().length;

    /**
     * Exact allocation-free length of the JDK UTF-8 encoding of {@code value}.
     * Valid surrogate pairs count as four bytes; isolated surrogates use the same
     * replacement width as {@link String#getBytes(java.nio.charset.Charset)}.
     */
    static long utf8Length(CharSequence value) {
        Objects.requireNonNull(value, "value");
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 0x7f) {
                bytes++;
            } else if (ch <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(ch)) {
                bytes += UTF8_REPLACEMENT_BYTES;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * Exact UTF-8 budget shared by the producer and consumer sides of one inbox.
     * Reservation and release are synchronized because the WebSocket callback and
     * command/drain caller are different threads.
     */
    static final class InboxBudget {
        private final long maxBytes;
        private final boolean tracked;
        private long retainedBytes;

        InboxBudget(long maxBytes) {
            this(maxBytes, true);
        }

        private InboxBudget(long maxBytes, boolean tracked) {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            this.maxBytes = maxBytes;
            this.tracked = tracked;
        }

        static InboxBudget untracked() {
            return new InboxBudget(Long.MAX_VALUE, false);
        }

        synchronized boolean tryRetain(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes must be non-negative");
            }
            if (!tracked) { return true; }
            if (bytes > maxBytes - retainedBytes) { return false; }
            retainedBytes += bytes;
            return true;
        }

        synchronized void release(long bytes) {
            if (!tracked) { return; }
            if (bytes < 0 || bytes > retainedBytes) {
                throw new IllegalStateException(
                    "CDP inbox byte accounting underflow: retained=" + retainedBytes
                        + ", release=" + bytes);
            }
            retainedBytes -= bytes;
        }

        long maxBytes() { return maxBytes; }

        synchronized long retainedBytes() { return retainedBytes; }
    }

    /**
     * WebSocket listener reassembling partial text frames into whole messages.
     * On close/error it enqueues a poison message so a blocked caller fails
     * fast ("Chrome exited") instead of sleeping out the full timeout.
     *
     * <p>Three independent F-01 ingress bounds are enforced here, all on the single
     * (serialized) WebSocket callback thread:
     * <ol>
     *   <li><b>Per-message</b> ({@link #maxMessageBytes}): a message whose reassembly
     *       buffer would cross the ceiling is dropped, its partial buffer released the
     *       moment it overflows — never materialized as a giant String.</li>
     *   <li><b>Queued message count</b> ({@link #maxInboxMessages}): the ingress queue only ever
     *       holds up to {@code maxInboxMessages} regular messages. A page emitting a
     *       flood of individually-small messages while the command thread is not draining
     *       used to grow the (default-unbounded) inbox without bound; now the newest
     *       message is DROPPED once the cap is reached. The sink's physical capacity is
     *       {@code maxInboxMessages + 1}, so one slot is always reserved for the
     *       close/error sentinel — the poison signal can never be lost to a full inbox.</li>
     *   <li><b>Queued UTF-8 bytes</b> ({@link InboxBudget#maxBytes()}): the prospective
     *       exact encoded aggregate is checked before a completed message is retained.
     *       Dequeue returns its reservation, so this bounds undrained content rather than
     *       imposing a lifetime traffic quota.</li>
     * </ol>
     * Every drop class is announced once + counted, never silent.
     */
    static final class Accumulator implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> sink;
        private final long maxMessageBytes;
        /** Cumulative cap on queued regular messages; one further slot in {@link #sink}
         *  is reserved for the close/error sentinel. */
        private final int maxInboxMessages;
        private final InboxBudget inboxBudget;
        private final StringBuilder buf = new StringBuilder();
        private long bufferedUtf8Bytes;
        private boolean overflowed;
        private long dropped;
        private long inboxDropped;
        private long inboxByteDropped;
        private boolean inboxCountDropAnnounced;
        private boolean inboxByteDropAnnounced;

        Accumulator(LinkedBlockingQueue<String> sink, long maxMessageBytes, int maxInboxMessages) {
            this(sink, maxMessageBytes, maxInboxMessages, InboxBudget.untracked());
        }

        Accumulator(LinkedBlockingQueue<String> sink, long maxMessageBytes,
                    int maxInboxMessages, InboxBudget inboxBudget) {
            this.sink = Objects.requireNonNull(sink, "sink");
            if (maxMessageBytes <= 0) {
                throw new IllegalArgumentException("maxMessageBytes must be positive");
            }
            if (maxInboxMessages <= 0) {
                throw new IllegalArgumentException("maxInboxMessages must be positive");
            }
            this.maxMessageBytes = maxMessageBytes;
            this.maxInboxMessages = maxInboxMessages;
            this.inboxBudget = Objects.requireNonNull(inboxBudget, "inboxBudget");
        }

        /**
         * The accumulation seam, split out from {@link #onText} so it is testable
         * without a live WebSocket. Appends {@code data}; on {@code last}, enqueues
         * the whole message — UNLESS the buffer crossed {@link #maxMessageBytes}
         * (dropped, buffer already released) or the inbox is already at
         * {@link #maxInboxMessages} (cumulative drop) — either drop counted.
         */
        void accept(CharSequence data, boolean last) {
            Objects.requireNonNull(data, "data");
            if (!overflowed) {
                // WebSocket.Listener guarantees every callback is a legal UTF-16
                // sequence, so exact per-callback lengths compose without boundary state.
                long addedBytes = utf8Length(data);
                if (addedBytes > maxMessageBytes - bufferedUtf8Bytes) {
                    overflowed = true;
                    resetBuffer(); // release the partial NOW — this is the whole point
                } else {
                    bufferedUtf8Bytes += addedBytes;
                    buf.append(data);
                }
            }
            if (last) {
                if (overflowed) {
                    dropped++;
                    if (dropped == 1) {
                        System.err.println("brewshot: dropped an oversized CDP message (exceeds "
                            + maxMessageBytes + "-byte UTF-8 ceiling; "
                            + "brewshot.maxCdpMessageBytes)."
                            + " Further such drops are counted, not re-announced.");
                    }
                    overflowed = false;
                } else {
                    enqueue(buf.toString(), bufferedUtf8Bytes);
                }
                resetBuffer();
            }
        }

        private void resetBuffer() {
            buf.setLength(0);
            bufferedUtf8Bytes = 0;
        }

        /**
         * Enqueue one whole (in-ceiling) message under the cumulative cap. Only
         * {@code sink.size() < maxInboxMessages} admits it; the last physical slot of
         * {@code sink} (capacity {@code maxInboxMessages + 1}) is left free so the
         * sentinel in {@link #signalClosed()} always has a home. Runs only on the
         * serialized WebSocket callback thread, so the size-check / offer pair is not
         * a race. FIFO order is preserved (sentinel goes to the tail like today), so a
         * closed socket still flushes already-queued events before the poison.
         */
        private void enqueue(String msg, long encodedBytes) {
            if (sink.size() >= maxInboxMessages) {
                recordCountDrop();
                return;
            }
            if (!inboxBudget.tryRetain(encodedBytes)) {
                recordByteDrop();
                return;
            }
            if (!sink.offer(msg)) {
                // A mismatched test/custom sink must not leak a byte reservation.
                inboxBudget.release(encodedBytes);
                recordCountDrop();
            }
        }

        private void recordCountDrop() {
            inboxDropped++;
            if (!inboxCountDropAnnounced) {
                inboxCountDropAnnounced = true;
                System.err.println("brewshot: CDP inbox full at " + maxInboxMessages
                    + " queued messages — dropping newer messages (brewshot.maxInboxMessages)."
                    + " Further count-cap drops are counted, not re-announced.");
            }
        }

        private void recordByteDrop() {
            inboxDropped++;
            inboxByteDropped++;
            if (!inboxByteDropAnnounced) {
                inboxByteDropAnnounced = true;
                System.err.println("brewshot: CDP inbox retained-byte budget full at "
                    + inboxBudget.maxBytes() + " UTF-8 bytes — dropping newer messages "
                    + "(brewshot.maxInboxBytes). Further byte-cap drops are counted, "
                    + "not re-announced.");
            }
        }

        /** Enqueue the poison sentinel into the reserved slot — never dropped even
         *  when the regular inbox is full. */
        private void signalClosed() {
            // Regular messages never exceed maxInboxMessages, so with capacity
            // maxInboxMessages + 1 this offer always succeeds.
            sink.offer(SOCKET_CLOSED);
        }

        /** Count of CDP messages dropped for exceeding the per-message byte ceiling. */
        long dropped() { return dropped; }

        /** Count of whole messages dropped for exceeding the cumulative inbox cap. */
        long inboxDropped() { return inboxDropped; }

        /** Count of the cumulative drops caused specifically by the retained-byte cap. */
        long inboxByteDropped() { return inboxByteDropped; }

        /**
         * Test seam: the sink this Accumulator was actually WIRED to.
         *
         * <p>The reserved-slot invariant is a relationship between two values decided in
         * two different places — the queue capacity chosen at launch and the cap handed
         * to this Accumulator — and {@link #signalClosed()} discards {@code offer}'s
         * boolean, so a mismatch loses the close sentinel SILENTLY. A test that builds
         * its own correctly-sized queue can never catch that; it has to inspect the one
         * production built. Package-private and read-only, like the drop counters.
         */
        LinkedBlockingQueue<String> sink() { return sink; }

        /** Test seam: the cumulative regular-message cap this Accumulator was built with. */
        int inboxCap() { return maxInboxMessages; }

        /** Test seam: the production byte tracker shared with its eventual client. */
        InboxBudget inboxBudget() { return inboxBudget; }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            accept(data, last);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            signalClosed();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            signalClosed();
        }
    }
}
