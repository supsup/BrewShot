# BrewShot — Release Notes ☕📸

## Unreleased

- **macOS crash-dialog spam eliminated** (`--no-startup-window` in the default launch
  args): on macOS 26 + Chrome 150, rapid headless launches sporadically abort in
  `TransformProcessType → _RegisterApplication` (LaunchServices refuses the app
  registration under launch storms) — usually a doomed secondary process while the
  capture still succeeds, but each abort queues a "Google Chrome quit unexpectedly"
  dialog on the operator's desktop, and under some conditions (cold `--no-daemon`
  suite runs) the serving process itself dies pre-DevTools. Reported by Charles;
  reproduced 5/15 storm launches without the flag, 0/15 with it, captures intact.
  Reaches embedders (LatteX/Sirentide suites) on their next jar re-vendor — the old
  vendored 0.2.0 jar has neither this flag nor the `BREWSHOT_CHROME_ARGS` env hook.

## 0.8.0

The page as a **print-fidelity PDF** — and, unlike GIF, it runs on the native binary.

- **PDF capture via `Page.printToPDF`.** `pdf(out)` / `pdf(out, PdfOptions)` render the
  whole document as a paged, print-fidelity PDF straight from CDP — base64 PDF bytes,
  **no ImageIO/AWT** — so it works on the macOS native binary, where GIF recording
  can't.
- **`PdfOptions` — a wither record with honest defaults.** `defaults()` is US Letter,
  portrait, zero margins, backgrounds on, scale 1.0 — "what the page looks like, as a
  print artifact," not the browser's print defaults (which drop backgrounds and add
  margins). An `a4()` preset plus withers `landscape` / `printBackground` / `scale` /
  `paper(widthIn, heightIn)` / `margin(inches)`; a bad envelope (non-positive paper,
  negative margin, scale outside CDP's 0.1–2.0) throws `IllegalArgumentException`
  loudly instead of an opaque Chrome reject.
- **CLI infers `.pdf` output** — case-insensitively, so `-o out.PDF` writes a real PDF
  rather than PNG bytes in a `.PDF` file. `brewshot URL -o page.pdf` routes to `pdf(out)`
  instead of a screenshot. The clip/scale flags are raster-only: combining any of
  `--clip-selector` / `--clip-js` / `--scale` / `--clip-padding` with a `.pdf` output is
  **refused loudly** (exit 2), never silently producing a full-page PDF — BrewShot output
  is review evidence, so a silently-wrong artifact fails closed.
- **Emulated media before capture.** `colorScheme("dark"|"light"|"no-preference")`,
  `media("print"|"screen")`, and `reducedMotion("reduce"|"no-preference")` force those media
  features via CDP `Emulation.setEmulatedMedia` — a dark-mode-only stylesheet, an
  `@media print` layout, or a `prefers-reduced-motion`-guarded animation now renders under
  the *intended* condition instead of whatever the OS happens to report. Chainable knobs
  (`navTimeout`/`commandTimeout` idiom) that apply immediately and are re-sent on every
  subsequent `open`/`html` on the same instance, so a second navigation never silently drops
  the override. CLI: `--color-scheme dark|light`, `--media print|screen`, `--reduced-motion`
  (boolean → `reduce`).

## 0.7.1

GIF quality and CI honesty, plus a version-string fix.

- **Stable global GIF palette — gradients stop flickering.** ImageIO's default GIF
  writer re-quantized each frame to its own 256-colour table, so a gradient landed on a
  shifting palette and flickered frame-to-frame. `GifWriter` now builds **one** shared
  palette by median cut over a pooled colour histogram and Floyd–Steinberg dithers every
  frame against that fixed `IndexColorModel`, so each frame's colour table is identical
  and gradients hold steady. Also fixes a `ClassCastException` in
  `recordGifFullPage`/`recordGifRegion`/`screenshotRegion` where a JSON-integer eval
  result was cast to `Double` instead of `Number`.
- **CI honesty — a required run must execute or fail, never skip.** The reference CI
  installs Chrome and sets `BREWSHOT_REQUIRE_CHROME=1`; an `afterSuite` guard turns any
  skip into a build failure, so a green build proves the browser suite actually ran
  ("green that tested nothing" can't pass). Every Chrome-driving test now gates through
  `TestChrome.requireChromeOrLoudSkip`, which prints an unmissable banner and records a
  JUnit skip locally while failing loud under `REQUIRE`.
- **Version string fixed.** `BrewShot.VERSION` had lagged at `0.6.0` while the build was
  `0.7.0`, so `--version` and the `--json` manifest under-reported provenance; both are
  now single-sourced at `0.7.1`.

## 0.7.0

Six features land together — capture gets **deterministic**, input gets **typed**,
GIFs get **compositor-paced**, and the harness gets **honest about Chrome**.

- **Deterministic settle.** `waitForNetworkIdle`, `waitForFontsReady`, `waitReady`,
  and a configurable per-call nav timeout replace blind `settle(ms)` guesses — shoot
  a page that has actually settled, not one mid-flight. In-flight tracking is keyed on
  live CDP `requestId`s, so a page with a redirecting resource reaches *true idle* in
  ~fetch-time instead of silently burning the whole timeout.
- **Visual diff → a citable verdict.** `brewshot diff` turns a pixel comparison into a
  threshold-gated verdict with a JSON sidecar (the machine artifact, written first and
  independently of the heatmap) and a *comparable-pixel* denominator, so masking a
  dynamic region can never dilute the gate.
- **Chrome discovery + JPEG + honest CI.** `findChrome` scans `$PATH` and the usual
  macOS / Linux / Windows locations (override with `BREWSHOT_CHROME`); capture now
  supports JPEG; and the test suite **loud-skips** when Chrome is absent instead of
  silently passing — a red CI names its cause.
- **Typed input primitives.** Mouse `click` / `hover` over CDP `Input.dispatch`, with
  below-fold targets scrolled into view before dispatch so a real click lands where you
  point.
- **Compositor-paced GIF streaming.** `recordGifStream` records via CDP
  `Page.startScreencast` — frames paced by the compositor, meaningfully denser than the
  poll recorder for smooth motion.
- **Lifecycle robustness.** A JVM shutdown hook kills descendant Chrome and removes the
  temp profile on abnormal exit (no orphaned processes, no leaked temp dirs); `GifWriter`
  fails loud on a bad frame instead of an opaque whole-GIF NPE.

Every feature ships with real-Chrome tests; the consolidated 0.7.0 suite is 73 green,
zero skipped. Prior versions (0.1.0–0.6.0) are in the git history.
