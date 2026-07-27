# BrewShot — Release Notes ☕📸

## Unreleased

- **Reparented-orphan sweep before profile release** (plan 735951a2; Marlow's
  report-only evidence, brewshot room 140): `ResourceLease` cleanup force-kills
  every process-tree member an enumeration ever OBSERVED, but a helper that was
  reparented BEFORE the first `descendants()` snapshot is invisible to every
  handle walk — the shape of a failed bootstrap, where the direct child exits
  ("Chrome exited without a DevTools listening line") before any walk runs while
  its helpers live on and recreate the generated `brewshot-*` profile dir.
  Cleanup now also force-kills any process still carrying this launch's unique
  `--user-data-dir=<temp>` path in its argv — a full-path match against a fresh
  `createTempDirectory` name, never a process name — and runs it BEFORE the
  delete. A still-live argv match is positive evidence that containment is not
  closed, so it now blocks profile release rather than letting the delete race a
  live writer; the fail-closed retention contract is strengthened, not relaxed.
  Chose argv matching over POSIX process groups: `ProcessBuilder` cannot
  `setsid`, so groups would cost a wrapper hop on every launch. Verified
  red-then-green against dummy `/bin/sh` process trees whose reparented child
  demonstrably recreated the dir without the sweep; NOT verified against a real
  failed Chrome bootstrap (browser launches are operator-forbidden while the
  crash-dialog investigation is live). The correlation between this leak class
  and the macOS crash-alert reports remains THEORY — the evidence in room 140
  shows a survivor recreating the profile dir, not that this ends the alerts.
- **`BREWSHOT_FORBID_CHROME=1` test-gate kill switch**: the Chrome-gated suites
  previously keyed only on browser availability, so a Chrome-having host could
  not run `./gradlew test` without launching Chrome. The operator switch makes
  gated suites loud-skip exactly like a browser-less host (banner + JUnit
  skip); `BREWSHOT_REQUIRE_CHROME`'s no-skip guard still fails a
  forbid+require run.
- **Real resource bounds on the CDP transport, captures, and recordings.** Advertised
  bounds are now enforced rather than assumed. The CDP inbox is a finite queue
  (`brewshot.maxInboxMessages`, default 4096) sized one slot beyond its cap so the
  close/error sentinel always has a home; oversized single messages are dropped during
  reassembly against their exact UTF-8 encoding (`brewshot.maxCdpMessageBytes`), summed
  incrementally across the API-guaranteed legal UTF-16 callback data. Undrained regular
  messages also share an exact cumulative UTF-8 budget (`brewshot.maxInboxBytes`, default
  32 MiB) whose
  capacity is returned on dequeue, so the count and per-message ceilings can no longer
  multiply into an implausible retained heap bound. `maxInboxMessages` now fails
  configuration above `Integer.MAX_VALUE - 1` before its reserved-slot addition can
  overflow. Console/error retention is clamped to a byte budget; screenshot captures are
  refused from their decoded size before anything is written; and GIF recording checks
  frame dimensions and a decoded working-set budget before decode. Every refusal and drop
  is **announced once and counted**, never silent.
  **Terminal transport state is now durable:** a nonblocking drain — reachable from
  `console()`, `errors()`, `freshNavigation()` and `waitForNetworkIdle()` — used to
  consume the close sentinel and leave later callers unable to learn the socket had died,
  so a command waited out its full timeout with Chrome still alive; the closed state is
  now latched and every subsequent command fails fast with a closed-socket reason.
  Tradeoffs, stated because they are real: the new defaults can refuse very large captures
  and recordings that previously succeeded (all are `-D` overridable), and a saturated
  inbox can drop a solicited response and surface it as a command timeout. These are
  bounds for the trusted-page model — not a defence against a hostile page.

- **Docker watched folders without sacrificing the CLI.** The Java 25 image
  still defaults to BrewShot's original argv/stdin/stdout CLI and now accepts
  `cli` as an explicit spelling plus a long-running `watch` mode over
  `/brewshot/input` and `/brewshot/output`; legacy relative CLI paths and the
  default `brewshot.png` output still resolve below `/work`. The fixed non-root `10001:10001`
  runtime has a writable Chromium home (including host-UID overrides) while
  application jars remain immutable under `/opt/brewshot`. Watch mode accepts
  only complete direct-child local `.html`/`.htm` files, atomically claims them,
  publishes complete PNGs without
  overwrite, and moves sources to `finished` or `failed`; diagnostics are
  bounded and content-free. Restart recovery, name/path bounds, output/archive
  collisions, unreadable inputs, readable foreign-owned mode-`0444` sources,
  and shared-worker races use atomic owner-agnostic moves plus stable physical
  file identity rather than same-name path existence. A cache-disabled Docker
  build and dedicated real-Chromium smoke pin old/explicit CLI parity, runtime
  ownership, success/failure/liveness, restart recovery, collision immutability,
  and multi-worker convergence. URL and advanced-option jobs remain on the
  one-shot CLI, avoiding a new autonomous URL/SSRF manifest surface.
- **macOS bootstrap is context-aware and multi-witness.** On macOS only, an
  inherited `CODEX_SANDBOX=seatbelt` context now refuses unified Chrome before
  creating a generated profile or starting a process, with a fixed actionable
  message. A caller may explicitly select `chrome-headless-shell` through
  `BREWSHOT_CHROME`; normal-Terminal and container launches are unchanged. In
  supported contexts BrewShot continuously drains bounded stdout and stderr and
  also validates the generated profile's `DevToolsActivePort`, using one
  monotonic deadline and requiring every observed endpoint witness to agree.
  The 100 ms cross-witness agreement window is honored even after both stream
  drains reach EOF, and adjacent startup flags cannot hide a credential value
  from diagnostic-tail redaction.
  Failures distinguish process exit, alive timeout, malformed witnesses, and
  disagreement while retaining only bounded sanitized stream tails. This is a
  narrow response to macOS 26.5.2 / Chrome 150 evidence, not a claim that every
  Chrome crash dialog has been eliminated; the existing `ResourceLease`
  ownership and fail-closed profile-retention contract is unchanged.
