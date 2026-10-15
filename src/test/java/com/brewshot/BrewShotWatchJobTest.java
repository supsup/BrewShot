package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure file/schema preparation; no Chrome, subprocesses, or network. */
class BrewShotWatchJobTest {
    @TempDir Path temporary;
    private int sequence;

    @Test
    void urlGifMapsOnlyDocumentedOptionsAndPreservesCliDefaults() throws Exception {
        BrewShotWatchJob.Prepared job = json("""
            {"version":1,"source":{"url":"https://example.test/scene?q=1"},
             "capture":{"format":"gif","frames":40,"delayMs":100,
             "viewport":{"width":1380,"height":920},"dpr":2,"settleMs":500,
             "selector":".stage","scale":0.85}}
            """);
        assertEquals("gif", job.format());
        assertEquals(List.of("https://example.test/scene?q=1", "--gif", "40",
            "--gif-delay", "100", "--size", "1380x920", "--dpr", "2",
            "--settle", "500", "--gif-element", ".stage", "--scale", "0.85"),
            job.arguments());
        assertFalse(job.arguments().contains("-o"));
        assertFalse(job.arguments().contains("--json"));
        assertThrows(UnsupportedOperationException.class,
            () -> job.arguments().add("--unexpected"));
        assertEquals(List.of("http://localhost:8080/"), json(urlJob("png", "")).arguments());
        assertEquals(List.of("http://localhost:8080/", "--gif", "3"),
            json(urlJob("gif", ",\"frames\":3")).arguments());
    }

    @Test
    void pngSelectorUsesStillCaptureFlagAndLeavesSelectorLiteral() throws Exception {
        assertEquals(List.of("http://localhost:8080/", "--clip-selector", "#animation",
            "--scale", "2.0"), json(urlJob("png",
                ",\"selector\":\"#animation\",\"scale\":2")).arguments());
        assertEquals(List.of("http://localhost:8080/", "--clip-selector", "--out"),
            json(urlJob("png", ",\"selector\":\"--out\"")).arguments(),
            "selector is one argv value, never interpreted as caller-supplied flags");
    }

    @Test
    void numericRangesAndCombinationsRemainTheCliValidatorsResponsibility() throws Exception {
        BrewShotWatchJob.Prepared job = json(urlJob("gif", """
            ,"frames":0,"delayMs":-2,"viewport":{"width":-1,"height":0},
             "dpr":99,"settleMs":-1,"scale":0
            """));
        assertEquals(List.of("http://localhost:8080/", "--gif", "0", "--gif-delay", "-2",
            "--size", "-1x0", "--dpr", "99", "--settle", "-1", "--scale", "0.0"),
            job.arguments());
        assertEquals("100000000000000000000", json(urlJob("gif", ",\"frames\":1e20"))
            .arguments().get(2), "integer token, not overflow/clamping or exponent syntax");
        assertEquals("-1", json(urlJob("gif", ",\"frames\":-1.0"))
            .arguments().get(2));
    }

    @Test
    void schemaRefusesUnknownFieldsWrongTypesMissingFieldsAndPngGifOptions() throws Exception {
        for (String content : List.of(
                "[]", "null", "{}",
                "{\"version\":\"1\",\"source\":{\"url\":\"https://example.test\"},\"capture\":{\"format\":\"png\"}}",
                "{\"version\":2,\"source\":{\"url\":\"https://example.test\"},\"capture\":{\"format\":\"png\"}}",
                urlJob("jpg", ""), urlJob("gif", ""),
                urlJob("png", ",\"frames\":1"), urlJob("png", ",\"delayMs\":1"),
                urlJob("gif", ",\"frames\":\"40\""),
                urlJob("gif", ",\"frames\":true"),
                urlJob("gif", ",\"frames\":1.5"),
                urlJob("png", ",\"dpr\":1.5"),
                urlJob("png", ",\"settleMs\":null"),
                urlJob("png", ",\"scale\":\"2\""),
                urlJob("png", ",\"selector\":false"),
                urlJob("png", ",\"viewport\":{\"width\":390}"),
                urlJob("png", ",\"viewport\":{\"width\":390,\"height\":800,\"auto\":true}"),
                urlJob("png", ",\"out\":\"escape.png\""),
                urlJob("png", "").replace("\"version\":1", "\"version\":1,\"args\":[\"--eval\",\"x\"]"),
                urlJob("png", "").replace("\"url\":\"http://localhost:8080/\"",
                    "\"url\":\"http://localhost:8080/\",\"file\":\"page.html\""),
                urlJob("png", "").replace("\"url\":\"http://localhost:8080/\"",
                    "\"file\":\"page.html\""),
                urlJob("png", "").replace("\"capture\":", "\"prepareScript\":\"prepare.js\",\"capture\":"))) {
            assertCode("JOB_SCHEMA", () -> json(content));
        }
    }

