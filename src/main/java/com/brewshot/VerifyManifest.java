package com.brewshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The bounded, closed V1 model for {@code brewshot verify}.
 *
 * <p>This slice deliberately stops at authorship parsing. It does not inspect the
 * filesystem graph, launch Chrome, or write an artifact; those effects belong to the
 * later preflight/staging slices. Every returned path is nevertheless absolute,
 * normalized, and lexically contained by the manifest's parent directory, so later
 * slices receive one spelling for each authored node.
 */
final class VerifyManifest {

    static final int VERSION = 1;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    static final int MAX_JOBS = 100;
    static final int MAX_MASKS_PER_JOB = 100;
    static final int MAX_PATH_BYTES = 4096;
    static final int MAX_INLINE_JS_BYTES = 64 * 1024;
    static final int MAX_SELECTOR_BYTES = 16 * 1024;
    static final int MAX_VIEWPORT_DIMENSION = 16_384;
    static final long MAX_VIEWPORT_PIXELS = 67_108_864L;
    static final long MAX_WAIT_MILLIS = 600_000L;
    static final double MAX_SCALE = 4.0;
    static final double MAX_CLIP_PADDING = 16_384.0;

    private static final Pattern JOB_ID =
        Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");
    private static final Pattern URI_SCHEME =
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    private static final Set<String> ROOT_FIELDS = Set.of("version", "jobs");
    private static final Set<String> JOB_FIELDS = Set.of(
        "id", "input", "baseline", "receipt", "heatmap", "capture", "diff");
    private static final Set<String> CAPTURE_FIELDS = Set.of(
        "width", "height", "settleMs", "waitJs", "waitTimeoutMs", "clipSelector",
        "scale", "clipPadding", "colorScheme", "media", "reducedMotion");
    private static final Set<String> DIFF_FIELDS = Set.of(
        "tolerance", "ignoreAntialiasing", "masks", "failOverPct", "failPixels");
    private static final Set<String> MASK_FIELDS = Set.of("x", "y", "width", "height");

    private final Path source;
    private final Path workspaceRoot;
    private final String sourceSha256;
    private final List<Job> jobs;

    private VerifyManifest(Path source, Path workspaceRoot, String sourceSha256, List<Job> jobs) {
        this.source = Objects.requireNonNull(source, "source");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        this.jobs = List.copyOf(jobs);
    }

    Path source() {
        return source;
    }

    Path workspaceRoot() {
        return workspaceRoot;
    }

    String sourceSha256() {
        return sourceSha256;
    }

    List<Job> jobs() {
        return jobs;
    }

