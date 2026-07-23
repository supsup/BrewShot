# BrewShot deep code audit

- **Auditor:** Marlow (`openai:marlow`)
- **Date:** 2026-07-23
- **Repository:** `supsup/BrewShot`
- **Audited baseline:** `main` / `origin/main` at `01222a796a8063c0d29bc3a918fd3e9656d72630`
- **Audit branch:** `marlow/brewshot-deep-code-audit-2026-07-23`
- **Plan:** `71bc723f-5d1d-4593-9bc6-fc0ea92efd50`
**Scope:** production Java, CLI and library contracts, tests, build/release configuration,
container definition, security claims, documentation, recent history, and active overlapping work.

This branch changes documentation only. No production source, GitHub issue, pull request, remote
branch, deployment, or external artifact was changed during the audit.

## Executive assessment

BrewShot has an unusually legible core for a browser-driving utility: five production Java files,
zero runtime dependencies, a small hand-rolled JSON value model, loud Chrome-test skip protection,
and good error context around most CDP calls. The basic pure-Java behavior is healthy: 56 selected
browser-free tests passed, production sources compiled cleanly under `javac --release 21
-Xlint:all`, and the GraalVM native binary built and reported the expected `0.9.0` version.

The material risks are concentrated at three boundaries:

1. **Untrusted or simply very large data crossing from Chrome into Java is not actually bounded.**
   The WebSocket queue, partial-message accumulator, individual console entry, screenshot result,
   and decoded GIF working set can all grow independently of the advertised bounds.
2. **The visual-diff gate can return a false green.** An out-of-range tolerance of 256 suppresses
   every possible channel difference, while the default anti-aliasing heuristic suppresses a real
   one-pixel layout translation. Both cases returned exit code 0 under a zero-pixel gate in isolated
   probes.
3. **Artifact ownership and liveness are weaker than the CLI contract suggests.** Diff sidecars can
   overwrite an input and still exit 0, `.jpg` output is PNG on the ordinary CLI path, and the
   advertised per-command timeout begins only after an unbounded WebSocket send completes.

I found no Java-side code-execution primitive, unsafe object deserialization, reflection gadget,
or credential-store access. BrewShot's stated trusted-page threat model materially lowers the
likelihood of deliberate resource attacks, but it does not remove the availability and false-green
risks for broken dashboards, accidental huge captures, or unattended CI.

**Recommended disposition:** keep using BrewShot for controlled captures, but do not treat the
default diff verdict as a release-blocking oracle or the current recording byte budget as a heap
safety guarantee until F-01 through F-05 are addressed. The first remediation slice can be small:
validate diff options and output paths, then replace compressed-byte accounting with decoded-pixel
accounting.

## Finding index

| ID | Severity | Finding | Primary consequence |
| --- | --- | --- | --- |
| F-01 | High | CDP ingress and retained console data are byte-unbounded | Java OOM / stalled harness |
| F-02 | High | The GIF “heap budget” measures compressed PNG bytes while retaining decoded frames | Severe heap undercount / OOM |
| F-03 | High | Diff tolerance is not constrained to the channel range | Completely different images can pass |
| F-04 | High | Default anti-alias forgiveness hides real one-pixel layout moves | Visual-regression false green |
| F-05 | High | Output aliases can overwrite inputs and still report success | Evidence destruction / false success |
| F-06 | Medium-high | Command timeout excludes WebSocket send; connect and close are unbounded | Calls and shutdown can hang |
| F-07 | Medium-high | Diff and heatmap allocate full-image state twice | High UHD/8K heap demand |
| F-08 | Medium-high | GIF dithering has an unbounded cache and up to 256 comparisons per new color | CPU and heap spikes |
| F-09 | Medium | `waitReady()` calls a best-effort wait but is documented as deterministic | Premature, flaky capture |
| F-10 | Medium | Numeric and structural validation is inconsistent | Invalid CDP JSON / late opaque failures |
| F-11 | Medium | Raster format inference and writes do not preserve the artifact contract | PNG under `.jpg`; partial files |
| F-12 | Medium | The machine manifest stringifies typed evaluation values | Lost types / awkward automation |
| F-13 | Medium | Pure image tests are not forced headless or separated from browser tests | macOS JVM abort / weak test lanes |
| F-14 | Medium | Runtime-support and reproducibility claims exceed the gates | Undetected JDK/container drift |
| F-15 | Medium | A 1,688-line client and cloned recorder paths amplify maintenance misses | Regression-prone changes |
| F-16 | Low-medium | Thread confinement and mutable API structures are not enforced | Races / post-verdict mutation |
| F-17 | Low-medium | GIF millisecond delays are silently floored to centiseconds | Playback faster than requested |

