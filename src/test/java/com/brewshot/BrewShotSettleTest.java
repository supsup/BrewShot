package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-readiness tests: open() returns on loadEventFired, which fires
 * BEFORE an async fetch settles; waitForNetworkIdle must bridge that gap so the
 * captured page reflects the late content, not the mid-flight state.
 *
 * <p>(Off main, pre-CI-honesty branch, so it still gates with assumeTrue; on a
 * rebase past the ChromeGate slice this switches to ChromeGate.gate().)
 */
class BrewShotSettleTest {

    @Test
    void networkIdleWaitsForAPostLoadFetch() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");

        // "/" loads instantly and kicks off a fetch to a SLOW endpoint; "/late"
        // takes 500ms then returns DONE, which the page stores on window.__late.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ("<!doctype html><html><body>hi<script>"
                + "fetch('/late').then(function (r) { return r.text(); })"
                + ".then(function (t) { window.__late = t; });"
                + "</script></body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/late", ex -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            byte[] body = "DONE".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try (BrewShot shot = BrewShot.launch(400, 300)) {
            shot.open("http://127.0.0.1:" + port + "/");

            // Load has fired, but the 500ms fetch is still in flight: the late
            // content is NOT there yet — this is exactly the gap a blind settle races.
            assertNull(shot.eval("window.__late || null"),
                "fetch should still be pending right after loadEventFired");

            shot.waitForNetworkIdle(200, 5_000);

            // After network-idle, the fetch has resolved and its result is applied.
            assertEquals("DONE", shot.eval("window.__late || null"),
                "waitForNetworkIdle must wait for the post-load fetch to settle");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void networkIdleReachesIdleThroughARedirectInsteadOfBurningTheTimeout() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");

        // "/" kicks off fetch('/redirect'); "/redirect" 302s to "/final" which
        // returns DONE. CDP reuses ONE requestId across the 302 hop (two
        // requestWillBeSent — the second carrying redirectResponse — but a SINGLE
        // terminal loadingFinished), so a counter-based tracker leaks a permanent
        // +1 and waitForNetworkIdle runs the FULL timeout (brewshot #82). The
        // requestId-Set tracker treats the redirect hop as an idempotent add and
        // reaches true idle.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ("<!doctype html><html><body>hi<script>"
                + "fetch('/redirect').then(function (r) { return r.text(); })"
                + ".then(function (t) { window.__redir = t; });"
                + "</script></body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/final");
            ex.sendResponseHeaders(302, -1); // 302 with no body → the fetch follows it
            ex.close();
        });
        server.createContext("/final", ex -> {
            byte[] body = "DONE".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try (BrewShot shot = BrewShot.launch(400, 300)) {
            shot.open("http://127.0.0.1:" + port + "/");

            long timeout = 8_000;
            long start = System.currentTimeMillis();
            shot.waitForNetworkIdle(200, timeout);
            long elapsed = System.currentTimeMillis() - start;

            // The counter over-count would run to the full timeout; the fix
            // returns at true idle, far below it. (Wide margin for CI jitter: the
            // bug is ~8000ms, the fix a few hundred ms.)
            assertTrue(elapsed < timeout - 2_000,
                "waitForNetworkIdle must reach idle THROUGH the 302, not burn the "
                    + timeout + "ms timeout (took " + elapsed + "ms)");
            // And it genuinely reached idle rather than returning early: the
            // redirected fetch resolved and its result was applied.
            assertEquals("DONE", shot.eval("window.__redir || null"),
                "the fetch through the 302 must have settled by network-idle");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fontsReadyAndNavTimeoutSetterAreWiredAndSafe() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        try (BrewShot shot = BrewShot.launch(400, 300)) {
            assertEquals(shot, shot.navTimeout(30_000), "navTimeout is fluent");
            shot.html("<!doctype html><html><body><span>font check</span></body></html>");
            shot.waitForFontsReady(); // must not throw on a page with the Font Loading API
            assertEquals("font check", shot.eval("document.querySelector('span').textContent"));
        }
    }
}
