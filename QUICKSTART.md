# BrewShot — QUICKSTART

The whole API in two minutes. One class, a dozen small methods, `AutoCloseable`.

## Get in

```java
import com.brewshot.BrewShot;

if (!BrewShot.available()) { return; }        // no local Chrome → bow out
try (BrewShot shot = BrewShot.launch()) {     // or launch(width, height)
    ...
}                                             // Chrome + temp profile cleaned up
```

Chrome is auto-discovered (macOS app path, Linux names). Override:
`export BREWSHOT_CHROME=/path/to/chrome`.

## Feed it a page — two ways

```java
shot.open("https://example.com");                     // 1. an ADDRESS (http/https/file)
shot.open(Path.of("report.html").toUri().toString()); //    …incl. local files

shot.html("<h1>hi</h1><script>…</script>");           // 2. DIRECT HTML SOURCE
```

`html(source)` replaces the page document with document.write semantics —
**inline scripts execute**, styles apply, load fires. No server, no temp file.

## Look at it

```java
shot.settle(800);                              // let animations/layout settle

Object v = shot.eval("document.title");        // any JS expression; promises awaited.
// returns String / Double / Boolean / Map / List / null (JSON-serializable values)

shot.screenshot(Path.of("page.png"));          // full page, beyond the viewport
byte[] png = shot.screenshotClip(x, y, w, h);  // one rectangle, page coordinates
```

`eval` is the escape hatch for everything: dispatch events
(`el.dispatchEvent(new MouseEvent('mouseenter',{bubbles:true}))`), read
`getBoundingClientRect()`, scroll, poll a condition.

## Listen to the page

```java
shot.waitFor("document.querySelector('.done')", 5000); // deterministic wait, fails loud
List<String> logs = shot.console();   // "log: hello 42" — since last open()/html()
List<String> errs = shot.errors();    // uncaught exceptions + console.error
assertEquals(List.of(), shot.errors());  // the one-line page-health assertion
shot.captureConsole(false);           // opt out if you want zero retention
```

## Get past auth

```java
shot.header("Authorization", "Basic dXNlcjpwYXNz");  // any header — NOTE: sent on EVERY
                                                     // request incl. cross-origin subresources;
                                                     // for host-scoped creds prefer cookie()
shot.cookie("SESSION", token, "localhost");          // session-cookie auth
shot.open("http://localhost:8080/private/page");     // set BEFORE open()
```

## Record it

```java
// trigger your animation first (open/html/eval), then film it:
shot.recordGif(x, y, w, h, /*frames*/ 14, /*delayMs*/ 110, Path.of("anim.gif"));

shot.recordGifFullPage(30, 130, /*scale*/ 0.4, Path.of("whole-page.gif"));
// every viewport of the document; scale keeps bytes sane (in-browser, free)

shot.recordGifRegion(0.5, 1.0, 24, 130, 0.55, Path.of("bottom-half.gif"));

// Smoothest capture of a live animation: stream frames at the compositor's own
// pace (viewport-only) instead of polling — ~9x denser over the same window.
int frames = shot.recordGifStream(/*durationMs*/ 1200, /*playbackDelayMs*/ 60, Path.of("smooth.gif"));
// fractions of document height: (0, 0.5)=top half, (0.25, 0.75)=the middle
```

Looping GIFs, assembled by the JDK's ImageIO. Frames are real captures — if
nothing animates, all frames match (assert that for trigger-liveness tests).
Stills have the same region trick: `screenshotRegion(from, to, scale)`, and
`screenshotClip` takes an optional `scale` for cheap downscaled captures.
For a screen-recording-style TOUR (viewport frames while the page scrolls),
compose it from the primitives — recipe in SLOWSTART.

## CLI

```
brewshot https://example.com -o page.png
brewshot ./report.html -o report.png --size 1440x1000 --settle 1500
cat page.html | brewshot - -o page.png --eval "document.title"

# one element, crisp: selector clip + 3x re-raster (no hand-rolled rect JS)
brewshot page.html -o card.png --clip-selector "#card" --scale 3 --clip-padding 8

# CI/agent shape: deterministic wait, one panel, assertion, manifest sidecar
brewshot http://localhost:8080/route -o shot.png \
  --wait-js "document.readyState==='complete'" \
  --clip-selector main \
  --fail-js "!document.querySelector('.error-banner')" \
  --cookie "SESSION=tok@localhost" --json shot.json
# exit 4 when --fail-js is false — the PNG is still written (failures carry eyes)
# (--clip-js still exists for computed rects; --clip-selector covers the common case)
```

`java -jar brewshot.jar …` works everywhere, GIFs included. The native binary
(`./gradlew nativeImage`) does PNG + eval everywhere too — its ONE gap: no GIF
assembly on macOS (native-image doesn't support AWT/ImageIO there yet; on a
JVM, GIFs always work — that's how every example GIF was made).

## Test-gating pattern

```java
@Test
void pageRenders() throws Exception {
    assumeTrue(BrewShot.available(), "no local Chrome; skipping");
    try (BrewShot shot = BrewShot.launch(1200, 900)) { … }
}
```

Skips (never fails) on machines without Chrome — CI stays honest either way.
