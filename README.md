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
| `Page.captureScreenshot` | `screenshot(path)` / `screenshotClip(x,y,w,h)` · `screenshotElement("css")` |
| `Input.dispatchMouseEvent` | `mouse(x,y)` · `click(x,y)` / `click("css")` · `hover("css")` — real trusted input |
| + JDK ImageIO | `recordGif(rect…)` · `recordGifElement("css", …)` · `recordGifScroll(…)` · `recordGifFullPage(…, scale, …)` · `recordGifRegion(0.5, 1.0, …)` |

**Target one element by CSS selector.** `elementBox("css")` resolves an element's
page-coordinate box (scroll offset folded in), and `screenshotElement`/`recordGifElement`
capture *just that element* — no hand-computing `getBoundingClientRect()`. Trigger the
animation first (`open`/`eval`), then film it: `recordGifElement` resolves the box once and
films that fixed region, so motion *within* the element (glyph jitter, a spinner) is captured
cleanly. Built for exactly this — recording one card's effect out of a page full of them.

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
shoots 60 frames ~25 ms apart, played back at 75 ms/frame. **FPS = `1000 / playbackDelayMs`** —
so a *bigger* delay is a *lower* fps is a *slower* GIF (a slower scroll, a slower effect).

| playbackDelayMs | ≈ fps | good for |
|---|---|---|
| 33 ms | ~30 | real-time smoothness — UI motion, a spinner, "does it feel right" |
| 50 ms | ~20 | lively but legible — hover/click micro-interactions |
| 75 ms | ~13 | **catalogue/showcase default** — an effect or a scroll you can actually read |
| 100 ms | ~10 | study pace — walk someone through each step |
| 150 ms | ~7 | slow-mo — a fast effect (glitch, a shatter) frame-by-frame; a leisurely scroll |

Chrome's shot time floors real capture cadence at ≈20-30 ms, so `captureDelayMs` below that just
samples as fast as it can; `playbackDelayMs` has no floor — set it purely for the speed you want.

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
75 ms. (GIF stores a per-frame delay, so this is one file — no repeated frames.)

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
  evidence-first contract as `--fail-js`.
- **Mask dynamic regions** (`--mask x,y,w,h`, repeatable): zero a clock or
  spinner on both images so the numbers stay stable and citable.
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