- **Contract validation is fail-loud and finite-first.** PDF paper/margins/scale,
  clipped screenshot geometry, recorder counts/delays, diff options/masks, CLI
  positive integers/longs, and public timeout/heap knobs now reject invalid
  inputs before protocol or file work. Documented zero-duration no-ops remain
  only on `settle(0)` and `waitForNetworkIdle(..., 0)`. Shared bounded UTF-8
  ingestion accepts stdin HTML through exactly 16 MiB and `--eval-file` through
  exactly 1 MiB, reading only one sentinel byte before an over-limit refusal.
- **Truthful, transactional artifacts.** Case-insensitive `.jpg`/`.jpeg` CLI
  stills use Chrome's JPEG encoder (`--jpeg-quality 1..100`, default 90),
  including clip/scale paths; unknown shoot extensions and non-PNG
  `--diff-out` names are refused.
  Screenshots, PDFs, GIFs, manifests, diff JSON, and heatmaps now write through
  sibling temporaries and move into place atomically when supported. Encoding
  or temporary-write failures preserve an existing completed target and clean
  temporary residue best-effort. The complete-temp fallback cannot promise
  atomic replacement on filesystems that reject `ATOMIC_MOVE`. Replacement
  retains existing POSIX mode bits and follows valid output symlinks to their
  referents; broken/cyclic links fail before temporary-file creation. CLI
  output paths are preflighted against sibling artifacts and diff baselines.
  Absent output identities use a fail-closed Unicode-normalized case fold, so
  case-only future aliases cannot overwrite one another on insensitive mounts.
- **Typed manifests.** `MiniJson` is now the zero-dependency serializer for the
  full supported JSON domain; manifest `eval` values remain null/boolean/number/
  string/array/object, while non-finite, cyclic, and unsupported values fail loud.
- **Immutable diff inputs/results and safe selectors.** `Options` deep-copies and
  validates mask shape/extents/overflow; `Verdict` owns and re-copies changed
  bounds. Tolerance is 0–254 and percentage gates are 0–100. One
  selector-literal helper escapes quotes, backslashes, CR/LF, U+2028, U+2029,
  and every UTF-16 surrogate code unit before interpolation or UTF-8 output.
- **Honest GIF timing.** Millisecond delays round to the nearest centisecond
  (75 ms → 80 ms) while retaining the 20 ms minimum.
  `BrewShot.effectiveGifDelayMs` and CLI manifest requested/encoded fields expose
  the effective value.

## 0.9.0

CLI GIF parity — the recorder family finally reachable without writing Java — plus
the first macOS crash-dialog mitigation.

- **macOS crash-dialog storm reduced** (`--no-startup-window` in the default launch
  args): on macOS 26 + Chrome 150, rapid headless launches sporadically abort in
  `TransformProcessType → _RegisterApplication` (LaunchServices refuses the app
  registration under launch storms) — usually a doomed secondary process while the
  capture still succeeds, but each abort queues a "Google Chrome quit unexpectedly"
  dialog on the operator's desktop, and under some conditions (cold `--no-daemon`
  suite runs) the serving process itself dies pre-DevTools. Reported by Charles;
  reproduced 5/15 storm launches without the flag, 0/15 with it, captures intact.
  That sample demonstrated a mitigation, not universal elimination: later
  macOS 26.5.2 / Chrome 150 evidence showed unified Chrome's serving process can
  still abort before DevTools in a LaunchServices-denied Codex Seatbelt context.
  The Unreleased context refusal and three-witness observer address that narrower
  failure without rewriting this historical result.
  Reaches embedders (LatteX/Sirentide suites) on their next jar re-vendor. Interim
  workaround is LINEAGE-SPECIFIC (correction credits: Marlow, brewshot/130 AND /132 —
  two claims here were wrong before this wording): LatteX **main** vendors 0.8.0,
  which honors `BREWSHOT_CHROME_ARGS=--no-startup-window` today; the frozen
  0.11.0-release lineage (the in-review seam-patch branch) still vendors **0.2.0**,
  which has no env hook — suite runs on that lineage cannot take the workaround and
  will keep spawning dialogs until the fixed jar exists.
- **Bounded, continuously-owned DevTools transport lifecycle.** Command timeouts now
  cover WebSocket send plus response on one monotonic deadline; connect is bounded
  both natively and at the Future boundary, and close has a timed abort fallback. A
  shutdown admission fence atomically joins Chrome start to its process/profile lease;
  retained pre-exit handles let cleanup terminate every helper it actually observed.
  A JDK `ProcessHandle` snapshot cannot prove that a helper created during teardown
  did not reparent after the final snapshot, however, so the zero-dependency launcher
  now conservatively retains the temp profile and its in-memory lease unless an
  external process-tree containment owner proves membership closed and fully reaped.
  Even then, deregistration requires `Files.notExists(..., NOFOLLOW_LINKS)` to
  positively establish that no entry remains at the profile pathname; provider
  uncertainty, probe failure, or a dangling symlink keeps the lease live and
  retryable. The lease remains retryable only while that JVM lives; JVM exit
  reclaims the registry but deliberately leaves an unproven profile on disk. This
  corrects the older “no leaked temp dirs” overclaim. One JVM-hook reconciliation
  loop shares a five-second
  process-wait/retry-admission budget; synchronous profile deletion and waiting for an
  already-admitted OS start remain outside it.
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
