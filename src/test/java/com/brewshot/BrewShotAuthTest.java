package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * header()/cookie() verified against a real HTTP exchange: a JDK-built-in
 * HttpServer echoes the Authorization and Cookie request headers into the
 * page body, and the browser-side eval reads them back. Still zero deps.
 */
class BrewShotAuthTest {

    @Test
    void headerAndCookieReachTheServer() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotAuthTest");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = "auth=[" + String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))
                + "] brew=[" + String.valueOf(ex.getRequestHeaders().getFirst("X-Brew"))
                + "] cookie=[" + String.valueOf(ex.getRequestHeaders().getFirst("Cookie")) + "]";
            byte[] b = ("<body>" + body + "</body>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.header("Authorization", "Basic dXNlcjpwYXNz");
            shot.header("X-Brew", "hot");                    // accumulation: both must arrive
            shot.cookie("SESSION", "tok-123", "127.0.0.1");
            shot.open("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertEquals("auth=[Basic dXNlcjpwYXNz] brew=[hot] cookie=[SESSION=tok-123]",
                shot.eval("document.body.textContent.trim()"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * Plan 728a0dfd acceptance, end to end. The brewshot CLI runs as a CHILD JVM with
     * {@code --header-file} against a page that refuses anonymous access. While the guarded
     * request is being served (so the child and its Chrome are certainly alive) the server
     * snapshots the command line of the child and of every descendant, which is what {@code ps}
     * shows any local user. Mutants: delete the {@code --header-file} parse case (exit 2, page
     * never reached) or {@code applyFileAuth} (page answers "denied") -> red; route the file
     * value into Chrome's launch flags or print it -> the argv/output assertions go red.
     */
    @Test
    void headerFileReachesAGuardedPageAndNoProcessArgvCarriesTheValue(@TempDir Path dir)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotAuthTest");

        String secret = "Bearer argv-s3cr3t-5c1e-file-only";
        AtomicReference<ProcessHandle> child = new AtomicReference<>();
        List<String> argvDuringGuardedRequest = new CopyOnWriteArrayList<>();
        AtomicInteger granted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            boolean authed = secret.equals(ex.getRequestHeaders().getFirst("Authorization"));
            if (authed) {
                granted.incrementAndGet();
                ProcessHandle cli = child.get();
                if (cli != null) {
                    Stream.concat(Stream.of(cli), cli.descendants())
                        .map(p -> p.info().commandLine().orElse(""))
                        .forEach(argvDuringGuardedRequest::add);
                }
            }
            // The page never echoes the credential: anything that prints it would be a leak.
            byte[] b = ("<body>" + (authed ? "granted" : "denied") + "</body>")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(authed ? 200 : 401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            // FIXTURE GUARD: the page really refuses an anonymous request.
            HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, anonymous.statusCode());

            Path headerFile = dir.resolve("auth-headers");
            Files.writeString(headerFile, "Authorization: " + secret + "\n");
            Files.setPosixFilePermissions(headerFile, PosixFilePermissions.fromString("rw-------"));
            Path out = dir.resolve("guarded.png");
            Path manifest = dir.resolve("guarded.json");
            Path stdout = dir.resolve("stdout.txt");
            Path stderr = dir.resolve("stderr.txt");
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process cli = new ProcessBuilder(javaBin,
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"), "com.brewshot.Main",
                url, "-o", out.toString(), "--json", manifest.toString(), "--settle", "1",
                "--header-file", headerFile.toString(),
                "--eval", "document.body.textContent.trim()")
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
            child.set(cli.toHandle());
            boolean exited;
            // Custody of the child AND its Chrome: on a timeout or interrupt the child is asked
            // to stop (SIGTERM, so its shutdown hook closes Chrome), then the child and every
            // descendant snapshotted while it ran are SIGKILLed and joined, bounded; a survivor
            // fails the test by pid. See OwnedProcessTree / OwnedProcessTreeTest.
            try (OwnedProcessTree tree = OwnedProcessTree.adopt(cli,
                    Duration.ofSeconds(10), Duration.ofSeconds(10))) {
                exited = tree.waitFor(Duration.ofSeconds(90));
            }
            String outText = Files.readString(stdout, StandardCharsets.UTF_8);
            String errText = Files.readString(stderr, StandardCharsets.UTF_8);
            assertTrue(exited, "the CLI did not finish: " + errText);
            assertEquals(0, cli.exitValue(), errText);

            // Reached the page that refuses anonymous access, with the file's credential.
            assertTrue(granted.get() >= 1, "the guarded page never saw the credential: " + errText);
            assertTrue(outText.contains("granted"), outText + errText);

            // `ps` during the run: the path is there, the value is not, in any process.
            assertFalse(argvDuringGuardedRequest.isEmpty(), "no argv snapshot was taken");
            assertTrue(argvDuringGuardedRequest.get(0).contains(headerFile.toString()),
                "FIXTURE GUARD: the child's own argv must be readable and name the file: "
                    + argvDuringGuardedRequest.get(0));
            assertTrue(argvDuringGuardedRequest.size() > 1,
                "FIXTURE GUARD: Chrome must have been seen as a descendant: "
                    + argvDuringGuardedRequest);
            for (String argv : argvDuringGuardedRequest) {
                assertFalse(argv.contains("argv-s3cr3t-5c1e"), "a process argv carries the value");
            }

            // The JSON sidecar and both output streams carry no value from the file.
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            for (String text : List.of(json, outText, errText)) {
                assertFalse(text.contains("argv-s3cr3t-5c1e"), "output leaked the value: " + text);
            }
            assertTrue(json.contains("\"<from file>\""), json);
        } finally {
            server.stop(0);
        }
    }
}
