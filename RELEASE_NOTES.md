# BrewShot — Release Notes ☕📸

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