    @Test
    void strictJsonRefusesDuplicatesInvalidNumbersAndMalformedUtf8() throws Exception {
        for (String content : List.of(
                "{\"version\":1,\"version\":1}",
                urlJob("png", ",\"dpr\":+1"),
                urlJob("png", ",\"dpr\":01"),
                urlJob("png", ",\"scale\":1e309"),
                urlJob("png", ",\"selector\":\"\\ud800\""),
                urlJob("png", ",\"selector\":\"raw\ncontrol\""),
                urlJob("png", "") + " garbage")) {
            assertCode("JOB_JSON_INVALID", () -> json(content));
        }
        Path source = input(".brewshot.json", new byte[] {(byte) 0xc3, 0x28});
        assertCode("JOB_IO", () -> BrewShotWatchJob.prepare(source, scratch()));
    }

    @Test
    void urlIntakeRequiresHttpHostAndNoEmbeddedCredentials() throws Exception {
        for (String url : List.of("file:///etc/passwd", "data:text/html,hello",
                "chrome://version", "javascript:alert(1)", "//example.test/path",
                "http:example.test", "https:///missing", "https://user:secret@example.test/",
                "https://@example.test/", "https://example.test/a b")) {
            String content = urlJob("png", "").replace("http://localhost:8080/", url);
            assertCode("JOB_URL", () -> json(content));
        }
        assertEquals("HTTP://example.test/path", json(urlJob("png", "")
            .replace("http://localhost:8080/", "HTTP://example.test/path")).arguments().get(0));
    }

    @Test
    void bundleRetainsRelativeAssetsAndMapsBoundedPreparationFile() throws Exception {
        String manifest = """
            {"version":1,"source":{"file":"site/index.html"},
             "capture":{"format":"gif","frames":4},"prepareScript":"prepare.js"}
            """;
        byte[] image = {0, 1, 2, 3};
        Path source = input(".brewshot.zip", deflatedZip(List.of(
            new Fixture("job.json", utf8(manifest), 0100644),
            new Fixture("site/", new byte[0], 0040755),
            new Fixture("site/index.html", utf8("<img src='image.png'><script src='scene.js'></script>"), 0100644),
            new Fixture("site/image.png", image, 0100644),
            new Fixture("site/scene.js", utf8("document.body.dataset.ready='yes'"), 0100644),
            new Fixture("prepare.js", utf8("document.body.click()"), 0100644))));
        Path scratch = scratch();
        BrewShotWatchJob.Prepared prepared = BrewShotWatchJob.prepare(source, scratch);
        assertEquals("gif", prepared.format());
        assertEquals(List.of(scratch.resolve("assets/site/index.html").toString(),
            "--gif", "4", "--eval-file", scratch.resolve("assets/prepare.js").toString()),
            prepared.arguments());
        assertArrayEquals(image, Files.readAllBytes(scratch.resolve("assets/site/image.png")));
        assertEquals("document.body.dataset.ready='yes'",
            Files.readString(scratch.resolve("assets/site/scene.js")));
    }

    @Test
    void storedZipAndPlainIntegerVersionAreSupported() throws Exception {
        BrewShotWatchJob.Prepared prepared = bundle(storedZip(basicMembers()));
        assertEquals("png", prepared.format());
        assertEquals(1, prepared.arguments().size());
        assertTrue(prepared.arguments().get(0).endsWith("/assets/page.html"));
    }

    @Test
    void bundleSourceAndPreparationMustBeExistingLocalHtmlAndJs() throws Exception {
        for (String replacement : List.of("missing.html", "job.json", "../page.html",
                "/page.html", "C:/page.html", "folder/./page.html", "folder//page.html")) {
            List<Fixture> members = new ArrayList<>(basicMembers());
            members.set(0, fixture("job.json", fileJob(replacement)));
            assertBoundedFailure(() -> bundle(storedZip(members)));
        }
        List<Fixture> members = new ArrayList<>(basicMembers());
        members.set(0, fixture("job.json", """
            {"version":1,"source":{"url":"https://example.test"},"capture":{"format":"png"}}
            """));
        assertCode("JOB_SCHEMA", () -> bundle(storedZip(members)));
        for (String script : List.of("absent.js", "page.html", "../prepare.js")) {
            List<Fixture> scriptMembers = new ArrayList<>(basicMembers());
            scriptMembers.set(0, fixture("job.json", fileJob("page.html")
                .replace("\"version\":1", "\"version\":1,\"prepareScript\":\"" + script + "\"")));
            assertCode("JOB_PATH", () -> bundle(storedZip(scriptMembers)));
        }
    }

