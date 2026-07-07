package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * header()/cookie() verified against a real HTTP exchange: a JDK-built-in
 * HttpServer echoes the Authorization and Cookie request headers into the
 * page body, and the browser-side eval reads them back. Still zero deps.
 */
class BrewShotAuthTest {

    @Test
    void headerAndCookieReachTheServer() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = "auth=[" + String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))
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
            shot.cookie("SESSION", "tok-123", "127.0.0.1");
            shot.open("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertEquals("auth=[Basic dXNlcjpwYXNz] cookie=[SESSION=tok-123]",
                shot.eval("document.body.textContent.trim()"));
        } finally {
            server.stop(0);
        }
    }
}