    /** Read and validate one existing, locally-authored manifest without side effects. */
    static VerifyManifest load(Path manifest) throws ManifestException {
        Objects.requireNonNull(manifest, "manifest");
        Path source = manifest.toAbsolutePath().normalize();
        Path workspace = source.getParent();
        if (workspace == null) {
            throw failure("manifest has no parent directory: " + manifest, null);
        }
        try {
            String json = BoundedUtf8.readStrict(
                source, MAX_MANIFEST_BYTES, "verify manifest " + source);
            String sourceSha256 = sha256(json.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> root = object(MiniJson.parseStrict(json), "manifest root");
            rejectUnknown(root, ROOT_FIELDS, "manifest root");
            int version = intValue(required(root, "version", "manifest root"),
                "manifest version", Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (version != VERSION) {
                throw problem("manifest version must be " + VERSION);
            }
            List<Object> rawJobs = array(required(root, "jobs", "manifest root"), "jobs");
            if (rawJobs.isEmpty()) {
                throw problem("jobs must contain at least one job");
            }
            if (rawJobs.size() > MAX_JOBS) {
                throw problem("jobs exceeds the " + MAX_JOBS + "-job limit");
            }

            List<Job> jobs = new ArrayList<>(rawJobs.size());
            Set<String> ids = new LinkedHashSet<>();
            Map<Path, String> logicalOutputs = new LinkedHashMap<>();
            for (int index = 0; index < rawJobs.size(); index++) {
                Job job = parseJob(rawJobs.get(index), index, workspace);
                if (!ids.add(job.id())) {
                    throw problem("duplicate job id: " + job.id());
                }
                registerOutput(logicalOutputs, job.id(), "baseline", job.baseline(), workspace);
                registerOutput(logicalOutputs, job.id(), "receipt", job.receipt(), workspace);
                if (job.heatmap() != null) {
                    registerOutput(logicalOutputs, job.id(), "heatmap", job.heatmap(), workspace);
                }
                jobs.add(job);
            }
            return new VerifyManifest(source, workspace, sourceSha256, jobs);
        } catch (ManifestProblem invalid) {
            throw failure(invalid.getMessage(), invalid);
        } catch (IOException | IllegalArgumentException invalid) {
            throw failure(invalid.getMessage(), invalid);
        }
    }

    private static Job parseJob(Object raw, int index, Path workspace) {
        String context = "job[" + index + "]";
        Map<String, Object> fields = object(raw, context);
        rejectUnknown(fields, JOB_FIELDS, context);
        String id = string(required(fields, "id", context), context + ".id", 80);
        if (!JOB_ID.matcher(id).matches()) {
            throw problem(context + ".id must match [a-z0-9][a-z0-9._-]{0,79}, got: " + id);
        }

        Path input = relativePath(required(fields, "input", context), context + ".input",
            workspace, Set.of(".html", ".htm"));
        Path baseline = relativePath(required(fields, "baseline", context),
            context + ".baseline", workspace, Set.of(".png"));
        Path receipt = relativePath(required(fields, "receipt", context),
            context + ".receipt", workspace, Set.of(".json"));
        Path heatmap = fields.containsKey("heatmap")
            ? relativePath(fields.get("heatmap"), context + ".heatmap", workspace, Set.of(".png"))
            : null;
        CaptureOptions capture = parseCapture(
            fields.containsKey("capture") ? fields.get("capture") : Map.of(),
            context + ".capture");
        DiffOptions diff = parseDiff(
            fields.containsKey("diff") ? fields.get("diff") : Map.of(),
            context + ".diff");
        return new Job(id, input, baseline, receipt, heatmap, capture, diff);
    }

    private static CaptureOptions parseCapture(Object raw, String context) {
        Map<String, Object> fields = object(raw, context);
        rejectUnknown(fields, CAPTURE_FIELDS, context);
        int width = optionalInt(fields, "width", context, 1280, 1, MAX_VIEWPORT_DIMENSION);
        int height = optionalInt(fields, "height", context, 900, 1, MAX_VIEWPORT_DIMENSION);
        if ((long) width * height > MAX_VIEWPORT_PIXELS) {
            throw problem(context + " viewport exceeds the " + MAX_VIEWPORT_PIXELS
                + "-pixel limit: " + width + "x" + height);
        }
        long settleMs = optionalLong(fields, "settleMs", context, 800L, 0, MAX_WAIT_MILLIS);
        String waitJs = optionalString(fields, "waitJs", context, MAX_INLINE_JS_BYTES);
        long waitTimeoutMs = optionalLong(
            fields, "waitTimeoutMs", context, 10_000L, 1, MAX_WAIT_MILLIS);
        if (waitJs == null && fields.containsKey("waitTimeoutMs")) {
            throw problem(context + ".waitTimeoutMs requires waitJs");
        }
        String clipSelector = optionalString(
            fields, "clipSelector", context, MAX_SELECTOR_BYTES);
        double scale = optionalDouble(fields, "scale", context, 1.0, 0.01, MAX_SCALE);
        double clipPadding = optionalDouble(
            fields, "clipPadding", context, 0.0, 0.0, MAX_CLIP_PADDING);
        if (clipSelector == null && fields.containsKey("clipPadding")) {
            throw problem(context + ".clipPadding requires clipSelector");
        }
        String colorScheme = optionalEnum(fields, "colorScheme", context, "dark", "light");
        String media = optionalEnum(fields, "media", context, "print", "screen");
        boolean reducedMotion = optionalBoolean(fields, "reducedMotion", context, false);
        return new CaptureOptions(width, height, settleMs, waitJs, waitTimeoutMs,
            clipSelector, scale, clipPadding, colorScheme, media, reducedMotion);
    }

    private static DiffOptions parseDiff(Object raw, String context) {
        Map<String, Object> fields = object(raw, context);
        rejectUnknown(fields, DIFF_FIELDS, context);
        int tolerance = optionalInt(fields, "tolerance", context,
            BrewShotDiff.DEFAULT_TOLERANCE, 0, 254);
        boolean ignoreAntialiasing = optionalBoolean(
            fields, "ignoreAntialiasing", context, true);
        List<int[]> masks = fields.containsKey("masks")
            ? parseMasks(fields.get("masks"), context + ".masks") : List.of();
        Double failOverPct = fields.containsKey("failOverPct")
            ? doubleValue(fields.get("failOverPct"), context + ".failOverPct", 0, 100)
            : null;
        Long failPixels = fields.containsKey("failPixels")
            ? longValue(fields.get("failPixels"), context + ".failPixels",
                0, MAX_VIEWPORT_PIXELS)
            : null;
        // A visual verification command must gate by default. Authors may replace
        // the exact-pixel default with a percentage gate, or explicitly supply both.
        if (failOverPct == null && failPixels == null) {
            failPixels = 0L;
        }
        return new DiffOptions(
            new BrewShotDiff.Options(tolerance, ignoreAntialiasing, masks),
            failOverPct, failPixels);
    }

    private static List<int[]> parseMasks(Object raw, String context) {
        List<Object> values = array(raw, context);
        if (values.size() > MAX_MASKS_PER_JOB) {
            throw problem(context + " exceeds the " + MAX_MASKS_PER_JOB + "-mask limit");
        }
        List<int[]> masks = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String maskContext = context + "[" + index + "]";
            Map<String, Object> mask = object(values.get(index), maskContext);
            rejectUnknown(mask, MASK_FIELDS, maskContext);
            int x = intValue(required(mask, "x", maskContext), maskContext + ".x",
                Integer.MIN_VALUE, Integer.MAX_VALUE);
            int y = intValue(required(mask, "y", maskContext), maskContext + ".y",
                Integer.MIN_VALUE, Integer.MAX_VALUE);
            int width = intValue(required(mask, "width", maskContext),
                maskContext + ".width", 1, Integer.MAX_VALUE);
            int height = intValue(required(mask, "height", maskContext),
                maskContext + ".height", 1, Integer.MAX_VALUE);
            masks.add(new int[] {x, y, width, height});
        }
        return masks;
    }

    private static Path relativePath(Object raw, String context, Path workspace,
                                     Set<String> extensions) {
        String authored = string(raw, context, MAX_PATH_BYTES);
        if (URI_SCHEME.matcher(authored).matches()) {
            throw problem(context + " must be a local relative path, not a URL: " + authored);
        }
        try {
            Path relative = Path.of(authored);
            if (relative.isAbsolute()) {
                throw problem(context + " must be relative to the manifest directory: " + authored);
            }
            Path normalized = relative.normalize();
            if (normalized.toString().isEmpty()) {
                throw problem(context + " must name a file");
            }
            Path resolved = workspace.resolve(normalized).normalize();
            if (!resolved.startsWith(workspace)) {
                throw problem(context + " escapes the manifest directory: " + authored);
            }
            String filename = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
            if (extensions.stream().noneMatch(filename::endsWith)) {
                throw problem(context + " must use " + String.join(" or ", extensions)
                    + ", got: " + authored);
            }
            return resolved;
        } catch (InvalidPathException invalid) {
            throw problem(context + " is not a valid local path: " + invalid.getInput());
        }
    }

    private static void registerOutput(Map<Path, String> outputs, String jobId, String role,
                                       Path path, Path workspace) {
        String owner = "job " + jobId + " " + role;
        String previous = outputs.putIfAbsent(path, owner);
        if (previous != null) {
            throw problem("duplicate logical output " + workspace.relativize(path)
                + ": " + previous + " and " + owner);
        }
    }

    private static Object required(Map<String, Object> fields, String name, String context) {
        if (!fields.containsKey(name)) {
            throw problem(context + " is missing required field: " + name);
        }
        return fields.get(name);
    }

    private static void rejectUnknown(Map<String, Object> fields, Set<String> allowed,
                                      String context) {
        TreeSet<String> unknown = new TreeSet<>(fields.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw problem(context + " has unknown field(s): " + String.join(", ", unknown));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String context) {
        if (!(value instanceof Map<?, ?> map)) {
            throw problem(context + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String context) {
        if (!(value instanceof List<?> list)) {
            throw problem(context + " must be an array");
        }
        return (List<Object>) list;
    }

    private static String string(Object value, String context, int maxBytes) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw problem(context + " must be a non-blank string");
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw problem(context + " exceeds the " + maxBytes + "-byte limit");
        }
        return text;
    }

    private static String optionalString(Map<String, Object> fields, String name,
                                         String context, int maxBytes) {
        return fields.containsKey(name)
            ? string(fields.get(name), context + "." + name, maxBytes) : null;
    }

    private static String optionalEnum(Map<String, Object> fields, String name,
                                       String context, String... allowed) {
        if (!fields.containsKey(name)) {
            return null;
        }
        String value = string(fields.get(name), context + "." + name, 32);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw problem(context + "." + name + " must be one of "
            + String.join(", ", allowed) + ", got: " + value);
    }

    private static boolean optionalBoolean(Map<String, Object> fields, String name,
                                           String context, boolean defaultValue) {
        if (!fields.containsKey(name)) {
            return defaultValue;
        }
        Object value = fields.get(name);
        if (!(value instanceof Boolean bool)) {
            throw problem(context + "." + name + " must be a boolean");
        }
        return bool;
    }

    private static int optionalInt(Map<String, Object> fields, String name, String context,
                                   int defaultValue, int min, int max) {
        return fields.containsKey(name)
            ? intValue(fields.get(name), context + "." + name, min, max) : defaultValue;
    }

    private static long optionalLong(Map<String, Object> fields, String name, String context,
                                     long defaultValue, long min, long max) {
        return fields.containsKey(name)
            ? longValue(fields.get(name), context + "." + name, min, max) : defaultValue;
    }

    private static double optionalDouble(Map<String, Object> fields, String name,
                                         String context, double defaultValue,
                                         double min, double max) {
        return fields.containsKey(name)
            ? doubleValue(fields.get(name), context + "." + name, min, max) : defaultValue;
    }

    private static int intValue(Object value, String context, int min, int max) {
        long parsed = longValue(value, context, min, max);
        return (int) parsed;
    }

    private static long longValue(Object value, String context, long min, long max) {
        if (!(value instanceof Double number) || !Double.isFinite(number)
                || number != Math.rint(number) || number < min || number > max) {
            throw problem(context + " must be an integer in " + min + ".." + max
                + ", got: " + value);
        }
        return (long) number.doubleValue();
    }

    private static double doubleValue(Object value, String context, double min, double max) {
        if (!(value instanceof Double number) || !Double.isFinite(number)
                || number < min || number > max) {
            throw problem(context + " must be a finite number in " + min + ".." + max
                + ", got: " + value);
        }
        return number;
    }

    private static ManifestProblem problem(String message) {
        return new ManifestProblem(message);
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256 provider", impossible);
        }
    }

    private static ManifestException failure(String message, Throwable cause) {
        String detail = message == null || message.isBlank() ? "unknown manifest failure" : message;
        return new ManifestException(new Failure(
            FailureCategory.MANIFEST, null, detail), cause);
    }

    private static void requireCanonicalAbsolutePath(String name, Path path) {
        Objects.requireNonNull(path, name);
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException(name + " must be absolute and normalized: " + path);
        }
    }

