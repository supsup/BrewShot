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
| `Runtime.evaluate` | `eval(js)` → String/Double/Boolean/Map/List · `waitFor(predicate, ms)` |
| `Runtime.consoleAPICalled` / `exceptionThrown` | `console()` / `errors()` — the page's voice, one-line health asserts |
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
- **Agent eyes** — a headless way for an AI to *see* a rendered page:
  [AGENTS.md](AGENTS.md) has the CLI pattern, a drop-in skill example, and an
  honest MCP-server recipe (the first two are how this project itself is
  developed).
- **One-off shots** — the CLI: `java -jar` today, or the GraalVM native
  binary (`./gradlew nativeImage`) for instant no-JVM startup. See the
  metrics table below — including the one GIF caveat that applies *only* to
  the native binary on macOS.

## What it deliberately is not

Not a Playwright/Selenium replacement: one browser (local Chrome/Chromium), one
page at a time, no selector engine, no auto-waiting, no cross-browser patches.
`eval()` is the escape hatch for all of that — dispatch events, read layout,
poll conditions with the full power of page-side JS. ~700 lines total, and it
intends to stay that size.

## Metrics — jar vs native binary

Measured on an Apple-silicon Mac (GraalVM CE for JDK 25, `./gradlew nativeImage`),
same tiny page, identical PNG output from both:

| | `brewshot.jar` | `build/brewshot` (native) |
| --- | --- | --- |
| artifact size | **17 KB** (+ a JVM on the machine) | 32 MB, fully self-contained |
| CLI startup (`--help`, warm) | ~20 ms | **~3 ms** |
| full shot (launch Chrome → render → PNG) | ~2.0 s | **~1.7 s** |
| needs a JVM installed | yes | **no** |
| GIF recording | **yes** | not on macOS yet¹ |

The honest read: **Chrome launch dominates a full shot** (~1.4 s), so the
native win there is modest (~0.3 s of skipped JVM warm-up). Where the binary
earns its keep is deployment — one file, no JVM, instant startup — which is
exactly the shape a pipeline step or an agent tool wants. The jar earns its
keep at 17 KB with full GIF support. Ship both, pick per context.

First run of a fresh binary pays macOS code-signature verification (~0.3 s,
once). PNG outputs are byte-identical across modes.

¹ To be clear about the GIF caveat: **GIFs work on every platform when you run
on a JVM** — the library in your tests, `java -jar` from the shell — because
the encoder is the JDK's own ImageIO. The limitation applies only to the
**native-compiled executable**: GraalVM's native-image doesn't support AWT
(which ImageIO rides on) on macOS yet, so `build/brewshot` can screenshot but
not assemble GIFs on a Mac. Same code, two execution modes — the caveat lives
entirely in the second one.

## Running in a container

The repo ships a `Dockerfile` (jar + Chromium + fonts, self-contained — GIFs
included, since the container runs a JVM):

```
docker build -t brewshot .
docker run --rm -v "$PWD:/work" brewshot https://example.com -o /work/page.png
```

(The `-v "$PWD:/work"` mount is how the PNG reaches your directory — the
container's own filesystem vanishes with `--rm`. Input files ride the same
mount.)

> [!WARNING]
> On **Linux hosts**, add `--user "$(id -u)"` if the output dies with
> `Permission denied` — the image runs non-root by design, and a bind-mounted
> directory must be writable by that user. Works-on-my-Mac is not evidence
> here: Docker Desktop maps permissions automatically, Linux CI does not.
> Details: [SLOWSTART](SLOWSTART.md) Scenario 5.

Rolling your own image: install `chromium` + fonts (`fonts-liberation`,
`fonts-dejavu-core`), set `BREWSHOT_CHROME=/usr/bin/chromium` and
`BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"` — scope
`--no-sandbox` to containers rendering YOUR OWN pages; it removes the layer
that contains a malicious page, so don't point a sandboxless browser at
untrusted URLs. (The provided image also runs as a non-root user.) Full walkthrough:
[SLOWSTART.md](SLOWSTART.md) Scenario 5.

## Requirements

- JDK 21+ (built with 25)
- A local Chrome or Chromium (auto-discovered; override with `BREWSHOT_CHROME`)

## Docs

- [QUICKSTART.md](QUICKSTART.md) — the whole API in two minutes
- [SLOWSTART.md](SLOWSTART.md) — walkthroughs by scenario, including the LatteX
  case study
- [AGENTS.md](AGENTS.md) — giving an AI eyes: CLI pattern, skill example, MCP recipe

## License

Apache-2.0 · extracted from the LatteX test harness · design reviewed against
playwright's chromium driver
