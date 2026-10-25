package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Plan 728a0dfd: session auth from a file, never argv. No Chrome: the refusals all happen while
/// the arguments are parsed, and the one capture runs over MainCliUnconfirmedCleanupTest's
/// scripted CDP session, so the values can be followed to the browser and looked for everywhere
/// else. The real-browser half (a guarded page, and `ps` during the run) is
/// BrewShotAuthTest.headerFileReachesAGuardedPageAndNoProcessArgvCarriesTheValue.
final class MainAuthFileTest {

    /// Distinctive enough that a substring hit can only be a leak.
    private static final String HEADER_SECRET = "hdr-s3cr3t-7f2a-not-for-ps";
    private static final String COOKIE_SECRET = "ck-s3cr3t-91bd-not-for-ps";

    @BeforeEach
    void pinTheZombieProbe() {
        BrewShot.exitedButUnreapedProbeForTests = pid -> false;
    }

    @AfterEach
    void releaseTheSeams() {
        BrewShot.exitedButUnreapedProbeForTests = null;
        Main.sessionLauncherForTests = null;
    }

    private record Run(int code, String stdout, String stderr) {
        String all() { return stdout + "\n" + stderr; }
    }

    private static Run cli(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int code;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            code = Main.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    private static Path authFile(Path dir, String name, String body, String mode)
            throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        return file;
    }

    private static Path page(Path dir) throws IOException {
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<!doctype html><title>auth</title><p>auth</p>");
        return page;
    }

    // Mutant: drop the WARNING lines or the two -file lines from Main.usage() -> red.
    @Test
    void helpWarnsThatArgvValuesAreVisibleAndNamesTheFileForms() throws Exception {
        Run run = cli("--help");
        assertEquals(0, run.code());
        String help = run.stderr();
        assertTrue(help.contains("--header-file PATH") && help.contains("--cookie-file PATH"), help);
        assertTrue(help.contains("visible to other local users (ps)"), help);
        assertTrue(help.contains("owner-only (chmod 600)") && help.contains("not a symlink"), help);
        assertTrue(help.contains("<from file>"), help);
    }

    // Mutant: delete the NOT_OWNER permissions check in AuthFile.readOwnerOnly -> each of these
    // parses, reaches input resolution and exits 2 WITHOUT the mode in the message -> red.
    @Test
    void aGroupOrWorldAccessibleFileIsRefusedAndTheMessageNamesItsMode(@TempDir Path dir)
            throws Exception {
        Path page = page(dir);
        Path out = dir.resolve("shot.png");
        String[][] cases = {
            {"--header-file", "X-Api-Token: " + HEADER_SECRET, "rw-r--r--", "0644"},
            {"--header-file", "X-Api-Token: " + HEADER_SECRET, "rw----r--", "0604"},
            {"--cookie-file", "SESSION=" + COOKIE_SECRET, "rw-r-----", "0640"},
            {"--cookie-file", "SESSION=" + COOKIE_SECRET, "rw--w----", "0620"},
        };
        for (String[] c : cases) {
            Path file = authFile(dir, "auth-" + c[3], c[1] + "\n", c[2]);
            Run run = cli(page.toString(), "-o", out.toString(), c[0], file.toString());
            assertEquals(2, run.code(), run.all());
            assertTrue(run.stderr().contains(c[0] + " " + file + ": mode " + c[3]), run.stderr());
            assertTrue(run.stderr().contains("chmod 600"), run.stderr());
            assertFalse(run.all().contains(HEADER_SECRET) || run.all().contains(COOKIE_SECRET),
                "a refusal must never echo the value: " + run.all());
            assertFalse(Files.exists(out), "refused before any artifact");
        }
    }

    // Mutant: delete the isSymbolicLink() refusal -> refused as "is not a regular file", not
    // "is a symlink" -> red. Mutant: drop NOFOLLOW_LINKS from the attribute read -> the 0600
    // target passes the checks and only the NOFOLLOW open stops it, as "cannot be read" -> red.
    // (Both are defence in depth: the open alone still refuses a link swapped in after the check.)
    @Test
    void aSymlinkIsRefusedEvenWhenItPointsAtAnOwnerOnlyFile(@TempDir Path dir) throws Exception {
        Path target = authFile(dir, "real-auth", "X-Api-Token: " + HEADER_SECRET + "\n", "rw-------");
        Path link = Files.createSymbolicLink(dir.resolve("link-auth"), target);
        Run run = cli(page(dir).toString(), "-o", dir.resolve("shot.png").toString(),
            "--header-file", link.toString());
        assertEquals(2, run.code(), run.all());
        assertTrue(run.stderr().contains("--header-file " + link + ": is a symlink"), run.stderr());
        assertFalse(run.all().contains(HEADER_SECRET), run.all());
    }

