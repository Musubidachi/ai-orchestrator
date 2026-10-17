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

`UiServerTest`, `SpecApiTest`, `SuggesterTest`, `CrossSiteTest`, `UiScriptTest`, `QueryTest`, and the
Playwright suite in `ui-tests/` against fixture roots.