## Method and evidence

### Source room

The audit reconciled these sources before conclusions were written:

- `main` and `origin/main`, both at `01222a7`;
- production code, all tests, `README.md`, `SECURITY.md`, `AGENTS.md`,
  `build.gradle.kts`, `.github/workflows/ci.yml`, and `Dockerfile`;
- recent resource-bound and recorder history, especially `9091dea`, `c5ac582`,
  `29c76b0`, and merge `e54d743`;
- the active descendant-cleanup plan `735951a2-6505-4af6-ab6a-915045bfa092` and
  branch `confluence/bootstrap-descendant-cleanup` through `4e3f804`;
- current GitHub state via read-only `gh` calls: no open pull requests and no open issues;
- Coordination Room project history through the audit start advisory
  `PROJECT/brewshot/143/b34a5536-6698-488b-91a7-c0872d2fde7e`;
- BrewShot's project playbook record. It currently contains only a placeholder, so repository
  documentation and Coordination Room ownership were the operative project guidance.

The active Confluence branch already adds an operator Chrome kill switch, descendant-aware process
teardown, an orphan sweep keyed by profile path, and dummy-process teardown tests. This report does
not re-file failed-bootstrap descendant cleanup as a new finding. That work still needs its own
review; it does not address the AWT headless-test failure in F-13.

### Verification performed

No real Chrome process or browser test was launched. This was deliberate: the launcher owns the
browser substrate, the user had recently observed repeated Chrome errors, and the active teardown
plan explicitly limits real-Chrome validation.

| Check | Result |
| --- | --- |
| Repository sync | clean baseline; `main == origin/main == 01222a7` |
| Browser-free regression slice | **56 tests passed**, 0 failed, 0 skipped |
| Temporary isolated audit probes | **11 probes passed**, each asserting the observed edge behavior |
| Production compile lint | `javac --release 21 -Xlint:all`: clean |
| GraalVM native build | `nativeImage`: passed; binary reports `brewshot 0.9.0` |
| Jar smoke | `java -jar … --version`: `brewshot 0.9.0` |
| Native smoke | `build/brewshot --version`: `brewshot 0.9.0` |
| Browser / Docker execution | intentionally not run |

The temporary probe file was removed after evidence collection and is not part of the audit branch.
The final browser-free test slice ran after its removal.

### Quantitative receipts

| Measure | Receipt |
| --- | --- |
| Production size | 3,266 lines across 5 Java files |
| Largest class | `BrewShot.java`: 1,688 lines and 54 public declarations in `javap` |
| Other production files | `Main.java` 624; `GifWriter.java` 387; `BrewShotDiff.java` 370; `MiniJson.java` 197 |
| Declared baseline tests | 98 `@Test` methods |
| Current jar | 64,657 bytes |
| Current native binary | 36,373,160 bytes |
| Unbounded queue probe | 20,000 messages / 21,160,000 retained characters; remaining capacity 2,147,463,647 |
| Console byte probe | 60 entries retained 6,000,300 characters despite the 1,000-entry cap |
| GIF heap projection | 1,024² solid PNG: 3,129 compressed bytes vs 4,194,304 ARGB bytes (1,340×) |
| Default-budget projection | compressed budget admits 85,789 such frames; decoded pixel payload would be 359,825,145,856 bytes |
| Timeout probe | configured 25 ms; blocked `sendText().join()` returned only after 157 ms |
| Network-idle probe | returned normally after 37 ms with one request still in flight |
| Tolerance probe | black vs white at tolerance 256: 0 changed pixels; zero-pixel CLI gate exit 0 |
| AA probe | 1-pixel rectangle move: 0 changed, 40 “AA ignored”; zero-pixel CLI gate exit 0 |
| Alias probe | JSON sidecar replaced the baseline input; command exit 0 |
| Manifest probe | a typed map became Java `String` value `{ok=true}` |
| GIF delay probe | requested 75 ms; encoded 7 centiseconds = 70 ms |

