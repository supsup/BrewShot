# Watched PNG and GIF jobs

The container's `watch` worker accepts three complete, visible, regular files
directly inside `/brewshot/input`. Publish with a hidden sibling and an atomic
rename; do not write directly into a visible job while it is being consumed.

| Drop | Meaning | Successful output |
| --- | --- | --- |
| `page.html` or `page.htm` | Existing self-contained HTML still | `page.html.png` |
| `capture.brewshot.json` | Website URL plus capture settings | `capture.brewshot.json.gif` or `.png` |
| `capture.brewshot.zip` | Root `job.json`, local HTML and relative assets | `capture.brewshot.zip.gif` or `.png` |

Other JSON/ZIP names are ignored. Outputs go to `/brewshot/output`. The
original submitted file moves to `input/finished`; failure produces a bounded
`.error.txt` and archives the input under `input/failed`. Existing outputs and
archives are never replaced. The whole ZIP is one claimed job, so its assets
cannot arrive after capture has started. Each worker processes jobs serially.

Manifest jobs retain the selected complete capture beneath
`output/.brewshot-state/<job-id>/<source-sha256>.decision`. A success decision
is the complete image; a failure decision is a bounded diagnostic. This is durable
recovery state: competing attempts honor the same first terminal result, and a
restart after publication does not record a changing page again. The public
image is a hard link, so its bytes are not duplicated. Keep this state with the
job archives; remove it only during deliberate offline retention maintenance
with all workers stopped. Ordinary top-level output names remain unchanged.

## A website job

Save this as `capture.brewshot.json`, substituting a website you trust:

```json
{
  "version": 1,
  "source": {"url": "https://example.com/animation"},
  "capture": {
    "format": "gif",
    "frames": 40,
    "delayMs": 100,
    "viewport": {"width": 1380, "height": 920},
    "dpr": 1,
    "settleMs": 500,
    "selector": ".stage",
    "scale": 0.85
  }
}
```

Remove `selector` to film the full page. Set `format` to `png` and remove
`frames` and `delayMs` for a still image. A website needs no ZIP.

The URL is fetched **from inside the container**. `localhost` names that
container, not the host. On Docker Desktop, use an explicit host address such
as `http://host.docker.internal:8080/...` for a host service. On Linux configure
a reachable hostname/network explicitly. BrewShot does not rewrite URLs.

## A local bundle

The ZIP root contains `job.json`, an HTML entry and any assets it references:

```text
job.json
index.html
style.css
animation.js
images/button.png
prepare.js                 # optional
```

Use the same manifest with `"source": {"file": "index.html"}`. A bundle may
also set the root field `"prepareScript": "prepare.js"` to run a local script
after page load and settling, immediately before the first frame. This is
JavaScript in the page, with Main's existing eval semantics; it is not a host
command. An asynchronous preparation should return a promise. Keep preparation
bounded: the entire job shares one deadline.

For v1, the entry is opened through **`file://`**, exactly as Main opens local
HTML. Relative CSS, images and ordinary script references are supported. Chrome
restricts local-file `fetch()`/XHR and ES module loading; a ZIP does not create
an HTTP origin. For an app needing HTTP semantics, serve it yourself and submit
a URL manifest. External resources still depend on the container's network.

Create a normal single-disk ZIP containing the directory's contents, with
`job.json` at its root. ZIP64, encrypted archives, links/special files, duplicate
entries and unsafe paths are rejected. Stage the complete package before the
visible rename:

```sh
cp capture.brewshot.zip Input/.capture.upload
mv Input/.capture.upload Input/capture.brewshot.zip
```

## Version 1 rules

Unknown fields, duplicate JSON keys, wrong JSON types and unsupported versions
are rejected. Loose JSON accepts a URL source; ZIP accepts a local file source.
There is exactly one source. No output pathname or free-form CLI arguments are
accepted from a manifest. `format` is required; GIF also requires `frames`.

| Manifest field | Main CLI mapping |
| --- | --- |
| `source.url` / `source.file` | Positional URL / extracted local HTML path |
| `capture.format = "gif"`, `capture.frames` | `--gif N`, GIF output extension |
| `capture.format = "png"` | PNG output extension |
| `capture.delayMs` | `--gif-delay` (GIF only) |
| `capture.viewport.width`, `height` | `--size WxH` (both required together) |
| `capture.dpr` | `--dpr` |
| `capture.settleMs` | `--settle` |
| `capture.selector` | GIF: `--gif-element`; PNG: `--clip-selector` |
| `capture.scale` | `--scale` |
| `prepareScript` | `--eval-file` (bundle only) |

The manifest checks its schema, then delegates numeric ranges and capture
options to Main's own parser and validators. Defaults are Main defaults.
`scale` applies to the page or selector capture using the existing recorder.
Navigation happens first, followed by settling, preparation, then capture.
Requested GIF delay controls capture cadence; encoded playback delay rounds to
centiseconds (nearest 10 ms, half up, minimum 20 ms). A 40-frame, 100 ms recording
has four seconds of encoded playback; elapsed job time also includes navigation,
settling, encoding and cleanup.

## Limits and failures

The worker bounds each JSON/ZIP job to 120 seconds, including subprocess
preparation, navigation and capture. `BREWSHOT_WATCH_JOB_TIMEOUT_MS` configures
1000 through 600000 ms; termination can take up to 15 additional seconds for
bounded shutdown. A deadline failure publishes no success image. Main's
existing GIF frame/decoded-image budgets continue to apply. Archive input is
limited to 16 MiB, root JSON to 64 KiB, ZIP entries to 256, one expanded entry
to 16 MiB, and all expanded entries to 64 MiB.

The worker publishes a fully written hidden image with the existing atomic,
no-replacement mechanism. A completed capture reporting exit 6 still publishes
its image and records its surviving-Chrome warning and counter. A timeout is
a failure and is **not** proof that Chrome resources were released. No unrelated
browser/service is stopped. Original job bytes remain available for diagnosis
or resubmission in the terminal archive.

The input folder is a trusted-operator interface. See
[SECURITY.md](../SECURITY.md) for URL reach and executable-content boundaries.