    private static void requireOptionalBoundedText(String name, String value, int maxBytes) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be null or non-blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(name + " exceeds the " + maxBytes + "-byte limit");
        }
    }

    record Job(String id, Path input, Path baseline, Path receipt, Path heatmap,
               CaptureOptions capture, DiffOptions diff) {
        Job {
            if (id == null || !JOB_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid verify job id: " + id);
            }
            requireCanonicalAbsolutePath("input", input);
            requireCanonicalAbsolutePath("baseline", baseline);
            requireCanonicalAbsolutePath("receipt", receipt);
            if (heatmap != null) {
                requireCanonicalAbsolutePath("heatmap", heatmap);
            }
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(diff, "diff");
        }
    }

    record CaptureOptions(int width, int height, long settleMs, String waitJs,
                          long waitTimeoutMs, String clipSelector, double scale,
                          double clipPadding, String colorScheme, String media,
                          boolean reducedMotion) {
        CaptureOptions {
            Validation.intRange("verify width", width, 1, MAX_VIEWPORT_DIMENSION);
            Validation.intRange("verify height", height, 1, MAX_VIEWPORT_DIMENSION);
            if ((long) width * height > MAX_VIEWPORT_PIXELS) {
                throw new IllegalArgumentException("verify viewport exceeds the "
                    + MAX_VIEWPORT_PIXELS + "-pixel limit: " + width + "x" + height);
            }
            if (settleMs < 0 || settleMs > MAX_WAIT_MILLIS) {
                throw new IllegalArgumentException("verify settleMs must be 0.."
                    + MAX_WAIT_MILLIS + ", got: " + settleMs);
            }
            if (waitTimeoutMs < 1 || waitTimeoutMs > MAX_WAIT_MILLIS) {
                throw new IllegalArgumentException("verify waitTimeoutMs must be 1.."
                    + MAX_WAIT_MILLIS + ", got: " + waitTimeoutMs);
            }
            requireOptionalBoundedText("verify waitJs", waitJs, MAX_INLINE_JS_BYTES);
            requireOptionalBoundedText(
                "verify clipSelector", clipSelector, MAX_SELECTOR_BYTES);
            Validation.finiteRange("verify scale", scale, 0.01, MAX_SCALE);
            Validation.finiteRange(
                "verify clipPadding", clipPadding, 0, MAX_CLIP_PADDING);
            if (clipSelector == null && clipPadding != 0) {
                throw new IllegalArgumentException(
                    "verify clipPadding requires clipSelector");
            }
            if (colorScheme != null && !Set.of("dark", "light").contains(colorScheme)) {
                throw new IllegalArgumentException(
                    "verify colorScheme must be dark or light, got: " + colorScheme);
            }
            if (media != null && !Set.of("print", "screen").contains(media)) {
                throw new IllegalArgumentException(
                    "verify media must be print or screen, got: " + media);
            }
        }
    }

    record DiffOptions(BrewShotDiff.Options options, Double failOverPct, Long failPixels) {
        DiffOptions {
            Objects.requireNonNull(options, "options");
            if (failOverPct == null && failPixels == null) {
                throw new IllegalArgumentException("diff options require at least one visual gate");
            }
            if (failOverPct != null) {
                Validation.finiteRange("verify failOverPct", failOverPct, 0, 100);
            }
            if (failPixels != null && (failPixels < 0 || failPixels > MAX_VIEWPORT_PIXELS)) {
                throw new IllegalArgumentException("verify failPixels must be 0.."
                    + MAX_VIEWPORT_PIXELS + ", got: " + failPixels);
            }
        }
    }

    enum FailureCategory {
        NONE(0, 0),
        DIFF(1, 1),
        CAPTURE(1, 2),
        COMMIT(1, 3),
        PREFLIGHT(2, 4),
        MANIFEST(2, 5),
        THRESHOLD(4, 6);

        private final int exitCode;
        private final int tiePrecedence;

        FailureCategory(int exitCode, int tiePrecedence) {
            this.exitCode = exitCode;
            this.tiePrecedence = tiePrecedence;
        }

        int exitCode() {
            return exitCode;
        }

        static FailureCategory worst(FailureCategory left, FailureCategory right) {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (left.exitCode != right.exitCode) {
                return left.exitCode > right.exitCode ? left : right;
            }
            return left.tiePrecedence >= right.tiePrecedence ? left : right;
        }
    }

    record Failure(FailureCategory category, String jobId, String message) {
        Failure {
            Objects.requireNonNull(category, "category");
            if (jobId != null && jobId.isBlank()) {
                throw new IllegalArgumentException("failure jobId must be null or non-blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("failure message must be non-blank");
            }
        }
    }

    static int worstExit(Iterable<Failure> failures) {
        Objects.requireNonNull(failures, "failures");
        FailureCategory worst = FailureCategory.NONE;
        for (Failure failure : failures) {
            worst = FailureCategory.worst(worst, Objects.requireNonNull(failure, "failure").category());
        }
        return worst.exitCode();
    }

    static final class ManifestException extends Exception {
        private final Failure failure;

        ManifestException(Failure failure, Throwable cause) {
            super("verify manifest: " + failure.message(), cause);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    private static final class ManifestProblem extends RuntimeException {
        ManifestProblem(String message) {
            super(message);
        }
    }
}
