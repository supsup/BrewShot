package com.brewshot;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Preparation only: worker-owned immutable input and empty, private scratch. */
final class BrewShotWatchJob {
    private static final int MAX_ARCHIVE = 16 * 1024 * 1024;
    private static final int MAX_MANIFEST = 64 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final long MAX_EXPANDED = 64L * 1024 * 1024;
    private static final int MAX_ENTRY = 16 * 1024 * 1024;

    private BrewShotWatchJob() { }

    record Prepared(String format, List<String> arguments) {
        Prepared { arguments = List.copyOf(arguments); }
    }

    /** Exceptions deliberately contain codes only, never caller-controlled text. */
    static Prepared prepare(Path source, Path scratch) throws IOException {
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("JOB_IO");
            }
            try (var children = Files.list(scratch)) {
                if (children.findAny().isPresent()) { throw failure("JOB_SCRATCH"); }
            }
            String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
            Path assets = null;
            String text;
            if (name.endsWith(".brewshot.zip")) {
                byte[] archive = readBounded(source, MAX_ARCHIVE);
                List<Member> members = inspectArchive(archive);
                assets = scratch.toAbsolutePath().resolve("assets");
                extract(source, assets, members);
                text = BoundedUtf8.readStrict(assets.resolve("job.json"),
                    MAX_MANIFEST, "manifest");
            } else if (name.endsWith(".brewshot.json")) {
                if (Files.size(source) > MAX_MANIFEST) { throw failure("JOB_LIMIT"); }
                text = BoundedUtf8.readStrict(source, MAX_MANIFEST, "manifest");
            } else {
                throw failure("JOB_EXTENSION");
            }
            Object parsed;
            try { parsed = MiniJson.parseStrict(text); }
            catch (IllegalArgumentException malformed) { throw failure("JOB_JSON_INVALID"); }
            return manifest(parsed, assets);
        } catch (JobFailure controlled) {
            throw controlled;
        } catch (IOException unreadable) {
            throw failure("JOB_IO");
        } catch (RuntimeException malformed) {
            throw failure("JOB_SCHEMA");
        }
    }

    private static Prepared manifest(Object parsed, Path assets) throws IOException {
        Map<?, ?> root = object(parsed, Set.of("version", "source", "capture", "prepareScript"));
        if (!(root.get("version") instanceof Double version) || version != 1.0) {
            throw failure("JOB_SCHEMA");
        }
        Map<?, ?> source = object(root.get("source"),
            assets == null ? Set.of("url") : Set.of("file"));
        if (source.size() != 1) { throw failure("JOB_SCHEMA"); }
        String input;
        if (assets == null) {
            input = string(source.get("url"));
            URI uri;
            try { uri = new URI(input); }
            catch (java.net.URISyntaxException malformed) { throw failure("JOB_URL"); }
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw failure("JOB_URL");
            }
            // Main's URL-mode recognizer expects a lower-case scheme. Preserve
            // the rest verbatim, including escaped path and query bytes.
            input = uri.getScheme().toLowerCase(Locale.ROOT) + input.substring(input.indexOf(':'));
        } else {
            Path file = bundleFile(assets, string(source.get("file")));
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".html") || lower.endsWith(".htm"))) {
                throw failure("JOB_PATH");
            }
            input = file.toString();
        }
        Map<?, ?> capture = object(root.get("capture"), Set.of("format", "frames", "delayMs",
            "viewport", "dpr", "settleMs", "selector", "scale"));
        String format = string(capture.get("format"));
        if (!(format.equals("gif") || format.equals("png"))) { throw failure("JOB_SCHEMA"); }
        List<String> args = new ArrayList<>();
        args.add(input);
        if (format.equals("gif")) {
            add(args, "--gif", integer(capture.get("frames")));
            optionalNumber(capture, "delayMs", "--gif-delay", true, args);
        } else if (capture.containsKey("frames") || capture.containsKey("delayMs")) {
            throw failure("JOB_SCHEMA");
        }
        if (capture.containsKey("viewport")) {
            Map<?, ?> viewport = object(capture.get("viewport"), Set.of("width", "height"));
            add(args, "--size", integer(viewport.get("width")) + "x"
                + integer(viewport.get("height")));
        }
        optionalNumber(capture, "dpr", "--dpr", true, args);
        optionalNumber(capture, "settleMs", "--settle", true, args);
        if (capture.containsKey("selector")) {
            add(args, format.equals("gif") ? "--gif-element" : "--clip-selector",
                string(capture.get("selector")));
        }
        optionalNumber(capture, "scale", "--scale", false, args);
        if (root.containsKey("prepareScript")) {
            if (assets == null) { throw failure("JOB_SCHEMA"); }
            Path script = bundleFile(assets, string(root.get("prepareScript")));
            if (!script.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".js")) {
                throw failure("JOB_PATH");
            }
            // Same byte cap and decoding as Main's --eval-file; no independent JS grammar.
            if (Files.size(script) > Main.MAX_EVAL_FILE_BYTES) { throw failure("JOB_LIMIT"); }
            BoundedUtf8.read(script, Main.MAX_EVAL_FILE_BYTES, "script");
            add(args, "--eval-file", script.toString());
        }
        return new Prepared(format, args);
    }

    private static Map<?, ?> object(Object value, Set<String> keys) throws IOException {
        if (!(value instanceof Map<?, ?> map) || !keys.containsAll(map.keySet())) {
            throw failure("JOB_SCHEMA");
        }
        return map;
    }

    private static String string(Object value) throws IOException {
        if (!(value instanceof String string)) { throw failure("JOB_SCHEMA"); }
        return string;
    }

    private static String integer(Object value) throws IOException {
        if (!(value instanceof Double number) || !Double.isFinite(number)
                || number != Math.rint(number)) { throw failure("JOB_SCHEMA"); }
        // MiniJson's numeric domain is Double. Emit plain decimal integer tokens,
        // including out-of-range values, for Main's single CLI validator to decide.
        return BigDecimal.valueOf(number).toBigIntegerExact().toString();
    }

    private static void optionalNumber(Map<?, ?> map, String key, String flag,
                                       boolean integral, List<String> args) throws IOException {
        if (!map.containsKey(key)) { return; }
        Object value = map.get(key);
        String token;
        if (integral) { token = integer(value); }
        else {
            if (!(value instanceof Double number) || !Double.isFinite(number)) {
                throw failure("JOB_SCHEMA");
            }
            token = Double.toString(number);
        }
        add(args, flag, token);
    }

    private static void add(List<String> args, String flag, String value) {
        args.add(flag);
        args.add(value);
    }

    private static Path bundleFile(Path assets, String name) throws IOException {
        safeName(name, false);
        Path path = assets.resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw failure("JOB_PATH"); }
        return path;
    }

    private record Member(String name, boolean directory, long size, long compressed,
                          long crc, int localOffset, int method, int flags, byte[] rawName) { }

    /**
     * Inspect the bounded central directory because ZipEntry does not expose Unix
     * file modes. Crosscheck local headers as well: no alternate names, encryption,
     * hidden local members, ZIP64, special files, or overlapping records.
     */
    private static List<Member> inspectArchive(byte[] bytes) throws IOException {
        int end = -1;
        for (int p = bytes.length - 22; p >= Math.max(0, bytes.length - 65557); p--) {
            if (u32(bytes, p) == 0x06054b50L && p + 22L + u16(bytes, p + 20) == bytes.length) {
                end = p;
                break;
            }
        }
        if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0
                || u16(bytes, end + 8) != u16(bytes, end + 10)) {
            throw failure("JOB_ZIP_INVALID");
        }
        int count = u16(bytes, end + 10);
        long centralSize = u32(bytes, end + 12);
        long centralStart = u32(bytes, end + 16);
        if (count == 0xffff || centralSize == 0xffffffffL || centralStart == 0xffffffffL) {
            throw failure("JOB_ZIP_INVALID");
        }
        if (count == 0 || count > MAX_ENTRIES) { throw failure("JOB_LIMIT"); }
        if (centralStart + centralSize != end) { throw failure("JOB_ZIP_INVALID"); }
        int at = (int) centralStart;
        List<Member> members = new ArrayList<>();
        Map<String, Boolean> names = new HashMap<>();
        long total = 0;
        for (int index = 0; index < count; index++) {
            require(bytes, at, 46);
            if (u32(bytes, at) != 0x02014b50L) { throw failure("JOB_ZIP_INVALID"); }
            int flags = u16(bytes, at + 8);
            int method = u16(bytes, at + 10);
            long crc = u32(bytes, at + 16);
            long compressed = u32(bytes, at + 20);
            long size = u32(bytes, at + 24);
            int nameLength = u16(bytes, at + 28);
            int extraLength = u16(bytes, at + 30);
            int commentLength = u16(bytes, at + 32);
            long localOffset = u32(bytes, at + 42);
            if ((flags & ~(0x800 | 8)) != 0 || (method != 0 && method != 8)
                    || u16(bytes, at + 34) != 0 || compressed == 0xffffffffL
                    || size == 0xffffffffL || localOffset == 0xffffffffL) {
                throw failure("JOB_ZIP_INVALID");
            }
            require(bytes, at + 46, nameLength + extraLength + commentLength);
            byte[] rawName = Arrays.copyOfRange(bytes, at + 46, at + 46 + nameLength);
            String name = decodeName(rawName);
            boolean directory = name.endsWith("/");
            safeName(name, directory);
            int mode = (int) ((u32(bytes, at + 38) >>> 16) & 0xffff);
            int type = mode & 0170000;
            if (type != 0 && type != (directory ? 0040000 : 0100000)
                    || !directory && (u32(bytes, at + 38) & 0x10) != 0) {
                throw failure("JOB_ZIP_INVALID");
            }
            String key = directory ? name.substring(0, name.length() - 1) : name;
            if (names.putIfAbsent(key, directory) != null) { throw failure("JOB_ZIP_INVALID"); }
            extras(bytes, at + 46 + nameLength, extraLength);
            if (size > MAX_ENTRY || (total += size) > MAX_EXPANDED
                    || name.equals("job.json") && size > MAX_MANIFEST) {
                throw failure("JOB_LIMIT");
            }
            if (directory && (size != 0 || crc != 0)) { throw failure("JOB_ZIP_INVALID"); }
            members.add(new Member(name, directory, size, compressed, crc,
                (int) localOffset, method, flags, rawName));
            at += 46 + nameLength + extraLength + commentLength;
        }
        if (at != end || !Boolean.FALSE.equals(names.get("job.json"))) {
            throw failure("JOB_ZIP_INVALID");
        }
        for (String name : names.keySet()) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                if (Boolean.FALSE.equals(names.get(name.substring(0, slash)))) {
                    throw failure("JOB_ZIP_INVALID");
                }
            }
        }
        List<Member> localOrder = new ArrayList<>(members);
        localOrder.sort(java.util.Comparator.comparingInt(Member::localOffset));
        long next = 0;
        for (Member member : localOrder) {
            int local = member.localOffset;
            if (next != local) { throw failure("JOB_ZIP_INVALID"); }
            require(bytes, local, 30);
            int nameLength = u16(bytes, local + 26);
            int extraLength = u16(bytes, local + 28);
            require(bytes, local + 30, nameLength + extraLength);
            if (u32(bytes, local) != 0x04034b50L || u16(bytes, local + 6) != member.flags
                    || u16(bytes, local + 8) != member.method
                    || u32(bytes, local + 18) == 0xffffffffL
                    || u32(bytes, local + 22) == 0xffffffffL
                    || !Arrays.equals(member.rawName,
                        Arrays.copyOfRange(bytes, local + 30, local + 30 + nameLength))) {
                throw failure("JOB_ZIP_INVALID");
            }
            extras(bytes, local + 30 + nameLength, extraLength);
            next = local + 30L + nameLength + extraLength + member.compressed;
            if (next > centralStart) { throw failure("JOB_ZIP_INVALID"); }
            if ((member.flags & 8) == 0) {
                if (u32(bytes, local + 14) != member.crc
                        || u32(bytes, local + 18) != member.compressed
                        || u32(bytes, local + 22) != member.size) {
                    throw failure("JOB_ZIP_INVALID");
                }
            } else {
                int descriptor = (int) next;
                if (u32(bytes, descriptor) == 0x08074b50L) { descriptor += 4; }
                if (u32(bytes, descriptor) != member.crc
                        || u32(bytes, descriptor + 4) != member.compressed
                        || u32(bytes, descriptor + 8) != member.size) {
                    throw failure("JOB_ZIP_INVALID");
                }
                next = descriptor + 12L;
            }
        }
        if (next != centralStart) { throw failure("JOB_ZIP_INVALID"); }
        return members;
    }

    private static void extras(byte[] bytes, int at, int length) throws IOException {
        int end = at + length;
        while (at < end) {
            if (at + 4 > end) { throw failure("JOB_ZIP_INVALID"); }
            int id = u16(bytes, at);
            int size = u16(bytes, at + 2);
            if (id == 1 || at + 4L + size > end) { throw failure("JOB_ZIP_INVALID"); }
            at += 4 + size;
        }
    }

    private static String decodeName(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) { throw failure("JOB_ZIP_INVALID"); }
    }

    private static void safeName(String name, boolean directory) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw failure("JOB_PATH");
        }
        String stripped = directory ? name.substring(0, name.length() - 1) : name;
        for (String component : stripped.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw failure("JOB_PATH");
            }
        }
    }

    private static void extract(Path source, Path assets, List<Member> members) throws IOException {
        Files.createDirectory(assets);
        long total = 0;
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            for (Member member : members) {
                Path target = assets.resolve(member.name);
                if (member.directory) { Files.createDirectories(target); }
                else { Files.createDirectories(target.getParent()); }
                ZipEntry entry = zip.getEntry(member.name);
                if (entry == null) { throw failure("JOB_ZIP_INVALID"); }
                CRC32 crc = new CRC32();
                long count = 0;
                try (InputStream in = zip.getInputStream(entry);
                     var out = member.directory ? java.io.OutputStream.nullOutputStream()
                         : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        count += n;
                        total += n;
                        if (count > MAX_ENTRY || count > member.size || total > MAX_EXPANDED) {
                            throw failure("JOB_LIMIT");
                        }
                        crc.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                if (count != member.size || crc.getValue() != member.crc) {
                    throw failure("JOB_ZIP_INVALID");
                }
            }
        }
    }

    private static byte[] readBounded(Path source, int max) throws IOException {
        if (Files.size(source) > max) { throw failure("JOB_LIMIT"); }
        try (InputStream in = Files.newInputStream(source)) {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) { throw failure("JOB_LIMIT"); }
            return bytes;
        }
    }

    private static int u16(byte[] bytes, int offset) throws IOException {
        require(bytes, offset, 2);
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) throws IOException {
        require(bytes, offset, 4);
        return (long) u16(bytes, offset) | ((long) u16(bytes, offset + 2) << 16);
    }

    private static void require(byte[] bytes, int offset, int size) throws IOException {
        if (offset < 0 || size < 0 || offset + (long) size > bytes.length) {
            throw failure("JOB_ZIP_INVALID");
        }
    }

    private static JobFailure failure(String code) { return new JobFailure(code); }

    private static final class JobFailure extends IOException {
        JobFailure(String code) { super(code); }
    }
}