The 359.8 GB GIF figure is a conservative decoded-pixel projection, not a claim that the audit
allocated that memory. It demonstrates that compressed bytes are not a safe proxy for the working
set; real allocator overhead, duplicate frame representations, palette state, and caches only
increase the gap.

## Detailed findings

### F-01 — CDP ingress and retained console data are byte-unbounded

**Severity: High (availability and contract integrity)**

`BrewShot.launch()` creates `new LinkedBlockingQueue<>()` without a capacity
(`BrewShot.java:385`). The WebSocket listener first appends arbitrary fragments to an unbounded
`StringBuilder`, then copies the complete message into that queue with `sink.add`
(`BrewShot.java:1660-1673`). Routing and dropping happen only later, on the caller's draining
thread (`BrewShot.java:489-500`, `522-554`).

The retained console lists are capped at 1,000 *entries*, not bytes
(`BrewShot.java:56`, `577-580`). A page can emit a small number of multi-megabyte strings, and the
raw CDP message plus parsed object plus retained formatted text can coexist. Network events are
enabled for every instance (`BrewShot.java:397-399`), so an event burst can also accumulate while a
slow screenshot command is outstanding.

An isolated listener probe queued 20,000 messages containing 21.16 million characters and still
reported `Integer.MAX_VALUE - 20,000` remaining slots. A console-routing probe retained 6,000,300
characters in only 60 entries.

This contradicts `SECURITY.md:17`, which calls CDP events bounded, and
`SECURITY.md:41-42`, which describes a thrown eval as the Java-side worst case. It is not a
Java-code-execution issue; it is a Java heap-exhaustion path. The trusted-page model lowers
malicious likelihood but not accidental exposure from log loops, very large eval results, or
broken network instrumentation.

**Recommendation**

- Put a byte and message ceiling on the listener, including the partial-frame buffer.
- Route responses directly to per-command futures and events through a separately bounded queue;
  do not make every response wait behind unrelated events.
- Bound console/error retention by UTF-16 character or encoded-byte budget in addition to count,
  append one truncation marker, and expose dropped counts.
- Bound eval/manifest strings and decoded screenshot/PDF payloads, or make maximums explicit
  configuration with loud overflow errors.
- Correct the security document after the behavior and tests agree.

### F-02 — The GIF “heap budget” measures compressed PNG bytes while retaining decoded frames

**Severity: High (availability)**

`FrameBudget` sums `byte[].length` for the compressed PNG frames
(`BrewShot.java:164-195`). `GifWriter.write()` then retains the original PNG list and decodes
*every* frame into a second `List<BufferedImage>` before palette construction and encoding
(`GifWriter.java:59-86`). `BrewShot.gif(List<byte[]>, …)` calls the writer directly and bypasses
`FrameBudget` entirely (`BrewShot.java:1600-1606`).

The probe's 1,024×1,024 solid image compressed to 3,129 bytes but requires at least 4,194,304 bytes
as an ARGB pixel array, a 1,340× ratio. At the default 256 MiB compressed-byte budget, that
particular ratio projects to 359.8 GB of decoded pixel payload. Actual working set also contains
the PNG arrays, `BufferedImage` objects, global histogram, indexed output, error rows, and color
cache.

The result is a false safety property: the recorder can remain “within heap budget” while the GIF
writer exhausts the heap. The first-frame-always-admitted rule also permits a single oversized
frame regardless of the configured budget (`BrewShot.java:169-194`).

**Recommendation**

- Budget decoded pixels before capture/decoding: checked `width × height × bytesPerPixel × frames`,
  plus a defined encoder allowance.
- Inspect dimensions with an `ImageReader` before full decode, reject overflow and excessive
  dimensions, and apply the same policy to the public static `gif` entry point.
- Prefer a streaming or bounded two-pass design: sample/spool for palette construction, then decode
  and encode one frame at a time.
- Return a structured truncation result instead of only stderr, so library callers cannot miss a
  shortened recording.
- Rename the current setting if it remains compressed-byte-only; it is not a heap budget.

### F-03 — Diff tolerance is not constrained to the channel range

**Severity: High (correctness / false green)**

`BrewShotDiff.Options` has no canonical validation (`BrewShotDiff.java:38-50`), and CLI
`posInt()` merely parses an integer (`Main.java:504-507`). The comparison is
`maxChannelDelta > tolerance` (`BrewShotDiff.java:137-147`). Since a channel delta cannot exceed
255, tolerance 256 suppresses every possible change.

