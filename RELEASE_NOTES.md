# BrewShot — Release Notes ☕📸

## 0.9.0

CLI GIF parity — the recorder family finally reachable without writing Java.

- **`--gif N` records a looping GIF from the CLI** (plan 6cc2d9ec, roadmap B4): the
  whole `recordGif*` family was library-only, so `java -jar` users had GIFs in the
  engine and zero access from the shell. `--gif N` flips the shoot to a recording
  (full page by default), `--gif-delay MS` sets the per-frame cadence (capture ==
  playback, default 40), and `--gif-element CSS` films just that element's box —
  resolved once, exit 1 loud if nothing matches (the `--clip-selector` posture).
  `--scale` composes as a true re-raster; setup flags (`--settle`, `--wait-js`,
  `--eval`, cookies/headers/emulated media) all run before recording, so you can
  trigger an animation and film it.
- **Loud refusals, honest output** (the `.pdf` lane's discipline): default `-o`
  becomes `brewshot.gif`; an explicit non-`.gif` `-o` is refused (exit 2) rather
  than writing GIF bytes under a misnamed extension — `isGifOutput` matches
  case-insensitively, same rule as the PDF dispatch. Still-shot-only flags
  (`--clip-selector`/`--clip-js`/`--clip-padding`) and `--gif-element`/`--gif-delay`
  without `--gif` are usage errors, and `--gif 0` refuses instead of silently
  degrading to a still. The guard is symmetric: a STILL shoot with a `.gif` output
  (`-o demo.gif`, no `--gif`) is refused too — previously it wrote PNG bytes into a
  `.gif` with exit 0 (found in review, live-repro'd).
- **The native-binary gap stays documented AND enforced**: GIF assembly rides
  ImageIO/AWT (unsupported under native-image on macOS) — on the native binary the
  `--gif` lane reports exactly that, loudly, instead of a stack trace; the jar path
  (`java -jar brewshot.jar`) records as always.

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
