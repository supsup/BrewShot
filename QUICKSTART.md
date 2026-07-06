# BrewShot — QUICKSTART

The whole API in two minutes. One class, seven methods, `AutoCloseable`.

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

## Record it

```java
// trigger your animation first (open/html/eval), then film a rectangle:
shot.recordGif(x, y, w, h, /*frames*/ 14, /*delayMs*/ 110, Path.of("anim.gif"));
```

Looping GIF, assembled by the JDK's ImageIO. Frames are real captures — if
nothing animates, all frames match (assert that for trigger-liveness tests).

## CLI

```
brewshot https://example.com -o page.png
brewshot ./report.html -o report.png --size 1440x1000 --settle 1500
cat page.html | brewshot - -o page.png --eval "document.title"
```

`java -jar brewshot.jar …` today; `./gradlew nativeImage` for the native
binary (PNG/eval path only — GIF is library-mode until native-image AWT lands
on macOS).

## Test-gating pattern

```java
@Test
void pageRenders() throws Exception {
    assumeTrue(BrewShot.available(), "no local Chrome; skipping");
    try (BrewShot shot = BrewShot.launch(1200, 900)) { … }
}
```

Skips (never fails) on machines without Chrome — CI stays honest either way.
