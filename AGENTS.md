# BrewShot for agents — giving an AI eyes

An AI agent maintaining a web app can read every line of a template and still
not know what the page *looks like*. BrewShot closes that gap: the agent
renders the page in a real Chrome, reads the PNG back into its context, and
*sees* the regression a human would have caught on sight.

Three integration paths, in order of how real they are today:

---

## 1. The CLI pattern — works today, zero wiring

Any agent that can run shell commands and read images already has eyes:

```
brewshot http://localhost:8080/docs/some-page -o /tmp/shot.png --settle 1200
# … agent reads /tmp/shot.png back into context and looks at it
```

Useful agent moves this enables (all field-tested — this file is written by
an agent that uses BrewShot this way):

- **Before/after on visual changes** — shoot the page, make the change, shoot
  again, compare by eye before telling a human it works.
- **Post-deploy verification** — "the deploy succeeded" becomes a picture of
  the live route, not an HTTP 200.
- **Debugging with the page's voice** — `--eval` prints JS values; the library
  path adds `errors()` so the agent sees the exception, not just the blank
  card it caused.
- **Authed routes** — the library's `cookie()`/`header()` (session or
  basic/bearer) let an agent shoot pages behind a login.

## 2. A skill (Claude Code / agent-harness) — works today, ~20 lines

A skill is just instructions + the CLI. A real, working example:

```markdown
---
name: page-eyes
description: Screenshot a rendered page (URL, local file, or raw HTML) with
  BrewShot and read it back, so you can SEE what you're changing. Use before
  and after any visual change, and after any deploy that touches a page.
---

# Page eyes

1. Shoot the page (pick the input form that fits):
   - URL:        `brewshot <url> -o /tmp/eyes.png --settle 1200`
   - local file: `brewshot path/to/page.html -o /tmp/eyes.png`
   - raw HTML:   `cat page.html | brewshot - -o /tmp/eyes.png`
   Bigger pages: add `--size 1440x2000`. Animated pages: settle longer.
2. Read `/tmp/eyes.png` back and actually look at it before drawing
   conclusions from markup.
3. For "did the page throw?" use the library path (`errors()`), or
   `--eval "window.__errors || 'no hook'"` if the page exposes one.
4. Before/after discipline: keep both shots; describe the visual delta to
   the user, not just "it changed."
```

Drop that in your skills directory, point the agent's allowlist at the
`brewshot` binary, done.

## 3. An MCP server — a recipe, not (yet) a shipped artifact

**Honesty first: BrewShot does not ship an MCP server today.** But the fit is
natural and the wrapper is thin, because MCP tool results can carry images.
If you build one (or we ship one later), the tool surface that matches
BrewShot's philosophy is small:

| MCP tool | wraps | returns |
| --- | --- | --- |
| `screenshot` | `open(url)` → `screenshot()` | image (PNG) |
| `render_html` | `html(source)` → `screenshot()` | image (PNG) |
| `page_errors` | `open(url)` → `errors()` + `console()` | text |
| `page_eval` | `open(url)` → `eval(js)` | text (JSON value) |

Implementation shape: a `main()` that speaks MCP's stdio JSON-RPC, holding one
lazy `BrewShot` instance per request (or a small pool); each tool call is
5–10 lines delegating to the library. The zero-dependency discipline can hold
— MCP's protocol is JSON over stdio, and BrewShot already ships a JSON
reader (`MiniJson`) and proves the pattern of speaking a JSON protocol with
no framework.

Why you might NOT want it: an agent with shell access gets everything from
path 1 with no server to run. MCP earns its keep when the agent *can't* shell
out (hosted contexts) or when you want the browser pooled/warm across calls.

---

*Provenance note: paths 1–2 aren't speculative — this project's own
development loop runs on them (an agent with a standing "use BrewShot anytime"
grant, shooting pages before reporting them done). Path 3 is a design sketch
and labeled as such.*