    // Mutant: put the line text into AuthFile.lineRefusal's message -> the secret appears -> red.
    @Test
    void aMalformedLineIsRefusedByNumberAndItsTextIsNeverShown(@TempDir Path dir)
            throws Exception {
        Path page = page(dir);
        Path headers = authFile(dir, "bad-headers",
            "# comment\n\nAuthorization Bearer " + HEADER_SECRET + "\n", "rw-------");
        Run headerRun = cli(page.toString(), "--header-file", headers.toString());
        assertEquals(2, headerRun.code(), headerRun.all());
        assertTrue(headerRun.stderr().contains(
            "--header-file " + headers + " line 3: wants 'Name: value'"), headerRun.stderr());
        assertFalse(headerRun.all().contains(HEADER_SECRET), headerRun.all());

        Path cookies = authFile(dir, "bad-cookies", "SESSION" + COOKIE_SECRET + "\n", "rw-------");
        Run cookieRun = cli(page.toString(), "--cookie-file", cookies.toString());
        assertEquals(2, cookieRun.code(), cookieRun.all());
        assertTrue(cookieRun.stderr().contains("line 1: wants name=value[@domain]"),
            cookieRun.stderr());
        assertFalse(cookieRun.all().contains(COOKIE_SECRET), cookieRun.all());
    }

    // Mutant: drop the NoSuchFileException arm -> the JDK exception escapes the parse catch as an
    // IOException with a different message ("no such file" missing) -> red.
    @Test
    void aMissingFileIsAUsageErrorNamingTheFlag(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("absent");
        Run run = cli(page(dir).toString(), "--cookie-file", missing.toString());
        assertEquals(2, run.code(), run.all());
        assertTrue(run.stderr().contains("--cookie-file " + missing + ": no such file"),
            run.stderr());
    }

    // The healthy positives for the parser and the file checks. Mutant: split the header on the
    // LAST colon, or the cookie domain on the FIRST @ -> a value is cut -> red. Mutant: refuse
    // OWNER_* bits as well -> the 0600/0400 reads throw -> red.
    @Test
    void ownerOnlyFilesParseCommentsBlankLinesCrlfAndValuesWithSeparators(@TempDir Path dir)
            throws Exception {
        Path headers = authFile(dir, "headers",
            "# leading comment\r\nAuthorization: Bearer a:b:" + HEADER_SECRET + "\r\n\r\n"
                + "X-Second:  two  \n", "rw-------");
        List<AuthFile.Header> parsed = AuthFile.readHeaders(headers);
        assertEquals(2, parsed.size(), parsed.toString());
        assertEquals("Authorization", parsed.get(0).name());
        assertEquals("Bearer a:b:" + HEADER_SECRET, parsed.get(0).value());
        assertEquals("X-Second", parsed.get(1).name());
        assertEquals("two", parsed.get(1).value());

        Path cookies = authFile(dir, "cookies",
            "SESSION=" + COOKIE_SECRET + "@x@app.test\nPLAIN=v=1\n", "r--------");
        List<AuthFile.Cookie> jar = AuthFile.readCookies(cookies);
        assertEquals(2, jar.size(), jar.toString());
        assertEquals("SESSION", jar.get(0).name());
        assertEquals(COOKIE_SECRET + "@x", jar.get(0).value());
        assertEquals("app.test", jar.get(0).domain());
        assertEquals("PLAIN", jar.get(1).name());
        assertEquals("v=1", jar.get(1).value());
        assertEquals("localhost", jar.get(1).domain(), "no @ defaults to localhost, as --cookie");

        // A record's generated toString would print the value; these never do.
        assertFalse(parsed.toString().contains(HEADER_SECRET), parsed.toString());
        assertFalse(jar.toString().contains(COOKIE_SECRET), jar.toString());
        assertEquals("0644", AuthFile.octal(PosixFilePermissions.fromString("rw-r--r--")));
    }