The isolated probe compared opaque black with opaque white at tolerance 256. The library reported
zero changed pixels, and `brewshot diff … --tolerance 256 --fail-pixels 0` returned exit 0.

Negative tolerances have the opposite pathology: even identical-channel comparisons after the
fast equality check can make many non-identical pixels count under nonsensical semantics.

**Recommendation**

Validate `0 <= tolerance <= 255` in the `Options` canonical constructor, then let the CLI surface
that same exception as exit 2. Add boundary tests for -1, 0, 255, and 256 at both library and CLI
levels.

### F-04 — Default anti-alias forgiveness hides real one-pixel layout moves

**Severity: High (correctness / false green)**

The default CLI enables anti-alias forgiveness (`Main.java:331-344`). Its test is reciprocal color
presence within each pixel's 3×3 neighborhood (`BrewShotDiff.java:150-179`). That proves a color
moved locally, but it does not prove the movement was rasterizer noise. A genuine one-pixel
translation of an edge, icon, border, or layout box satisfies the same condition.

The probe moved a solid rectangle by one pixel. The default verdict was:

- 0 changed pixels;
- 40 anti-aliasing pixels ignored;
- `anyChange == false`;
- `--fail-pixels 0` exit 0.

This directly contradicts `BrewShotDiff.java:154-156`, which says a real one-pixel edit stays
counted. `README.md:250-254` says ignored pixels are printed, which is true, but the release gate
does not use that count. The same paragraph calls `--pixel-exact` byte-faithful, although that flag
only disables the AA heuristic; the default tolerance of 16 still applies unless the caller also
sets `--tolerance 0`.

**Recommendation**

- Replace the reciprocal-neighbor test with a more discriminating anti-alias classifier that
  considers luminance slope/edge context, or adopt a proven pixelmatch-style implementation.
- Treat ignored-AA count as gate-relevant through a separate threshold, or make forgiveness
  opt-in for blocking comparisons.
- Make `--pixel-exact` also imply tolerance 0, or rename/document it accurately.
- Preserve this rectangle-translation case as a regression discriminator.

### F-05 — Output aliases can overwrite inputs and still report success

**Severity: High (data integrity)**

Diff inputs are decoded first, then sidecars are written directly to their requested paths
(`Main.java:384-425`). There is no canonical-path preflight. A JSON sidecar may equal either input,
and JSON and heatmap outputs may equal each other. In the probe, `--json` pointed at the baseline
input: the input was replaced by verdict JSON and the command returned exit 0.

The ordinary screenshot lane has the same shape: the image is written, then `writeManifest()`
writes its file directly (`Main.java:271-305`, `479-501`). If `--json` aliases `-o`, a valid image
is replaced by JSON after `Files.size(out)` is read, and the command still follows its success path.

This is especially damaging because these paths hold review evidence. A typo can destroy a
baseline while producing a green command.

**Recommendation**

- Normalize/canonicalize all known paths before work starts.
- Require the two inputs, primary output, JSON sidecar, and heatmap to be pairwise distinct as
  applicable; reject aliases with exit 2 before decoding or launching Chrome.
- Write each artifact to a sibling temporary file, flush/close it, then atomically move it into
  place where supported.
- Add alias matrices for input/output and sidecar/sidecar combinations.

### F-06 — Command timeout excludes WebSocket send; connect and close are unbounded

**Severity: Medium-high (liveness)**

`command()` performs `ws.sendText(...).join()` and only then computes its timeout deadline
(`BrewShot.java:473-490`). A blocked send is therefore outside `commandTimeoutMs`.
`fireAndForget()` has the same unbounded join (`BrewShot.java:454-468`).

The probe configured a 25 ms command timeout against a fake WebSocket whose send future did not
complete. The call remained blocked until the probe released it at 157 ms. The timeout never had a
chance to run.

The initial WebSocket connection uses `buildAsync(...).join()` without a connect bound
(`BrewShot.java:385-388`), and `close()` begins with unbounded `sendClose(...).join()`
(`BrewShot.java:1608-1619`). A wedged peer can therefore stall both startup and teardown.

**Recommendation**

- Start one monotonic deadline before send, and spend that same remaining budget across send and
  response wait.
- Use `orTimeout`/bounded `get` for WebSocket connect and close; abort the socket/process when close
  exceeds its small grace period.
