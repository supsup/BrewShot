package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
