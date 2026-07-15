package com.brewshot;

/**
 * Subprocess body for {@link ShutdownHookSmokeTest}: launch a real BrewShot, print
 * {@code READY <profileDir>} so the test knows what must be cleaned up, then park until the
 * test SIGTERMs this JVM — the idiomatic Ctrl+C path the shutdown hook claims to cover.
 * The profile dir is read reflectively so the probe adds no production API surface.
 */
public final class ShutdownHookProbeMain {

    private ShutdownHookProbeMain() { }

    public static void main(String[] args) throws Exception {
        BrewShot shot = BrewShot.launch();
        // Dirty the profile the way real usage does (the F1-r2 survivor was a Chrome
        // shutdown-flush file): render a page and pump cookie writes (CDP-set cookies land in
        // the profile's Cookies store regardless of page origin — localStorage would throw on
        // the opaque about:blank origin) so the Chrome tree has pending profile state to flush
        // when it dies. Without this pressure the delete race has nothing to lose to and the
        // test would pass even against the old racey hook.
        shot.html("<h1>probe</h1>");
        for (int i = 0; i < 300; i++) {
            shot.cookie("k" + i, "v".repeat(512), "pressure-" + (i % 20) + ".example");
        }
        java.lang.reflect.Field field = BrewShot.class.getDeclaredField("profileDir");
        field.setAccessible(true);
        System.out.println("READY " + field.get(shot));
        System.out.flush();
        Thread.sleep(60_000); // parked; the test kills us long before this elapses
    }
}
