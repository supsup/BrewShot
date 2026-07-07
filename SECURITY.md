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
| CDP events | routed by method name; console/errors kept as text | bounded, never executed |

The Java code and the page's HTML/JS **never touch**. There is no place where
page markup or scripts are interpreted, `eval`'d, deserialized into objects, or
reflected into class loading on the Java side. A `.html` crafted to compromise
the *Java process* would first have to break **Chromium's renderer sandbox** —
a browser-exploit-chain problem entirely inside Chrome's threat model.

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