- Use `System.nanoTime()` for elapsed deadlines so wall-clock adjustments cannot extend or shorten
  calls.
- Add blocked-send, blocked-connect, and blocked-close fake-WebSocket tests.

### F-07 — Diff and heatmap allocate full-image state twice

**Severity: Medium-high (performance and availability)**

For equal-sized inputs, `diff()` materializes two ARGB arrays, a mask, a changed bitmap, and a
visited bitmap (`BrewShotDiff.java:83-101`, `182-188`): approximately 11 bytes per pixel before
decoded input images and queue boxing. A UHD image therefore needs about 87 MiB of temporary diff
arrays alone.

When `--diff-out` is requested, `heatmap()` repeats both `getRGB` copies, rebuilds the full mask,
reclassifies every pixel, allocates another full pixel array, and creates an output image
(`BrewShotDiff.java:281-316`). There is no dimension or total-pixel ceiling. At 8K, active state is
comfortably in the hundreds of MiB; if the first pass has not been collected before heatmap
allocation, the process can cross a GiB.

The duplicated classification is also a correctness risk: future changes can make the numerical
verdict and heatmap disagree.

**Recommendation**

- Produce one immutable analysis result containing a compact `BitSet` change/mask map and reuse it
  for verdict, clusters, JSON, and heatmap.
- Avoid mask allocation when no masks exist; use primitive queues or destructive visitation rather
  than boxed `Integer` BFS.
- Enforce checked pixel/dimension limits before allocating arrays.
- Consider row-wise comparison when no connected-component/heatmap result is requested.

### F-08 — GIF dithering has an unbounded cache and up to 256 comparisons per new color

**Severity: Medium-high (performance and availability)**

Palette sampling is bounded, but the expensive conversion is not. For every pixel,
`ditherToPalette()` performs object-backed cache lookup, `BufferedImage.getRGB`, and
`WritableRaster.setSample` (`GifWriter.java:293-324`). Each new error-adjusted RGB value scans up
to all 256 palette entries (`GifWriter.java:336-350`). The per-frame `HashMap<Integer,Integer>`
can grow toward the RGB color space and has substantial boxing/node overhead.

`GifWriter.java:116-118` explicitly notes that the sample budget affects only palette selection;
every pixel is still dithered. High-entropy or gradient full-page recordings therefore combine the
decoded-frame retention in F-02 with worst-case CPU and cache growth.

**Recommendation**

Use a fixed-size quantized RGB lookup table (for example 5/6 bits per channel) or a bounded
nearest-color structure, operate on raster arrays rather than per-pixel accessor calls, and clear
or cap memoization deterministically. Benchmark flat, gradient, and noisy frames at realistic
full-page sizes, tracking peak heap as well as elapsed time.

### F-09 — `waitReady()` calls a best-effort wait but is documented as deterministic

**Severity: Medium (correctness and flakiness)**

`waitForNetworkIdle()` returns silently when its deadline expires and also returns on interruption
(`BrewShot.java:896-912`). `waitReady()` ignores which condition ended the wait and proceeds to
fonts (`BrewShot.java:923-932`).

That behavior matches the former method's “best-effort” Javadoc, but conflicts with
`waitReady()`'s “Deterministic readiness” claim and README guidance
(`README.md:50`, `293-294`). The probe returned normally after 37 ms while one request remained
in flight.

**Recommendation**

Return a typed result (`IDLE`, `TIMED_OUT`, `INTERRUPTED`) or throw a dedicated timeout from a
strict variant. Make `waitReady()` strict, and retain a clearly named `waitReadyBestEffort()` if
that convenience is useful. Include in-flight count and elapsed/quiet budgets in failure text.

### F-10 — Numeric and structural validation is inconsistent

**Severity: Medium (correctness and diagnostics)**

Several public boundaries accept values that cannot produce a valid request:

- `printPdfParams()` uses ordinary comparisons, so `NaN` scale and infinite paper/margins pass and
  are emitted as invalid JSON tokens (`BrewShot.java:1040-1061`). The probe confirmed both.
- `screenshotClip()` rejects non-finite dimensions but does not require positive width or height
  (`BrewShot.java:1106-1120`).
- most polling recorder overloads do not validate positive frame counts/delays before looping or
  sleeping (`BrewShot.java:1170-1185`, `1404-1417`, `1458-1505`);
  `recordGifStream()` does validate its envelope.
