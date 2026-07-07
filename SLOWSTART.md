# BrewShot — SLOWSTART

Scenario walkthroughs. Each one is a person with a page and a problem;
QUICKSTART has the bare API, this is how it composes.

---

## 1. Priya — "my JUnit suite can't see"

Priya maintains a Java library that *generates* HTML reports. Her tests assert
substrings (`assertTrue(html.contains("<table"))`) — which pass even when the
rendered table is 40,000px wide and unreadable. She wants "it actually renders
sanely" as a build gate, without adopting a browser-automation stack.

```java
@Test
void reportRendersSanely() throws Exception {
    assumeTrue(BrewShot.available(), "no local Chrome; skipping");
    String html = new ReportGenerator().render(sampleData());   // her own code

    try (BrewShot shot = BrewShot.launch(1200, 900)) {
        shot.html(html);                       // direct source — no temp file
        shot.settle(300);

        Object audit = shot.eval("""
            (function () {
              var bad = [];
              document.querySelectorAll('table, img, svg').forEach(function (el, i) {
                var r = el.getBoundingClientRect();
                if (r.width > innerWidth * 1.5) { bad.push(el.tagName + '#' + i); }
              });
              return bad;
            })()""");
        assertEquals(List.of(), audit, "elements wider than the viewport");

        shot.screenshot(Path.of("build/report-reference.png")); // eyeball artifact
    }
}
```

The `assumeTrue` gate means her CI containers without Chrome skip cleanly; her
laptop and the one CI job with Chrome enforce for everyone.

**The habit:** render → `eval` an audit that returns `[]` when healthy →
assert empty → screenshot for humans. The audit is the assertion; the PNG is
the receipt.

---

## 2. Marco — "the bug only exists on hover"

Marco ships a page-side animation runtime (this is the LatteX case study —
BrewShot was extracted from exactly this problem). His bug class: hover/enter
effects that mis-transform elements, leak timers, or die silently. None of it
is visible in HTML source; all of it is visible in a real render over time.

Trigger the state, then look:

```java
try (BrewShot shot = BrewShot.launch(1200, 900)) {
    shot.open(Path.of("examples/effects.html").toUri().toString());
    shot.settle(1500);

    // fire the hover through the element's real listener path
    shot.eval("""
        document.querySelector('[data-fx-hover="glitch"]')
          .dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}))""");

    // film the card while the animation runs
    Object r = shot.eval("""
        (function () {
          var el = document.querySelector('[data-fx-hover="glitch"]').closest('figure');
          var b = el.getBoundingClientRect();
          return {x: b.left + pageXOffset, y: b.top + pageYOffset, w: b.width, h: b.height};
        })()""");
    shot.recordGif((Double) MiniJson.get(r,"x"), (Double) MiniJson.get(r,"y"),
        (Double) MiniJson.get(r,"w"), (Double) MiniJson.get(r,"h"),
        14, 110, Path.of("examples/fx-hover-glitch.gif"));
}
```

Two assertions made this a *test* rather than a demo in LatteX:

- **Liveness** — capture N frames; if frame 0 equals every later frame, the
  trigger is dead → fail. (Caught real wiring bugs.)
- **Geometry sanity mid-animation** — while effects run, no element's box may
  exceed 2× its container. This mechanically pinned a bug class ("the blob")
  that had twice shipped past a green pure-Java suite and been caught only by
  a human eyeballing the page.

And the lifecycle bugs — "does the overlay actually get removed when the user
scrolls?" — became one `eval` (`scrollTo`) plus one assertion on
`document.querySelectorAll('[data-fx-overlay]').length`.

**The habit:** the browser state you can't unit-test is exactly the state
`eval` can create and interrogate. GIFs make the invisible reviewable.

---

## 3. Sam — "I want screenshots in my pipeline, not a framework"

Sam has a docs site bake and wants a rendered PNG of each page as a build
artifact — drift review by eyeball, before/after images in PRs. He does not
want npm in his Java pipeline.

CLI, today:

```
./gradlew jar
java -jar build/libs/brewshot-0.1.0.jar https://docs.internal/page -o page.png --settle 1200
```

Native binary (GraalVM JDK selected):

```
./gradlew nativeImage
build/brewshot https://docs.internal/page -o page.png     # instant startup, no JVM
```

> [!NOTE]
> The native binary is a **build artifact** — it won't have CLI flags added
> since you last built it (`--cookie`, `--wait-js`, etc. arrived in 0.3.0).
> After pulling or changing `Main.java`, re-run `./gradlew nativeImage`.
> `brewshot --version` prints what you've got; the jar always tracks source.

