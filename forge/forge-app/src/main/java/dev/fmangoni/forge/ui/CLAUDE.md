# ui

The local editor and dashboard: `UiServer` on the JDK's HTTP server, bound to loopback, serving
the page from the jar (`src/main/resources/ui/index.html`) and a small JSON API. `UiServer` only
wires; the endpoints are in `SpecRoutes`, `JobRoutes`, `SystemRoutes`, `ReviewRoutes` and
`PolishRoutes`, each registered through `Endpoints` as a read or a write. The quick review
(`ui/review.html`, `/review`) and Polish (`ui/polish.html`, `/polish`) are pages of their own, served
from the jar the same way. `ui/attach.js` (`/attach.js`) is shared by all of them and by each
report's form: a box marked `data-attach="<project id>"` takes a pasted, dropped or picked image,
posts it to `/api/jobs/attach` (`jobs.submit.Attachments`), and gets `[image: .forge-images/…]` put
in its text. Notes stay plain text, so nothing downstream had to learn about pictures.

## Extras

Each improvement to the dashboard that can stand alone is an extra: one script in `ui/extras/`,
served at `/extras/<name>.js` by `ExtraRoutes`, attached by its tag in the block beside
`attach.js` and nowhere else. An extra learns what the page loaded from two events, `forge:jobs`
(every project, after the page has placed them) and `forge:quota` (the vendors closed right now,
as the top bar's chips show them), and calls the page's own helpers (`groupOf`, `steps`, `esc`, ...)
only when one fires. It wraps itself in a function so its names cannot collide with the page's,
brings its own `<style>`, and hides what it replaces with a class rather than removing it. To take
one out, delete its file and its tag. `ExtrasTest` checks that every extra has a tag, is served,
parses, and is wrapped: one that was not, and reused a name the page declares, would stop the
page's own script and leave the dashboard empty.

## Must not be traded away

- **Only the editor itself may act.** `RequestGuard` checks Host (against DNS rebinding), Origin and
  Sec-Fetch-Site, and requires a JSON POST for anything that changes state (`CrossSiteTest`).
- **Every endpoint is a read or a write**, registered through `Endpoints`; there is no third way.
- **Handlers are thin.** A handler parses a request type (`JobRequest`, `DecisionRequest`, ...) and
  calls `jobs` or `SpecApi`. Logic in a handler cannot be tested without HTTP.
- **Deploy and marketing endpoints act only on a POST** that a click in the page sends. The page
  asks for confirmation before a deploy.
- **The dashboard JSON (`/api/jobs`) is flat on the wire.** Grouped records use `@JsonUnwrapped`,
  so the page and the Playwright tests are unaffected by how the Java is organised.

## Tests

`UiServerTest`, `SpecApiTest`, `SuggesterTest`, `CrossSiteTest`, `UiScriptTest`, `ExtrasTest`,
`QueryTest`, and the Playwright suite in `ui-tests/` against fixture roots (`status.spec.js` for the
waiting card).