    @Test
    void aFileWithNoAuthLinesIsRefusedRatherThanSilentlySendingNone(@TempDir Path dir)
            throws Exception {
        Path empty = authFile(dir, "empty", "# nothing here\n\n", "rw-------");
        IOException refusal = assertThrows(IOException.class, () -> AuthFile.readHeaders(empty));
        assertTrue(refusal.getMessage().contains("no 'Name: value' line found"),
            refusal.getMessage());
    }

    // THE END-TO-END NEGATIVE over the production capture path. Fixture guards first: the
    // values really reached the browser (mutant: delete applyFileAuth's shot.header/shot.cookie
    // call -> red). Then every output is searched for them (mutant: put header.value() where
    // fileAuthManifestField writes AuthFile.REDACTED -> red; mutant: drop the "auth" put ->
    // the redaction assertions go red).
    @Test
    @SuppressWarnings("unchecked")
    void fileAuthReachesTheBrowserAndNoValueReachesTheManifestStdoutOrStderr(@TempDir Path dir)
            throws Exception {
        Path page = page(dir);
        Path out = dir.resolve("shot.png");
        Path manifest = dir.resolve("shot.json");
        Path headers = authFile(dir, "headers", "X-Api-Token: " + HEADER_SECRET + "\n", "rw-------");
        Path cookies = authFile(dir, "cookies",
            "SESSION=" + COOKIE_SECRET + "@guarded.test\n", "rw-------");
        Path profile = Files.createDirectories(dir.resolve("brewshot-auth-profile"));
        MainCliUnconfirmedCleanupTest.FakeChrome chrome =
            new MainCliUnconfirmedCleanupTest.FakeChrome(424299L, true);
        MainCliUnconfirmedCleanupTest.ScriptedChrome script =
            new MainCliUnconfirmedCleanupTest.ScriptedChrome();
        Main.sessionLauncherForTests = (width, height, dpr) -> script.session(chrome, profile);
        Run run;
        try {
            run = cli(page.toString(), "-o", out.toString(), "--json", manifest.toString(),
                "--settle", "1", "--header-file", headers.toString(),
                "--cookie-file", cookies.toString());
        } finally {
            Main.sessionLauncherForTests = null;
            chrome.die();
            BrewShot.runShutdownCleanupForTests();
        }
        assertEquals(0, run.code(), run.all());
        assertEquals(1, script.captures.get(), "the page must really have been captured");

        // 1. What reached the browser: exactly the file's values, over CDP.
        Map<String, Object> headerCommand = null;
        Map<String, Object> cookieCommand = null;
        for (String sent : script.sentCommands) {
            Map<String, Object> message = (Map<String, Object>) MiniJson.parse(sent);
            if ("Network.setExtraHTTPHeaders".equals(message.get("method"))) {
                headerCommand = (Map<String, Object>) message.get("params");
            }
            if ("Network.setCookie".equals(message.get("method"))) {
                cookieCommand = (Map<String, Object>) message.get("params");
            }
        }
        assertTrue(headerCommand != null && cookieCommand != null,
            "both values must have reached the browser: " + script.sentCommands);
        assertEquals(HEADER_SECRET,
            ((Map<String, Object>) headerCommand.get("headers")).get("X-Api-Token"));
        assertEquals(COOKIE_SECRET, cookieCommand.get("value"));
        assertEquals("guarded.test", cookieCommand.get("domain"));

        // 2. What reached everything else: no value, anywhere.
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        for (String secret : List.of(HEADER_SECRET, COOKIE_SECRET)) {
            assertFalse(json.contains(secret), "manifest leaked a value: " + json);
            assertFalse(run.all().contains(secret), "stdout/stderr leaked a value: " + run.all());
        }
        assertFalse(json.contains("guarded.test"), "the cookie domain is withheld too: " + json);
        assertTrue(run.stderr().contains("brewshot: wrote " + out), run.stderr());

        // 3. The manifest says where auth came from, by name, with the value redacted.
        Map<String, Object> auth = (Map<String, Object>)
            ((Map<String, Object>) MiniJson.parse(json)).get("auth");
        assertEquals(List.of(headers.toString()), auth.get("headerFiles"), json);
        assertEquals(List.of(cookies.toString()), auth.get("cookieFiles"), json);
        assertEquals(List.of(Map.of("name", "X-Api-Token", "value", "<from file>")),
            auth.get("headers"), json);
        assertEquals(List.of(Map.of("name", "SESSION", "value", "<from file>")),
            auth.get("cookies"), json);
    }
}