Or straight from a bake step that has the HTML in hand:

```
render-docs | build/brewshot - -o preview.png
```

**The caveat Sam should know:** the PNG/eval path compiles native cleanly (pure
JDK net + process + regex, no reflection). GIF recording rides ImageIO/AWT,
which GraalVM native-image doesn't yet support on macOS — so GIFs are
JVM/library-mode for now. If you need native GIFs, that's a planned
hand-rolled encoder away (GIF89a is a famously simple format).

---

## 4. An agent — "I need eyes"

An AI agent maintaining a web app can read every line of a template and still
not know what the page *looks like*. BrewShot is the agent's camera: screenshot
a live route, compare against the reference beside the template, see the
regression a human would have caught on sight.

```java
try (BrewShot shot = BrewShot.launch(1280, 2000)) {
    shot.open("http://localhost:8080/docs/some-page.html");
    shot.settle(1000);
    shot.screenshot(Path.of("route-now.png"));      // agent reads the image back
}
```

This use case is why the API stays boring: `open`/`html` → `eval` → `screenshot`
covers everything an agent needs to *see*, and nothing it doesn't.

---

## 5. Ana — "it has to run in a container"

Ana's CI runners are containers; there is no Chrome on them and she can't
install one per-job. She wants screenshots (and GIFs — which, on a JVM, work
on every platform) as pipeline artifacts.

**The easy path — the provided image.** The repo ships a `Dockerfile`
(jar-on-JVM + Chromium + fonts, fully self-contained):

```
docker build -t brewshot .
docker run --rm -v "$PWD:/work" brewshot https://ci.internal/report -o /work/report.png
cat page.html | docker run --rm -i -v "$PWD:/work" brewshot - -o /work/page.png
```

**Rolling your own image instead?** Three things a container needs that a
laptop already has:

1. **A browser** — `chromium` from the distro (`apt-get install chromium`);
   point `BREWSHOT_CHROME=/usr/bin/chromium`.
2. **Fonts** — a bare container renders tofu boxes. `fonts-liberation
   fonts-dejavu-core` covers text; add `fonts-noto-color-emoji` if your pages
   use emoji.
3. **Sandbox flags** — Chrome's sandbox needs privileges containers don't
   grant by default, so it exits immediately. Set
   `BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"`
   (BrewShot appends these to the launch; `--disable-dev-shm-usage` avoids
   the tiny default `/dev/shm` crashing renders of large pages).

**Mounts — how files get in and out.** The container's filesystem vanishes
with `--rm`, so the `-v "$PWD:/work"` in every example is doing real work: it
mounts your current directory INTO the container at `/work` (the image's
default working directory). Forget it and the PNG is written inside the
container and gone before you can look at it. The same mount moves files BOTH
ways:

```
docker run --rm -v "$PWD:/work" brewshot /work/report.html -o /work/report.png
#                 ^ input read from your dir      ^ output lands in your dir
```

Mount any host path you like (`-v /ci/artifacts:/work`); stdin (`-`) needs no
mount for input, only for output.

> [!WARNING]
> **Linux hosts: `Permission denied` on the output is a real trap.** The
> image deliberately runs as a **non-root user** (a security choice — see the
> Dockerfile), so the mounted directory must be writable by that user. If
> your shot dies with `Permission denied`, run with your own uid:
>
> ```
> docker run --user "$(id -u)" --rm -v "$PWD:/work" brewshot … -o /work/page.png
> ```
>
> macOS and Windows Docker Desktop map this automatically — which is exactly
> why it works on your laptop and then bites in Linux CI.

**What Ana should know:** container renders use the Linux font stack, so
pixels differ subtly from Mac/Windows-Chrome renders — keep your reference
images consistently from one environment (CI is the good choice: it's the
one everyone shares). And inside the container the *jar* runs GIFs happily —
the macOS-native-binary caveat doesn't exist here.

---

## The design in one paragraph

BrewShot is deliberately ~700 lines: launch local Chrome headless with a
DevTools port, speak six CDP messages over the JDK's own WebSocket, hand back
bytes and JSON values. No selector engine (use `eval`), no auto-waiting (use
`settle`/`eval` polling), no browser downloads (use the Chrome you have), no
dependencies (the JDK has everything). When you outgrow it, you know exactly
what you're outgrowing — and until then, nothing in your build got heavier.