    @Test
    void rejectsTraversalDuplicateCollisionsAndSpecialFileModesBeforeExtraction() throws Exception {
        for (String name : List.of("../escape", "a/../escape", "a/./escape", "/escape",
                "C:/escape", "a\\escape", "a//escape", "a/", "bad\u0000name")) {
            List<Fixture> members = new ArrayList<>(basicMembers());
            // a/ is a normal directory only with directory metadata; here it is regular.
            members.add(new Fixture(name, utf8("payload"), 0100644));
            Path scratch = scratch();
            Path source = input(".brewshot.zip", storedZip(members));
            assertBoundedFailure(() -> BrewShotWatchJob.prepare(source, scratch));
            assertFalse(Files.exists(scratch.resolve("assets")), "inspect before extracting");
        }
        for (List<Fixture> extra : List.of(
                List.of(fixture("page.html", "duplicate")),
                List.of(fixture("job.json", "duplicate manifest")),
                List.of(fixture("page.html/child", "file parent collision")),
                List.of(new Fixture("page.html/", new byte[0], 0040755)),
                List.of(new Fixture("link", utf8("/etc/passwd"), 0120777)),
                List.of(new Fixture("fifo", new byte[0], 0010644)),
                List.of(new Fixture("device", new byte[0], 0020644)))) {
            List<Fixture> members = new ArrayList<>(basicMembers());
            members.addAll(extra);
            assertCode("JOB_ZIP_INVALID", () -> bundle(storedZip(members)));
        }
        assertFalse(Files.exists(temporary.resolve("escape")));
    }

    @Test
    void archiveRequiresExactlyOneRootManifestAndRejectsCorruption() throws Exception {
        assertCode("JOB_ZIP_INVALID", () -> bundle(storedZip(List.of(fixture("page.html", "html")))));
        assertCode("JOB_ZIP_INVALID", () -> bundle(storedZip(List.of(
            fixture("nested/job.json", fileJob("page.html")), fixture("page.html", "html")))));
        byte[] valid = storedZip(basicMembers());
        assertBoundedFailure(() -> bundle(Arrays.copyOf(valid, valid.length - 1)));
        assertBoundedFailure(() -> bundle(new byte[] {1, 2, 3}));
        byte[] badData = valid.clone();
        int payload = 30 + "job.json".length();
        badData[payload] ^= 1;
        assertCode("JOB_ZIP_INVALID", () -> bundle(badData));
        byte[] differentLocalName = valid.clone();
        differentLocalName[30] = 'x';
        assertCode("JOB_ZIP_INVALID", () -> bundle(differentLocalName));
        byte[] descriptor = deflatedZip(basicMembers());
        int descriptorAt = firstSignature(descriptor, 0x08074b50);
        descriptor[descriptorAt + 4] ^= 1;
        assertCode("JOB_ZIP_INVALID", () -> bundle(descriptor));
        byte[] trailingJunk = Arrays.copyOf(valid, valid.length + 1);
        assertCode("JOB_ZIP_INVALID", () -> bundle(trailingJunk));
    }

    @Test
    void refusesEncryptedZip64AndCentralLocalMetadataDisagreements() throws Exception {
        byte[] original = storedZip(basicMembers());
        int central = firstSignature(original, 0x02014b50);
        for (int mutation = 0; mutation < 7; mutation++) {
            byte[] changed = original.clone();
            switch (mutation) {
                case 0 -> { put16(changed, 6, 1); put16(changed, central + 8, 1); }
                case 1 -> put32(changed, central + 24, 0xffffffffL);
                case 2 -> put16(changed, original.length - 22 + 10, 0xffff);
                case 3 -> put16(changed, central + 34, 1);
                case 4 -> put32(changed, central + 42, 1);
                case 5 -> put16(changed, 8, 8);
                case 6 -> put32(changed, central + 16, 0);
                default -> throw new AssertionError();
            }
            assertCode("JOB_ZIP_INVALID", () -> bundle(changed));
        }
        // A ZIP64 extra must be rejected even without sentinel size fields.
        // Fixtures with a four-byte local extra are easier to construct explicitly.
        byte[] ordinaryExtra = zipWithExtra(new byte[] {2, 0, 0, 0});
        assertEquals("png", bundle(ordinaryExtra).format());
        // ZipOutputStream rewrites its own ZIP64 fields, so mutate an ordinary
        // local extra after writing instead of asking that writer for ZIP64.
        byte[] zip64 = ordinaryExtra.clone();
        put16(zip64, 30 + "job.json".length(), 1);
        assertCode("JOB_ZIP_INVALID", () -> bundle(zip64));
    }