- `navTimeout`, `commandTimeout`, and `recordingHeapBudget` silently ignore non-positive settings
  instead of identifying caller error (`BrewShot.java:856-893`).
- CLI helpers named `posInt` and `posLong` only parse (`Main.java:504-511`), allowing negative
  viewport, settle, wait, tolerance, mask, and gate values to fail later or acquire odd semantics.
- `Options.masks()` arrays have no null, length, non-negative-size, or overflow validation
  (`BrewShotDiff.java:46-50`, `88-98`).
- CLI stdin and eval files are read completely with no configurable size limit
  (`Main.java:105`, `222-223`).

**Recommendation**

Define canonical constructors/value validators for PDF, clip, recorder, diff, and timeout options;
require finite values before range checks; and make CLI parsing reuse those validators. Reject
invalid values as usage errors before Chrome launch or image allocation.

### F-11 — Raster format inference and writes do not preserve the artifact contract

**Severity: Medium (artifact integrity)**

The ordinary CLI path calls `shot.screenshot(out)` regardless of extension (`Main.java:295`), and
that default is PNG. Yet CLI errors explicitly suggest `.jpg` as a valid raster output
(`Main.java:164-166`, `193-196`). A caller choosing `-o page.jpg` therefore receives PNG bytes under
a JPEG name. The library already has explicit JPEG support, but the CLI neither infers it nor
offers a quality option.

Screenshot, PDF, manifest, JSON, and heatmap writes are direct rather than temporary-and-rename
(`BrewShot.java:980`, `1078`; `Main.java:271-295`, `414-425`, `501`). Most failures can leave
partial or stale artifacts. `GifWriter` is better: it deletes a partial output on failure
(`GifWriter.java:94-101`), but it still does not atomically replace a prior valid file.

**Recommendation**

Infer a supported raster format case-insensitively or require `--format`; add JPEG quality to the
CLI; reject unknown/mismatched extensions; and centralize all writes behind one atomic artifact
writer with parent-directory and replacement policy.

### F-12 — The machine manifest stringifies typed evaluation values

**Severity: Medium (automation contract)**

`eval()` promises `String`, `Double`, `Boolean`, `Map`, `List`, or null (`README.md:49`), but
`writeManifest()` serializes every non-null result as an escaped string using
`String.valueOf(evalResult)` (`Main.java:479-501`). Numbers and booleans lose their JSON types;
maps/lists become Java display syntax rather than nested JSON.

The probe passed a map containing a boolean. Parsing the manifest yielded a Java `String` with
value `{ok=true}`, not a JSON object. This weakens the stated machine-readable contract and forces
automation to parse human formatting.

**Recommendation**

Add a small JSON writer for the same value domain `MiniJson` parses, preserve nested types, and
reject unsupported/non-finite values. Test scalar, list, nested map, escape, and null cases.

### F-13 — Pure image tests are not forced headless or separated from browser tests

**Severity: Medium (test reliability)**

