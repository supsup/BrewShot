# BrewShot ☕📸

[![CI](https://github.com/supsup/BrewShot/actions/workflows/ci.yml/badge.svg)](https://github.com/supsup/BrewShot/actions/workflows/ci.yml)

**For when text is not enough.** Java brews screenshots: a zero-dependency
headless-browser harness — point it at a URL, or hand it raw HTML source, and
get back real-Chrome screenshots, print-fidelity PDFs, JS evaluation results,
and looping GIF recordings. Pure JDK; the only thing it needs from the world is the Chrome you
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
brewshot https://example.com -o page.jpg --jpeg-quality 82
cat report.html | brewshot - -o report.png
brewshot ./fx.html --gif 40 --gif-element ".lx-math" -o fx.gif   # film an element (jar path)
```

In the CLI, still-image extensions are truthful and case-insensitive: `.png` uses PNG;
`.jpg`/`.jpeg` use Chrome's JPEG encoder (`--jpeg-quality 1..100`, default 90);
`.pdf` selects `Page.printToPDF`. Other output extensions are rejected rather
than receiving misnamed PNG bytes. Every path-based screenshot, PDF, GIF,
manifest, diff JSON, and heatmap is completed in a sibling temporary file and
then moved into place (atomic move when supported, complete-temp
same-directory replace otherwise), so a failed encode/write cannot leave a
plausible partial target; temporary residue is cleaned best-effort. The
fallback preserves the complete-before-replace rule but cannot guarantee
atomic replacement on a filesystem that does not support `ATOMIC_MOVE`.
Replacing an existing POSIX target retains its mode bits. A valid output
symlink remains a symlink and its referent is replaced; broken or cyclic
output symlinks fail before temporary-file creation. Output aliases are
rejected before artifacts are written; when either future target is absent,
Unicode-normalized case-only variants are rejected conservatively, including
on case-sensitive mounts.
When `--json` accompanies `--eval`, the manifest's `eval` field preserves the
JSON value itself — null, boolean, number, string, array, or object — instead
of flattening it through `String.valueOf`.
Direct stdin HTML is capped at 16 MiB and `--eval-file` at 1 MiB (byte caps,
UTF-8); inputs at the exact cap are accepted and cap+1 is refused before
decoding.

The `--gif N` lane records N frames as a looping GIF instead of a still —
`--gif-delay` sets the per-frame cadence (capture == playback, default 40 ms) and
`--gif-element CSS` films just that element's box (composes with `--scale`).
GIF stores centiseconds: delays round to the nearest 10 ms (half up), with the
existing 20 ms minimum; `BrewShot.effectiveGifDelayMs(requested)` reports the
exact value, and CLI manifests disclose both requested and encoded delay.
**Jar path only**: GIF assembly rides ImageIO, which the macOS native binary
doesn't have — the CLI reports this loudly instead of half-working.

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
| `Runtime.evaluate` | `eval(js)` → null/String/Double/Boolean/Map/List · `waitFor(predicate, ms)` |
| `Network` + lifecycle | `waitReady()` · `waitForNetworkIdle(quietMs, timeoutMs)` · `waitForFontsReady()` — render-settled waits instead of a blind `settle(ms)` |
| `Runtime.consoleAPICalled` / `exceptionThrown` | `console()` / `errors()` — the page's voice, one-line health asserts |
| `Page.captureScreenshot` | PNG/JPEG `screenshot(path, format, quality)` / `screenshotClip(x,y,w,h)` · `screenshotElement("css")` |
| `Page.printToPDF` | `pdf(path)` / `pdf(path, PdfOptions)` — the page as a paged, print-fidelity PDF |
| `Emulation.setEmulatedMedia` | `colorScheme("dark"\|"light")` · `media("print"\|"screen")` · `reducedMotion("reduce")` |
| `Input.dispatchMouseEvent` | `mouse(x,y)` · `click(x,y)` / `click("css")` · `hover("css")` — real trusted input |
| + JDK ImageIO | `recordGif(rect…)` · `recordGifElement("css", …)` · `recordGifScroll(…)` · `recordGifFullPage(…, scale, …)` · `recordGifRegion(0.5, 1.0, …)` |

**Target one element by CSS selector.** `elementBox("css")` resolves an element's
page-coordinate box (scroll offset folded in), and `screenshotElement`/`recordGifElement`
capture *just that element* — no hand-computing `getBoundingClientRect()`. Trigger the
animation first (`open`/`eval`), then film it: `recordGifElement` resolves the box once and
films that fixed region, so motion *within* the element (glyph jitter, a spinner) is captured
cleanly. Built for exactly this — recording one card's effect out of a page full of them.
All selector-taking surfaces use one JavaScript-string quoting path, including
quotes, backslashes, CR/LF, U+2028, and U+2029; selector data cannot escape
into executable source.

**Poke the page with real input.** `click("css")` / `hover("css")` hit an element's center;
`mouse(x, y)` / `click(x, y)` take *document* coordinates (the same space `elementBox` speaks —
the scroll offset is handled at the CDP seam). These are **trusted browser events**: handlers see
`event.isTrusted === true` and `:hover` styles actually engage — things a page-side
`el.dispatchEvent(new MouseEvent(...))` can never do. Hover something, *then* screenshot it, and
the capture shows the hovered state, because the mouse genuinely stays there.

**Drive a recording frame-by-frame.** `recordGif`/`recordGifElement` overloads take a
`beforeFrame` hook (`IntConsumer`) invoked with the 0-based frame index right before each shot —
trigger the animation at `i == 0`, advance deterministic state (`eval("step()")`), or perturb
mid-recording (`click(...)`, `hover(...)`). The hook runs on the recording thread against the
same instance, so it composes with the whole surface; an exception aborts the recording.
Counts and capture/playback delays are positive contracts and fail before any
capture; clip origins are finite (negative page coordinates remain valid),
while width/height/scale are finite and positive.

**Scale is a re-raster, not an upscale.** The `scale` on `screenshotClip`/`screenshotElement`
makes Chrome **re-render** the clip region at that factor — `screenshotElement("svg", 3.0)`
turns a 360×140 CSS-px box into a genuinely crisp 1080×420 bitmap (vector content, fonts and
hairlines re-rasterized at 3× density), not a blurry blow-up. The arithmetic is pinned by
test: the clip rect is CSS px, the output bitmap is exactly `rect × scale`. That's the whole
"sharp element PNG" story in one call — no CSS-transform wrappers, no manual rect math.
`screenshotElement("css", scale, paddingPx)` inflates the box with breathing room first
(CSS px, pre-scale), so tight crops don't need a padding div. The same knobs ride the CLI:
`--clip-selector`, `--scale`, `--clip-padding` — and `--scale` alone re-rasters the full
page box:

```bash
brewshot page.html -o card.png --clip-selector "#card" --scale 3 --clip-padding 8
```

**Scroll-pan a tall page.** `recordGifScroll(panFrames, holdFrames, playbackDelayMs, scale, out)`
glides the camera from the top of the document to the bottom — one viewport-height window per
frame, smoothstep-eased so it accelerates and settles — turning a long static page (a docs page,
a showcase, a changelog) into a smooth guided tour. `holdFrames` pauses at top and bottom so the
loop reads. (Unlike `recordGifFullPage`, which re-shoots the *whole* page each frame; this pans a
window *down* it.)

```java
try (BrewShot b = BrewShot.launch(1120, 800)) {   // launch height = the pan window
    b.open("file:///…/showcase.html");
    b.recordGifScroll(46, 8, 90, 0.55, Path.of("scroll.gif"));  // 46 pan + 8 hold each end
}
```

### GIF playback speed (fps) — separate from capture

The per-frame **playback** delay is the speed knob, independent of how densely you **sample**.
Every recorder takes a single `frameDelayMs` (capture == playback, ≈ real time); the `recordGif`
and `recordGifElement` overloads split it into `(captureDelayMs, playbackDelayMs)` so you can
**sample a fast effect densely and replay it slowly**: `recordGifElement(".fx", 60, 25, 75, s, out)`
shoots 60 frames ~25 ms apart; requested 75 ms rounds to an encoded 80 ms/frame.
**FPS = `1000 / effectiveGifDelayMs(playbackDelayMs)`** — so a *bigger*
effective delay is a lower fps and a slower GIF (a slower scroll, a slower effect).

| requested | encoded | ≈ fps | good for |
|---|---|---|---|
| 33 ms | 30 ms | ~33 | real-time smoothness — UI motion, a spinner, "does it feel right" |
| 50 ms | 50 ms | ~20 | lively but legible — hover/click micro-interactions |
| 75 ms | 80 ms | ~12.5 | **catalogue/showcase default** — an effect or a scroll you can actually read |
| 100 ms | 100 ms | ~10 | study pace — walk someone through each step |
| 150 ms | 150 ms | ~7 | slow-mo — a fast effect (glitch, a shatter) frame-by-frame; a leisurely scroll |

Chrome's shot time floors real capture cadence at ≈20-30 ms, so `captureDelayMs` below that just
samples as fast as it can. GIF playback has 10 ms granularity and a 20 ms
minimum; use `effectiveGifDelayMs` when exact duration/fps matters.

**Stream instead of polling — `recordGifStream`.** The poll recorders shoot, wait, shoot; Chrome's
per-shot cost floors that cadence at ≈20-30 ms. `recordGifStream(durationMs, playbackDelayMs, out)`
opens a CDP screencast instead: Chrome *pushes* a frame every time the compositor produces one, so
you capture what actually rendered, at the pace it rendered — measured ~9× denser than the poll
path (112 frames vs 12 over the same 1.2 s window on an rAF spinner). The widest overload adds
`firstFrameDelayMs` (poster-frame hold, as below) and `maxWidth` (downscale bound; `0` = natural
size), and returns the captured frame count. Two honest limits: frames are **viewport-only**
(scroll the subject into view first; element/region targeting stays with the poll recorders), and
a page that never composites during the window throws instead of writing an empty GIF — a static
page has nothing to film.

**Hold the opening frame.** `recordGifElement`'s widest overload takes a `firstFrameDelayMs` — the
first frame is held that long before the animation runs, so the viewer registers the *before* state
(an intact equation, a button at rest) then watches it change:
`recordGifElement(".fx", 60, 25, 75, 900, s, out)` holds frame 0 for 900 ms, then plays the rest at
80 ms (75 ms requested, rounded). The per-frame delay lives in one file — no repeated frames.

### Recording a triggered animation — recipe

Capturing an effect that fires on hover/click has three gotchas, learned the hard way filming the
LatteX fx catalogue:

1. **Trigger *after* recording starts, not before.** If you fire the click then start recording,
   you miss the opening (the frames fly by before the first shot). Use the `beforeFrame` hook to
   trigger *inside* the recording — the early frames catch the *before* state, then the effect runs:
   `recordGifElement(".fx", 60, 25, 75, 1.3, i -> { if (i == 2) shot.click(".fx"); }, out)`.
   Pair with `firstFrameDelayMs` to hold that intact opening. (The hook's `click`/`hover` are
   trusted input, so `isTrusted`-checking handlers fire too — the old page-side
   `setTimeout(() => el.dispatchEvent(...))` workaround is obsolete, and never engaged `:hover`.)
2. **Sample dense, play slow.** Use the `(captureDelayMs, playbackDelayMs)` overload: shoot as fast
   as Chrome allows (~25 ms) and stamp a slower playback (75–110 ms). A fast effect stays smooth but
   readable — no re-encoding afterward.
3. **Watch for effects that leave the element's box.** `recordGifElement` films the box resolved
   *once* at the start. An effect that scatters glyphs, spawns a body overlay, or drifts (an ink
   diffusion, a page-wide flash) will clip or wander. Fixes: `scrollIntoView` + capture a padded
   `recordGif(rect…)` region instead, or reach for the frame-stream path (see `Page.startScreencast`,
   a planned `recordGifStream`) which follows what actually composites.

First proven as the [LatteX](https://github.com/supsup/LatteX) fx-runtime test
harness, where it pinned real rendering bugs (glyph placement, animation
lifecycle leaks, hover-state wiring) that no stubbed DOM could catch.

## Print to PDF — paged capture, native-binary-clean

`pdf(path)` renders the whole document via CDP `Page.printToPDF` — a paged,
print-fidelity PDF, not a raster. The default is deliberately *not* the
browser's print dialog (which drops backgrounds and adds margins): US Letter,
portrait, zero margins, backgrounds **on**, scale 1.0 — the page as a print
artifact. `PdfOptions` withers tune it per call, and a bad envelope
(non-finite/non-positive paper, non-finite/negative margin, non-finite scale
or scale outside CDP's 0.1–2.0) throws
`IllegalArgumentException` instead of an opaque Chrome reject:

```java
try (BrewShot shot = BrewShot.launch(1280, 900)) {
    shot.open("https://example.com");
    shot.waitReady();
    shot.pdf(Path.of("page.pdf"));               // US Letter, full-bleed

    shot.pdf(Path.of("report.pdf"),              // A4 landscape,
        BrewShot.PdfOptions.a4()                 // half-inch margins
            .landscape(true).margin(0.5));       // all round
}
```

From the shell, a `.pdf` output path is the whole story — the CLI infers the
mode from the extension:

```bash
brewshot https://example.com -o page.pdf
```

(`--clip-selector` / `--scale` / `--clip-padding` are raster-only and don't
apply to `.pdf` output — PDF is paged, not clipped.)

Unlike GIF recording, PDF rides **no ImageIO/AWT** — `printToPDF` hands back
the PDF bytes directly — so the same call works everywhere *including the
macOS native binary* (the metrics table's GIF caveat doesn't apply here).

## Emulated media — dark mode, print, reduced motion

A dark-mode-only stylesheet, an `@media print` layout, or a CSS animation guarded by
`@media (prefers-reduced-motion: reduce)` can't be captured faithfully by just opening the
page — the browser reports its *actual* OS-level preferences unless told otherwise.
`colorScheme`/`media`/`reducedMotion` force those media features via CDP
`Emulation.setEmulatedMedia`, **before** the capture:

```java
try (BrewShot shot = BrewShot.launch(1280, 900)) {
    shot.colorScheme("dark");        // "dark" | "light" | "no-preference"
    shot.reducedMotion("reduce");    // "reduce" | "no-preference" — freezes @media
                                      // (prefers-reduced-motion: reduce) { animation: none }
    shot.open("https://example.com");
    shot.screenshot(Path.of("dark.png"));

    shot.media("print");             // "print" | "screen" — @media print rules apply
    shot.screenshot(Path.of("print-preview.png"));
}
```

Each setter is chainable (returns `this`, like `navTimeout`/`commandTimeout`) and takes effect
immediately, whether called before or after `open`/`html` — and it **survives every subsequent
navigation** on the same instance without being called again: `open`/`html` re-send whatever
override is active before the new document paints, so a second `open()` on the same `shot`
never silently reverts to the browser's real preference. The same three knobs ride the CLI:

```bash
brewshot page.html -o dark.png --color-scheme dark
brewshot page.html -o preview.png --media print
brewshot page.html -o still.png --reduced-motion
```

## Compare two shots — a citable verdict, not an eyeball job

`brewshot diff` turns "did this page change?" into a sentence you can paste
straight into a PR review (on private repos a quoted verdict travels where an
image upload won't):

```bash
$ brewshot diff before.png after.png --json verdict.json --diff-out heat.png
0.55% of pixels changed (1365 of 250400; 771 anti-aliasing px ignored);
largest cluster at 173,203 (43x17, 20% of the change) — in the body.
```

- **Anti-aliasing forgiveness is ON by default** — a raw AA diff of two
  re-renders is a noise wall (font hinting shifts every glyph edge a pixel).
  A 3×3 shifted-edge heuristic forgives those, and everything it forgives is
  **counted and printed** in the verdict — nothing is silently eaten.
  `--pixel-exact` opts out for byte-faithful comparison.
- **Threshold gate**: `--fail-over 0.5` (percent) / `--fail-pixels 100` →
  **exit 4 with the verdict and artifacts still written** — the same
  evidence-first contract as `--fail-js`. Percent thresholds are bounded to
  0–100, and per-channel tolerance is bounded to 0–254 so even a maximum
  255-channel delta remains observable.
- **Mask dynamic regions** (`--mask x,y,w,h`, repeatable): zero a clock or
  spinner on both images so the numbers stay stable and citable. Masks require
  positive extents, are clipped to the image, and a wholly outside mask is an
  intentional no-op. Library `Options` owns deep copies of masks.
- **Heatmap** (`--diff-out diff.png`): the base image dimmed, changed pixels
  magenta — the eyes-artifact companion when someone does want to look.
- **Localization**: connected-component analysis names the largest changed
  cluster (centroid, box, share) and its page band — header / body / footer —
  so the verdict says *where to look first*.
- A **size mismatch** renders an explicit verdict (never a crash); under any
  `--fail-*` gate it exits 4.

No Chrome involved — `diff` is pure JDK image work, so it runs anywhere the
jar runs (it rides ImageIO, the same JVM-path caveat as GIF recording; not
the macOS native binary). Library callers get the same engine as
`BrewShotDiff.diff(imgA, imgB, options)` → a `Verdict` record.
`Options.masks()` and `Verdict.changedBounds()` return defensive copies, so
mutating caller arrays cannot rewrite a later comparison or sidecar.

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
page at a time, no selector engine, no cross-browser patches. Waiting is
opt-in: `open()` returns on the load event, and you reach for `waitReady()`
(network-idle + fonts) or `waitFor(predicate)` when a page settles after load.
`eval()` is the escape hatch for all of that — dispatch events, read layout,
poll conditions with the full power of page-side JS. ~800 lines of core CDP
client (≈1,300 with the CLI and JSON codec), and it intends to stay lean.

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

The repo ships a self-contained Java 25 image with Chromium and fonts. It runs
as fixed non-root user `10001:10001`, keeps immutable jars under
`/opt/brewshot`, and offers two modes:

- the original one-shot CLI (still the default; `cli` is an optional explicit
  spelling), including URL, stdin, PNG/JPEG/PDF, GIF, diff, and advanced flags;
- a long-running `watch` worker over `/brewshot/input` and
  `/brewshot/output`.

Build it and use the old CLI shape unchanged:

```sh
docker build -t brewshot .
docker run --rm -v "$PWD:/work" brewshot \
  https://example.com -o /work/page.png
```

For mounted file input, the input mount can stay read-only while output remains
writable:

```sh
mkdir -p Input Output
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --mount type=bind,src="$PWD/Input",dst=/brewshot/input,readonly \
  --mount type=bind,src="$PWD/Output",dst=/brewshot/output \
  brewshot cli /brewshot/input/page.html \
    -o /brewshot/output/page.png
```

### Watched input and output folders

Run a persistent local-HTML worker with writable input and output mounts:

```sh
mkdir -p Input Output
docker run -d --name brewshot-worker --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  --mount type=bind,src="$PWD/Input",dst=/brewshot/input \
  --mount type=bind,src="$PWD/Output",dst=/brewshot/output \
  brewshot watch
```

Watch mode accepts complete, visible, regular, direct-child `.html` and `.htm`
files only. They should be self-contained: claiming moves the file into a
job-specific processing directory, so sibling asset paths are not a worker
contract. URL manifests and arbitrary option manifests are intentionally not
accepted; use the unchanged CLI for URLs and advanced capture options.

Publish a complete job with a hidden sibling plus atomic rename so the worker
never observes a partial upload:

```sh
cp page.html Input/.page.html.tmp
mv Input/.page.html.tmp Input/page.html
```

For `page.html`, success creates `Output/page.html.png` and moves the original
to `Input/finished/page.html`:

```text
Input/page.html
  -> Input/processing/<job-id>/page.html
  -> Input/finished/page.html
```

A failed capture instead creates a bounded, content-free
`Output/page.html.error.txt` and moves the source below `Input/failed`.
Existing outputs, diagnostics, and archives are never overwritten; collisions
are retained under job-id directories. Valid processing claims recover after a
restart, and multiple workers may share the same mounts. Set
`BREWSHOT_WATCH_POLL_MS` to `10` through `60000` milliseconds (default `500`).

> [!WARNING]
> On Linux, map the process to the host UID/GID as above or make the bind
> folders writable by the image's `10001:10001` user. Docker Desktop usually
> translates ownership automatically; Linux does not. Watch input must be
> writable because the worker performs atomic state transitions. Individual
> input files only need to be readable: a foreign-owned mode-`0444` file is
> finalized with owner-agnostic directory/file moves rather than a hard link.
> One-shot CLI input can remain read-only. Details:
> [SLOWSTART](SLOWSTART.md) Scenario 5.

Rolling your own image: install `chromium` + fonts (`fonts-liberation`,
`fonts-dejavu-core`), set `BREWSHOT_CHROME=/usr/bin/chromium` and
`BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"` — scope
`--no-sandbox` to containers rendering YOUR OWN pages; it removes the layer
that contains a malicious page, so don't point a sandboxless browser at
untrusted URLs. (The provided image also runs as a non-root user.) Full walkthrough:
[SLOWSTART.md](SLOWSTART.md) Scenario 5.

## Security

BrewShot hands your input to Chromium and reads back bytes + JSON — it never
interprets page content itself, so a hostile page is Chromium's threat model,
not BrewShot's. The one Java-side ingestion point (`MiniJson`, for `eval`
results) is depth-capped and fails closed. The real risks are the ordinary
headless-browser ones (SSRF reach, `--no-sandbox` in containers, injecting
untrusted data into your own `eval` string). Full threat model + the three
things not to do: **[SECURITY.md](SECURITY.md)**.

## Requirements

- JDK 21+ (built with 25)
- A local Chrome or Chromium (auto-discovered; override with `BREWSHOT_CHROME`)

On macOS, the execution context is part of the browser requirement. A normal
Terminal is the supported host-native lane, and the provided Linux container is
the portable control. Unified Chrome 150 is known to abort in an inherited
Codex Seatbelt context (`CODEX_SANDBOX=seatbelt`) before it exposes DevTools, so
BrewShot refuses that exact combination before it creates a generated profile
or starts Chrome. Run the command from a normal Terminal/container, or
explicitly set `BREWSHOT_CHROME` to a compatible `chrome-headless-shell`; the
refusal does not silently fall back to another browser.

For allowed launches, bootstrap listens to three bounded endpoint witnesses:
stdout, stderr, and the generated profile's validated `DevToolsActivePort`.
They share one deadline and must agree when more than one appears. A failure
names process exit, alive timeout, malformed endpoint, or disagreement and may
include only bounded sanitized stream tails. BrewShot never uses the operator's
default Chrome profile or blanket-terminates unrelated Chrome processes.

Locally, the Chrome-driving tests loud-skip when no Chrome is found. In CI they
must not: the reference workflow sets `BREWSHOT_REQUIRE_CHROME=1`, which turns any
skip into a failure — so a green build proves the end-to-end suite actually ran,
never "green that tested nothing."

The load/navigation wait budget defaults to 15s; raise it for a heavy page with
`BREWSHOT_TIMEOUT_MS` or per-instance `shot.navTimeout(ms)`.

Resource controls, each a separate axis:

- **CDP inbox budgets** — every complete DevTools message is measured as exact
  UTF-8 while its WebSocket fragments are reassembled
  (`-Dbrewshot.maxCdpMessageBytes`, default 32 MiB). The undrained queue is
  independently capped by regular-message count
  (`-Dbrewshot.maxInboxMessages`, default 4096) and aggregate exact UTF-8 bytes
  (`-Dbrewshot.maxInboxBytes`, default 32 MiB). Dequeue returns byte capacity;
  these are live retained-content bounds, not lifetime traffic quotas. The
  message and aggregate byte properties are independently configured: raising
  one does not implicitly raise the other. One extra physical queue slot
  remains reserved for typed socket closure. The
  producer-measured reservation travels with each queued message, including
  across a split surrogate-pair callback boundary. Operators can retrieve the
  total and per-cause drop counters with `shot.inboxDropped()`,
  `shot.inboxCountDropped()`, and `shot.inboxByteDropped()`.
- **Per-CDP-call budget** — how long one DevTools round-trip may take (a
  full-page screenshot of a tall document is the motivating case: navigation was
  fast, the single capture call wasn't). `BREWSHOT_COMMAND_TIMEOUT_MS` or
  `shot.commandTimeout(ms)`; falls back to `BREWSHOT_TIMEOUT_MS`, then 15s.
- **Recording heap budget** — the GIF recorders hold every PNG frame in memory
  until encoding, so an unbounded recording is an OOM waiting for a long enough
  page. Both recorder families (frame-count and screencast) stop at
  `BREWSHOT_MAX_RECORDING_BYTES` (default 256 MiB; `shot.recordingHeapBudget(bytes)`),
  write the frames captured so far, and say so on stderr — a truncated GIF that
  announces itself beats both an OOM and a silently short one.
- **Still-image dimensions** — Chrome's Base64 CDP result and BrewShot's decoded
  compressed byte array already exist when BrewShot reads the image header.
  `-Dbrewshot.maxImageDimension` (default 16384/axis) and
  `-Dbrewshot.maxImagePixels` (default 64 MP) refuse the image before artifact
  write or downstream full-raster decode; they do not claim to precede the CDP or
  Base64 allocations.
- **GIF decoded-raster accounting** — before full frame decode,
  `-Dbrewshot.gif.maxFrames` (default 1000),
  `-Dbrewshot.gif.maxFrameDimension` (default 4096/axis), and
  `-Dbrewshot.gif.maxDecodedBytes` (default 512 MiB) inspect frame headers and
  bound `Σ width * height * 4`. The last value is a raster accounting proxy, not
  total encoder heap: compressed frames, Java objects, palette/index buffers,
  and encoder state are additional.

## Docs

- [QUICKSTART.md](QUICKSTART.md) — the whole API in two minutes
- [SLOWSTART.md](SLOWSTART.md) — walkthroughs by scenario, including the LatteX
  case study
- [AGENTS.md](AGENTS.md) — giving an AI eyes: CLI pattern, skill example, MCP recipe

## License

Apache-2.0 · extracted from the LatteX test harness · design reviewed against
playwright's chromium driver

## Disclaimer

This project is provided under the Apache License 2.0 on an "AS IS" basis,
without warranties or conditions of any kind. See the LICENSE file for details.