    @Test
    void enforcesManifestCompressedInputEntryCountAndExpandedLimits() throws Exception {
        String small = urlJob("png", "");
        assertEquals("png", json(small + " ".repeat(64 * 1024 - utf8(small).length)).format());
        assertCode("JOB_LIMIT", () -> json(" ".repeat(64 * 1024 + 1)));
        assertCode("JOB_LIMIT", () -> bundle(new byte[16 * 1024 * 1024 + 1]));
        List<Fixture> exactCount = new ArrayList<>(basicMembers());
        for (int i = 0; i < 254; i++) { exactCount.add(fixture("asset" + i, "")); }
        assertEquals("png", bundle(storedZip(exactCount)).format());
        List<Fixture> tooMany = new ArrayList<>(basicMembers());
        for (int i = 0; i < 255; i++) { tooMany.add(fixture("asset" + i, "")); }
        assertCode("JOB_LIMIT", () -> bundle(storedZip(tooMany)));
        List<Fixture> giantManifest = new ArrayList<>(basicMembers());
        giantManifest.set(0, new Fixture("job.json", new byte[64 * 1024 + 1], 0100644));
        assertCode("JOB_LIMIT", () -> bundle(deflatedZip(giantManifest)));
        List<Fixture> giantEntry = new ArrayList<>(basicMembers());
        giantEntry.add(new Fixture("big", new byte[16 * 1024 * 1024 + 1], 0100644));
        assertCode("JOB_LIMIT", () -> bundle(deflatedZip(giantEntry)));
        List<Fixture> giantTotal = new ArrayList<>(basicMembers());
        byte[] sixteenMib = new byte[16 * 1024 * 1024];
        for (int i = 0; i < 4; i++) { giantTotal.add(new Fixture("big" + i, sixteenMib, 0100644)); }
        assertCode("JOB_LIMIT", () -> bundle(deflatedZip(giantTotal)));
    }

    @Test
    void usesMainsPreparationScriptByteCapAndRequiresEmptyPrivateScratch() throws Exception {
        for (int size : new int[] {Main.MAX_EVAL_FILE_BYTES, Main.MAX_EVAL_FILE_BYTES + 1}) {
            List<Fixture> members = new ArrayList<>(basicMembers());
            members.set(0, fixture("job.json", fileJob("page.html")
                .replace("\"version\":1", "\"version\":1,\"prepareScript\":\"prepare.js\"")));
            members.add(new Fixture("prepare.js", new byte[size], 0100644));
            if (size == Main.MAX_EVAL_FILE_BYTES) {
                assertTrue(bundle(deflatedZip(members)).arguments().contains("--eval-file"));
            } else { assertCode("JOB_LIMIT", () -> bundle(deflatedZip(members))); }
        }
        Path scratch = scratch();
        Files.writeString(scratch.resolve("preexisting"), "keep");
        Path source = input(".brewshot.json", utf8(urlJob("png", "")));
        assertCode("JOB_SCRATCH", () -> BrewShotWatchJob.prepare(source, scratch));
        assertEquals("keep", Files.readString(scratch.resolve("preexisting")));
    }

    private BrewShotWatchJob.Prepared json(String content) throws Exception {
        return BrewShotWatchJob.prepare(input(".brewshot.json", utf8(content)), scratch());
    }

    private BrewShotWatchJob.Prepared bundle(byte[] bytes) throws Exception {
        return BrewShotWatchJob.prepare(input(".brewshot.zip", bytes), scratch());
    }

    private Path input(String suffix, byte[] bytes) throws IOException {
        Path path = temporary.resolve("job-" + sequence++ + suffix);
        return Files.write(path, bytes);
    }

    private Path scratch() throws IOException {
        return Files.createDirectory(temporary.resolve("scratch-" + sequence++));
    }

