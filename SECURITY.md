# Security model

Short version: **BrewShot hands your input to Chromium and reads back bytes and
JSON — it never interprets page content itself.** A malicious page is
Chromium's threat model, not BrewShot's. The real risks are the ordinary ones
that come with driving a browser, listed at the end.

## What actually happens to page content

BrewShot launches your local Chrome, speaks the DevTools Protocol over a
WebSocket, and consumes exactly three things back from the page:

| From the page | BrewShot does | Risk |
| --- | --- | --- |
| screenshot | `Base64.decode` → `Files.write` | opaque bytes, never decoded/parsed by us |
| `eval` result | `MiniJson.parse` → typed value | the one place page **data** enters Java — see below |
| CDP events | routed by method name; console/errors kept as text | bounded by entry count **and** a retained-byte ceiling; CDP ingress itself is byte-bounded; never executed |

The Java code and the page's HTML/JS **never touch**. There is no place where
page markup or scripts are interpreted, `eval`'d, deserialized into objects, or
reflected into class loading on the Java side. A `.html` crafted to compromise
the *Java process* would first have to break **Chromium's renderer sandbox** —
a browser-exploit-chain problem entirely inside Chrome's threat model.

### Resource bounds (memory)

A page BrewShot drives can push a lot of bytes back — a firehose of console
output, a huge `eval` result, a giant screenshot, a long recording. Those paths
are bounded so a chatty or hostile page degrades loudly instead of OOMing the
harness:

- **CDP ingress** is bounded on **two** axes. *Per message*
  (`brewshot.maxCdpMessageBytes`, default 32 MB): a message that would exceed the
  ceiling is dropped — its reassembly buffer released, never materialized as a
  giant `String`. *Cumulatively* (`brewshot.maxInboxMessages`, default 4096): the
  ingress queue holds at most this many undrained messages, so a page that emits a
  flood of individually-small messages while the command thread is busy can no
  longer grow the inbox without bound — the newest messages are dropped once the
  cap is reached. One queue slot is reserved for the socket close/error signal, so
  that poison is never lost to a full inbox (a stalled caller still fails fast).
  Both drops are announced once and counted, never silent.
- **Console/error retention** is bounded on **two** axes: entry count (1000)
  **and** a retained-byte budget (`brewshot.maxConsoleBytes`, default 1 MB), so
  a single multi-MB console entry can no longer be kept whole. Over-budget
  entries are truncated/dropped and the dropped count is exposed.
- **Screenshot capture** is refused, header-only and before any full-pixel
  allocation, above `brewshot.maxImageDimension` (16384 px/axis) or
  `brewshot.maxImagePixels` (64 MP).
- **GIF assembly** is refused, before decode, above `brewshot.gif.maxFrames`
  (1000), `brewshot.gif.maxFrameDimension` (4096 px/axis), or
  `brewshot.gif.maxDecodedBytes` (512 MB of decoded working set = Σ w·h·4).

All limits are `-D` overridable and enforced BEFORE the large allocation, and a
breach is loud (a thrown error or an announced+counted drop) — never a silent
truncation.

### The one ingestion point: `MiniJson`

`eval` results arrive as JSON and are parsed by the hand-rolled `MiniJson`.
This is the only path where attacker-influenced *data* reaches Java, so it is
hardened accordingly:

- **Depth-capped** (`MAX_DEPTH = 200`): a pathologically nested value fails the
  single `eval` call with a clear `IllegalArgumentException`, never a
  `StackOverflowError` that takes down the harness.
- **Malformed input fails closed**: truncated strings, bad `\u` escapes,
  trailing garbage → a caught `IllegalArgumentException`, not undefined
  behaviour. (Pinned by `MiniJsonTest`.)
- **No object mapping**: it produces only `Map`/`List`/`String`/`Double`/
  `Boolean`/`null` — no reflection, no type coercion, no gadget surface. There
  is nothing for a crafted payload to instantiate.

Worst case from a hostile `eval` result today: your `eval`/`clip-js` call
throws. No code execution, no process compromise.

## The real risks (they are about what you point it at, not the parser)

These are inherent to any headless-browser tool. BrewShot is a **test/CI/agent
harness for TRUSTED pages driven by a trusted operator** — calibrate to that.

1. **`eval` / `--fail-js` / `--clip-js` run JavaScript you supply, in the page's
   context.** That is the feature. But if *you* build that JS string by
   concatenating untrusted input, you have injected into your own probe. Keep
   `eval` expressions static, or escape anything interpolated.

2. **SSRF-style reach.** Point BrewShot at a URL and *your machine/network*
   fetches it — same as `curl` or any headless browser. Safe for the intended
   `localhost`/your-own-pages use; if a target URL could ever come from an
   untrusted source, treat it like any server-side fetch (allowlist hosts,
   run it network-isolated).

3. **`--no-sandbox` in containers.** The provided Docker image sets
   `--no-sandbox` because Chrome's sandbox needs privileges containers don't
   grant by default. That removes the layer that would contain a malicious
   renderer, so **only use the sandboxless container to render pages you
   trust** — never point a sandboxless browser at untrusted URLs. (The image
   also runs as a non-root user, which limits the blast radius if the renderer
   is compromised.)

4. **`header()` is not host-scoped.** An extra header (e.g. `Authorization`) is
   sent on *every* request the page makes, including cross-origin subresources
   — a credential can leak off-host if the page pulls from elsewhere. For
   host-scoped credentials prefer `cookie()`, which the browser applies under
   its own same-domain rules.

## Reporting

This is a small, single-maintainer utility provided under Apache-2.0 on an
"AS IS" basis (see LICENSE). If you find a genuine Java-side memory-safety or
code-execution issue (as opposed to a Chromium bug — report those upstream to
Chrome), open an issue describing the input and observed behaviour.
