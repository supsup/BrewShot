# BrewShot ☕📸

**For when text is not enough.** Java brews screenshots: a zero-dependency
headless-browser harness — point it at a URL, or hand it raw HTML source, and
get back real-Chrome screenshots, JS evaluation results, and looping GIF
recordings. Pure JDK; the only thing it needs from the world is the Chrome you
already have installed.

```java
try (BrewShot shot = BrewShot.launch(1280, 900)) {
    shot.open("https://example.com");          // an address…
    // shot.html("<h1>or direct source</h1>"); // …or raw HTML — no server, no temp file
    shot.settle(800);
    Object title = shot.eval("document.title");
    shot.screenshot(Path.of("page.png"));
}
```

Or from the shell:

```
brewshot https://example.com -o page.png
cat report.html | brewshot - -o report.png
```

## Why it exists

Testing a 2,000-line browser-side animation runtime from a pure-Java repo left
two bad options: stub a fake DOM (can't prove a real render) or adopt a
browser-automation stack (hundreds of MB of toolchain for a repo that prides
itself on zero dependencies). Reading the [playwright](https://github.com/microsoft/playwright)
source revealed the third option: under everything, driving Chrome bottoms out
in **a handful of JSON messages over a WebSocket** — and the JDK has shipped a
WebSocket client since 11. BrewShot is those messages, wrapped well:

| CDP message | BrewShot surface |
| --- | --- |
| `Page.navigate` | `open(url)` |
| `Page.setDocumentContent` | `html(source)` — scripts execute, load fires |
| `Runtime.evaluate` | `eval(js)` → String/Double/Boolean/Map/List |
| `Page.captureScreenshot` | `screenshot(path)` / `screenshotClip(x,y,w,h)` |
| + JDK ImageIO | `recordGif(x,y,w,h, frames, delayMs, path)` |

First proven as the [LatteX](https://github.com/supsup/LatteX) fx-runtime test
harness, where it pinned real rendering bugs (glyph placement, animation
lifecycle leaks, hover-state wiring) that no stubbed DOM could catch.

## What it's good for

- **Visual pins in JUnit** — "this page renders, and no element exploded" as a
  build-failing assertion, gated with `assumeTrue(BrewShot.available())` so
  Chrome-less CI skips cleanly.
- **Reference artifacts** — commit screenshots/GIFs beside the pages that
  produce them; every change carries its own visual receipt.
- **Agent eyes** — a headless way for tooling to *see* a rendered page.
- **One-off shots** — the CLI, eventually as a GraalVM native binary
  (`./gradlew nativeImage`; the PNG/eval path is native-clean — GIF assembly
  uses ImageIO/AWT, which native-image doesn't yet support on macOS, so GIFs
  are library-mode for now).

## What it deliberately is not

Not a Playwright/Selenium replacement: one browser (local Chrome/Chromium), one
page at a time, no selector engine, no auto-waiting, no cross-browser patches.
`eval()` is the escape hatch for all of that — dispatch events, read layout,
poll conditions with the full power of page-side JS. ~700 lines total, and it
intends to stay that size.

## Requirements

- JDK 21+ (built with 25)
- A local Chrome or Chromium (auto-discovered; override with `BREWSHOT_CHROME`)

## Docs

- [QUICKSTART.md](QUICKSTART.md) — the whole API in two minutes
- [SLOWSTART.md](SLOWSTART.md) — walkthroughs by scenario, including the LatteX
  case study

## License

Apache-2.0 · extracted from the LatteX test harness · design reviewed against
playwright's chromium driver