    private static String urlJob(String format, String captureFields) {
        return "{\"version\":1,\"source\":{\"url\":\"http://localhost:8080/\"},"
            + "\"capture\":{\"format\":\"" + format + "\"" + captureFields + "}}";
    }

    private static String fileJob(String path) {
        return "{\"version\":1,\"source\":{\"file\":\"" + path
            + "\"},\"capture\":{\"format\":\"png\"}}";
    }

    private static byte[] utf8(String string) { return string.getBytes(StandardCharsets.UTF_8); }
    private static Fixture fixture(String name, String content) {
        return new Fixture(name, utf8(content), 0100644);
    }
    private static List<Fixture> basicMembers() {
        return List.of(fixture("job.json", fileJob("page.html")), fixture("page.html", "<p>hello</p>"));
    }
    private record Fixture(String name, byte[] content, int unixMode) { }

    /** Minimal stored writer allows intentionally duplicate/hostile member names. */
    private static byte[] storedZip(List<Fixture> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        List<Long> crcs = new ArrayList<>();
        for (Fixture member : members) {
            offsets.add(bytes.size());
            CRC32 crc = new CRC32();
            crc.update(member.content);
            crcs.add(crc.getValue());
            byte[] name = utf8(member.name);
            le32(bytes, 0x04034b50); le16(bytes, 20); le16(bytes, 0x800); le16(bytes, 0);
            le16(bytes, 0); le16(bytes, 0); le32(bytes, crc.getValue());
            le32(bytes, member.content.length); le32(bytes, member.content.length);
            le16(bytes, name.length); le16(bytes, 0); bytes.write(name); bytes.write(member.content);
        }
        int start = bytes.size();
        for (int i = 0; i < members.size(); i++) {
            Fixture member = members.get(i);
            byte[] name = utf8(member.name);
            le32(bytes, 0x02014b50); le16(bytes, 0x0314); le16(bytes, 20);
            le16(bytes, 0x800); le16(bytes, 0); le16(bytes, 0); le16(bytes, 0);
            le32(bytes, crcs.get(i)); le32(bytes, member.content.length); le32(bytes, member.content.length);
            le16(bytes, name.length); le16(bytes, 0); le16(bytes, 0); le16(bytes, 0); le16(bytes, 0);
            le32(bytes, (long) member.unixMode << 16); le32(bytes, offsets.get(i)); bytes.write(name);
        }
        int size = bytes.size() - start;
        le32(bytes, 0x06054b50); le16(bytes, 0); le16(bytes, 0);
        le16(bytes, members.size()); le16(bytes, members.size());
        le32(bytes, size); le32(bytes, start); le16(bytes, 0);
        return bytes.toByteArray();
    }

    private static byte[] deflatedZip(List<Fixture> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Fixture member : members) {
                zip.putNextEntry(new ZipEntry(member.name));
                zip.write(member.content);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zipWithExtra(byte[] extra) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Fixture fixture : basicMembers()) {
                ZipEntry entry = new ZipEntry(fixture.name);
                entry.setExtra(extra);
                zip.putNextEntry(entry);
                zip.write(fixture.content);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static int firstSignature(byte[] bytes, int signature) {
        for (int p = 0; p < bytes.length - 3; p++) {
            if ((bytes[p] & 255) == (signature & 255)
                    && (bytes[p + 1] & 255) == ((signature >>> 8) & 255)
                    && (bytes[p + 2] & 255) == ((signature >>> 16) & 255)
                    && (bytes[p + 3] & 255) == ((signature >>> 24) & 255)) { return p; }
        }
        throw new AssertionError("fixture missing signature");
    }
    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void le32(ByteArrayOutputStream out, long value) {
        le16(out, (int) value); le16(out, (int) (value >>> 16));
    }
    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value; bytes[offset + 1] = (byte) (value >>> 8);
    }
    private static void put32(byte[] bytes, int offset, long value) {
        put16(bytes, offset, (int) value); put16(bytes, offset + 2, (int) (value >>> 16));
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void assertCode(String expected, Action action) {
        assertEquals(expected, assertThrows(IOException.class, action::run).getMessage());
    }
    private static void assertBoundedFailure(Action action) {
        IOException failure = assertThrows(IOException.class, action::run);
        assertTrue(failure.getMessage().matches("JOB_[A-Z_]+"), failure.getMessage());
        assertTrue(failure.getMessage().length() < 64);
        assertEquals(null, failure.getCause(), "no nested path/URL leak through exception causes");
    }
}
