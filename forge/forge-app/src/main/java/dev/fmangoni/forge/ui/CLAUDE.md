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
brings its own `<style>`, and hides what it replaces with a class rather than removing it. One page
function is replaced, by one extra: `pause.js` wraps `groupOf`, on the first `forge:jobs`, because
the extras' tags come before the page's own script, whose declaration would replace a wrapper set
any sooner. To take one out, delete its file and its tag. `ExtrasTest` checks that every extra has a tag, is served,
parses, and is wrapped: one that was not, and reused a name the page declares, would stop the
page's own script and leave the dashboard empty.

`wait.js` shows nothing. It works out what a waiting project waits for and the one time that
counts, and gives the page one name, `ForgeWait`, which the waiting card (`status.js`) and the next
24 hours (`nextday.js`) both use, so the two never give different times. Without it each of them
fails its own check and leaves the page as it was.

`families.js` puts one row in the table for each project that has versions, where its first version
stood, and draws the versions as a chain. Projects with no versions share one row the same way, two
or more under one heading whose projects want nothing done here (Not on this machine, Accepted);
under any other heading each keeps its own line. The page still places every line on each poll; the
extra reads both of its orders back, `--od` for a wide screen and `--om` for a phone's ranked list,
and gives each line a new place in each, so opening a row is the page's own lines, hidden or shown
by a class. On a phone a closed row hides its projects' details too. It makes the tiles the
table's only counts on a wide screen too, and hides the line of counts and the headings' numbers.

`pause.js` makes Stop ask why, and the project is then held until Resume Now; one that is not
running can be paused from its panel the same way. The endpoints are `PauseRoutes`
(`/api/jobs/pause`, and `/api/pauses` for the reasons); what a pause does is `jobs.pause`. A held
project's row and panel say so, with how long and why. It gives the page one name, `ForgePause`, and
fires `forge:pauses` when the reasons change: the waiting card, the next 24 hours and the family
rows ask it, so a project you paused is never also said to be waiting. Without it they show what
they showed before. The dialog can also ask for a reminder: a day, a week on unless another is
picked, kept with the reason (`PauseNote.remind`), when the project moves to Needs you, still held.
Where a held project is listed is the wrapped `groupOf`'s: under Paused, or Needs you once its
reminder is due, never under the quota or the version it also waits for, so the headings, the
tiles and the counts agree with its row. Stop on a finished project ends a button's job, a deploy
say, and stays the page's own.

`gate.js` puts the last gate under the open project's stats: quick or full, passed or failed, and
what it did with each command (ran, failed, not reached, waits for the full gate, not run yet),
each drawn by its usual time against the line a quick gate stops at. It reads `/api/gate?id=`
(`GateRoutes`; what it reads is `status.gate`) for the open project only, again when the poll's
phase, sessions or gate time for it change. While its section shows, it hides the "last gate" stat,
which adds every command's latest time whether or not the last gate ran it. The head says which
session the gate followed, and once a version has had more than one gate, the latest eight are
listed under it with the time its quick gates spared (`status.gate.GateHistory`, kept beside the
version's state by a step the `Outline` attaches after each gate). It gives the page one name,
`ForgeGate`, null until the open project's answer is fresh, and fires `forge:gate` when one
arrives: the waiting card says from it what the last gate ran, instead of the poll's sum.

`review-pictures.js` is the quick review's extra, tagged beside `attach.js` in `review.html`. On
the card that asks whether the screens look like the spec's pictures (`pipeline.PictureLook`,
found by those words), it puts each picture beside its screenshot, between the question and the
note. It reads them from the report's own Pictures section (`report.outward.PicturesSection`), so
they are the ones the report shows; when it cannot, the card says so and links to the report.

`restyle.js` lays the board out as the mockups draw it. At every width the next 24 hours and the
projects are two sections: the queue switch moves into the first (a small card says every
vendor is open and holds it when the strip has nothing to show), the projects' counts and buttons
into the second's heading, and spend, usage and the league fold under the table, left open or
closed as they were (`localStorage`). From 1200 pixels the table gets a header and a Now column
that says what each project waits for (from `ForgeWait` and `ForgePause`), and hides the rank (kept
in the line's small print), the open items, the pause line and the group headings, which the tiles
stand in for. Each lane's caption is split around its mark. The page still chooses a project as
before, but its panel shows only once a line is clicked, pushes the table aside, and closes with its
× or Esc (left to a field being typed in, and to a dialog). Up to 1399 pixels the Tokens column is
hidden, and Worked too while the panel is open. It moves the page's own elements rather than copying
them, so deleting its tag brings back the board as it was.

`restyle-panel.js` draws a project's panel as the mockup does: its five steps listed top to bottom,
as the waiting card lists its own, at the first that is yours or has failed; its counts three to a
row, worked, sessions and tokens first; the builder's handoff as a quote said to be its, without the
count of steps once none are left; its buttons in one row, without their groups' headings; and the
builder's vendor named as the table names it. From 1200 pixels the phase tag and line go, since
the card and the Now column say it. The waiting card (`status.js`) and a paused panel (`pause.js`)
keep their own. A panel takes the extra's class only once its list is filled, so a failure, like
deleting the tag, leaves the page's own panel.

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
`QueryTest`, `PauseRoutesTest`, `GateRoutesTest`, and the Playwright suite in `ui-tests/` against
fixture roots (`status.spec.js` for the waiting card, `nextday.spec.js` for the next 24 hours,
`families.spec.js` for the family and single-project rows, `pause.spec.js` for Stop with a reason, `gate.spec.js`
for the last gate, `review-pictures.spec.js` for the pictures on the quick review, `restyle.spec.js`
for the board's layout and its panel, `restyle-panel.spec.js` for what the panel shows).