The single Gradle `test` task mixes pure ImageIO/AWT tests and Chrome-driving tests and does not set
`java.awt.headless=true` (`build.gradle.kts:45-74`). During this audit, a selected browser-free run
aborted the test JVM with exit 134 before any Chrome could be involved. The macOS diagnostic
`java-2026-07-23-175853.ips` showed AppKit registration through
`JRSAppKitAWT`/`NSApplicationAWT`. The same tests passed with
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`.

The active Confluence branch's `BREWSHOT_FORBID_CHROME` switch is valuable for preventing an
accidental browser launch, but it does not force headless AWT or create a separately selectable
pure unit lane.

**Recommendation**

- Set `systemProperty("java.awt.headless", "true")` on image-capable test tasks.
- Tag or source-set browser tests separately (`unitTest` and `chromeTest`), with `test` aggregating
  both in CI.
- Make the no-Chrome lane assert the forbid switch so a test cannot silently launch a browser.
- Preserve a macOS browser-free CI or local gate if that execution mode is supported.

### F-14 — Runtime-support and reproducibility claims exceed the gates

**Severity: Medium (release confidence)**

`build.gradle.kts:19-20` says JDK 21+ is a tested promise, but both the toolchain and only CI job run
on JDK 25 (`build.gradle.kts:12-23`; `.github/workflows/ci.yml:15-43`). `--release 21` proves
bytecode/API targeting, not execution on a JDK 21 runtime.

The native build and Docker image are not CI gates. The Dockerfile calls the environment
reproducible while using mutable JDK base tags and unversioned Alpine packages
(`Dockerfile:1-4`, `11-21`). CI downloads the mutable Chrome stable `.deb` without a checksum, and
GitHub Actions are version-tag-pinned rather than commit-pinned
(`.github/workflows/ci.yml:25-39`).

Documentation metrics have also drifted: `README.md:304-316` reports a 17 KB jar and 32 MB native
binary, while this baseline produced 64,657 bytes and 36,373,160 bytes.

**Recommendation**

Add a JDK 21 runtime matrix lane, a native build/version smoke, and a Docker build plus
containerized version/unit smoke. Pin image digests and browser/package versions where
reproducibility is claimed, verify downloaded artifacts, and generate or date-stamp size metrics.

### F-15 — A 1,688-line client and cloned recorder paths amplify maintenance misses

**Severity: Medium (maintainability)**

`BrewShot.java` owns process launch/teardown, WebSocket transport, protocol correlation, event
routing, navigation, input, capture, PDF, recording, state, and cleanup across 1,688 lines and 54
public declarations. `Main.java` adds 624 lines of parsing, execution, diff dispatch, artifact
writes, and manual JSON.

Recorder families share a budget type but clone the loop and final-write pattern across rectangle,
element, scroll, full-page, region, and screencast paths
(`BrewShot.java:1170-1185`, `1404-1417`, `1430-1450`, `1458-1469`,
`1490-1505`, `1542-1595`). History shows the practical cost: the resource fix required follow-up
commits `c5ac582` and `29c76b0` to cover families missed in earlier passes.

Diff/heatmap repeat mask and classification logic (F-07), selector-to-JS escaping is repeated
(`BrewShot.java:1196`, `1268`), and output writing is scattered (F-11).

**Recommendation**

Extract narrowly scoped internal components without widening the public dependency surface:

1. process lifecycle/profile ownership;
2. CDP transport/response correlation and event router;
3. capture/recording pipeline with one frame-source loop;
4. shared diff analysis;
5. artifact transaction/JSON writer.

Move one behavior at a time under existing tests; avoid a large rewrite.

### F-16 — Thread confinement and mutable API structures are not enforced

**Severity: Low-medium (API robustness)**

Class Javadoc states one command in flight (`BrewShot.java:42-44`), but the invariant is not
enforced. Concurrent callers can race `nextId++`, consume each other's queue responses, route a
method-less response as discard, and leave the rightful caller timing out
(`BrewShot.java:454-500`). This matters if the `AGENTS.md` future MCP/pooling sketch is implemented
without strict one-borrower ownership.

`BrewShotDiff.Options` does not defensively copy the list or the mutable `int[]` masks, and
`Verdict.changedBounds()` exposes its mutable array (`BrewShotDiff.java:46-65`). Callers can mutate
configuration during analysis or alter a completed verdict before JSON serialization. Short/null
mask arrays fail as incidental NPE/AIOOBE rather than boundary errors.

Finally, selector interpolation is duplicated and only escapes backslash and apostrophe
(`BrewShot.java:1196`, `1268`); line terminators can produce broken JavaScript. This is primarily a
correctness issue under the trusted-operator model, not a claimed privilege boundary.

**Recommendation**

Enforce an owner thread or serialize command dispatch with response futures; make any pooling
contract explicit. Deep-copy/validate masks, return an immutable bounds value, and use one
JSON-string-literal helper for selector interpolation.

### F-17 — GIF millisecond delays are silently floored to centiseconds

**Severity: Low-medium (output fidelity)**

GIF stores delays in centiseconds, but `GifWriter` uses integer floor division:
`Math.max(2, delayMs / 10)` (`GifWriter.java:355-366`). A requested 75 ms becomes 70 ms, as the
probe confirmed; 25 ms becomes 20 ms. Values below 20 ms are silently raised to 20 ms.

The README examples say a frame is played at 75 ms and calculate FPS as
`1000 / playbackDelayMs` (`README.md:107-125`), so the artifact is systematically faster than the
documented request for non-multiples of ten.

**Recommendation**

Validate positive delays at the shared writer boundary, round to the nearest centisecond, expose or
log the effective delay when it differs, and document GIF's 10 ms granularity. Tests should inspect
encoded metadata for 1, 19, 20, 25, 75, and 76 ms.

## Positive findings and ruled-out concerns

- Production sources compiled with `--release 21 -Xlint:all` without warnings.
- There are no runtime library dependencies. The JDK `HttpClient` is shared statically, avoiding
  a per-launch selector-thread leak (`BrewShot.java:60-61`).
- `MiniJson` has a depth cap, fails malformed input closed, and maps only to ordinary value
  containers. I found no reflection, class loading, Java serialization, expression engine, or
  gadget surface.
- Chrome-death and CDP error messages preserve method context. A socket close injects a poison
  event so response waits can fail promptly once the listener observes closure.
- Network tracking correctly uses request IDs rather than a counter, avoiding redirect imbalance
  (`BrewShot.java:120-128`, `541-551`).
- Console/error list *counts* are bounded and snapshots are immutable, although F-01's byte issue
  remains.
- GIF encoding deletes a newly partial output when encoding fails and attributes undecodable frame
  errors by index (`GifWriter.java:59-101`).
- PDF and JPEG library paths validate several common envelope errors; PDF/GIF extension routing is
  case-insensitive and loud for known incompatible flag combinations.
- The provided container runs non-root and documents the risk of `--no-sandbox`.
- The shutdown hook and recent lifecycle work show good attention to child cleanup. The currently
  active descendant-cleanup branch is the right ownership path for the remaining failed-bootstrap
  process-tree issue.
- Native compilation and both CLI version paths succeeded on this baseline.
- No open GitHub issue or pull request conflicted with this audit at the time of review.

## Remediation sequence

### P0 — Prevent false green and destructive success

1. Validate diff tolerance and mask structure (F-03).
2. Add the one-pixel translation discriminator and change AA gate semantics (F-04).
3. Preflight pairwise-distinct artifact paths and write atomically (F-05).

These are small, independently testable changes with high correctness value and no Chrome
requirement.

### P1 — Make resource and timeout promises true

4. Bound CDP ingress and retained text by bytes; update the threat model (F-01).
5. Replace compressed-frame accounting with decoded-pixel/working-set accounting and stream the
   GIF pipeline (F-02).
6. Apply one deadline to send plus response, and bound connect/close (F-06).
7. Share diff classification and cap image dimensions/pixels (F-07).
8. Bound/replace the GIF nearest-color cache and benchmark it (F-08).

### P2 — Tighten contracts and maintainability

9. Split strict readiness from best effort (F-09).
10. Centralize finite/range validation (F-10).
11. Add explicit CLI image-format handling and a shared atomic artifact writer (F-11).
12. Preserve JSON types in manifests (F-12).
13. Split headless unit and Chrome integration lanes (F-13).
14. Add JDK 21/native/container gates and pin reproducible inputs (F-14).
15. Extract transport, recording, diff-analysis, and artifact components incrementally (F-15).
16. Harden concurrency/mutability boundaries and document GIF timing granularity (F-16, F-17).

## Suggested first implementation slices

The following slices minimize overlap and make review straightforward:

1. **`diff-options-and-alias-guard`** — F-03 and F-05 only; pure tests.
2. **`diff-aa-discriminator`** — F-04, including translation and font-raster fixtures; policy
   decision required on whether ignored AA can trip a gate.
3. **`cdp-ingress-budget`** — F-01 plus security-doc corrections; fake listener/transport tests.
4. **`decoded-recording-budget`** — F-02, with dimension preflight and static `gif` coverage.
5. **`transport-deadline`** — F-06 using fake WebSocket futures; no Chrome.
6. **`headless-test-lanes`** — F-13, coordinated with the active descendant-cleanup branch to avoid
   editing the same Gradle/test-gate surface twice.

The active `confluence/bootstrap-descendant-cleanup` work should remain separately owned and land
only after its existing review. None of the slices above should duplicate its process-tree changes.

## Final audit verdict

BrewShot's small dependency footprint and core protocol model are sound, and the audit did not find
a Java-side execution vulnerability. The current implementation is nevertheless too optimistic at
its data-size, visual-gate, and artifact boundaries for fully unattended evidence generation.

The highest-value changes are not a rewrite: four boundary checks, a stricter AA discriminator,
and honest decoded-memory accounting remove the most consequential false-green and OOM paths.
After those, transport deadlines and shared analysis/recording internals will make the existing
public surface substantially more reliable without sacrificing BrewShot's deliberately lean
design.
