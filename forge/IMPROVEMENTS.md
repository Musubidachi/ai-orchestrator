# Forge — review and open work

Last reviewed 2026-09-14; restructured 2026-09-19. Every finding is backed by something measured on this machine — a
reproduction, a log, a count — and says what it was. Items are closed by a test that fails without
the fix, confirmed by reverting it.

I wrote most of this code, so what I judge to be fine is the least trustworthy part of this file.

## Where things stand

| measure | value |
|---|---|
| Java tests | 696, all passing, across eight modules |
| Playwright tests | 24, all passing |
| Instruction coverage | 83% across all modules (reported, deliberately not enforced) |
| Main classes | 270 in 76 packages, none nested, no package over 8; largest class `Recording` and `Deployer`, 290 lines |
| `index.html` | ~1,200 lines |

```
 64%   forge-app       (cli 13%: the command classes only wire and print; Args is tested)
 82%   forge-pipeline
 83%   forge-marketing, forge-report
 85%   forge-core, forge-security, forge-deploy
 91%   forge-review
```

---

## Nine sessions spent saying "I could not touch the repository" (2026-09-26)

Reported from the dashboard, on 004: *the fix could not be applied because every repository
read/write attempt failed before process startup with the host's `apply deny-read ACLs` error*.

Two faults, one on this machine and one here.

**On the machine.** Codex's elevated Windows sandbox stopped provisioning. Its own `codex doctor`
says so plainly — `sandbox provisioning: failed`, `error code: helper_unknown_error` — and flags
Microsoft Defender as the likely cause, with no exclusions registered for the Codex helpers. Not
Forge's to fix, and not Forge's to change either: it is someone's security configuration.

**Here.** Forge counted those turns as work. The CLI exits 0, the model writes a perfectly coherent
summary explaining that it could not read anything, and from the outside that is indistinguishable
from a turn that tried and failed — so the gate failed it, and it went round again. Nine sessions,
and a plausible-sounding diagnosis of a Playwright video deadlock that the model had inferred
without ever reading the file.

`AgentOutcome.TOOL_BROKEN` names it, and `blockedExternally()` already meant exactly the right
thing: suspend, and do not spend a repair round on a problem no repair can reach. The same treatment
a rate limit gets, for the same reason.

**Detected from stderr only**, which is the entire reason it is safe. This codebase has already paid
once for matching phrases against what a model writes: one project's own template
data contains the phrase "a proxy API that pools and manages rate limits", and two real turns were
thrown away as refusals because of it. Model prose goes to stdout. The signal used here is
`codex_core::tools::router` refusing to create a process — the CLI's internal logger, which no
project's content can reach. `SignalsTest` pins both directions: three of the real rejections
classify as `TOOL_BROKEN`, and the same words in a model's own output do not.

Three rejections rather than one, because a single failure is a hiccup and a turn that cannot start
anything repeats it without end. The run that prompted this logged 258.

---

## The ladder said things the account could not run (2026-09-21)

Reported from the dashboard: two projects sat in `REPAIRING` and `REVIEW_REPAIR` repeating
`RATE_LIMITED: Fable 5.1 requires usage credits`, and the Codex ladder named models a ChatGPT plan
has never been able to reach.

Both are the same fault. `models.json` was seeded from a list written into the code — a guess about
somebody else's subscription, correct when it was typed and unable to notice that it had stopped
being so. `gpt-5.4-mini` was not in the account's catalog at all; `gpt-5.5` was listed but carrying
a retirement notice. Choosing either costs a whole turn to discover, and the refusal arrives as a
failed project rather than as an explanation.

Fable was worse, because it was genuinely available and still wrong. It sat at the top of Claude's
ladder, `ModelChoice` climbs a rung per repair round, and the rounds that most need to succeed are
exactly the ones that reached it — where it refused for want of usage credits, every time, and the
project made no progress while still counting rounds against itself.

**Discovered, not remembered.** `agent.catalog` reads the caches each CLI keeps for its own model
picker, which is the only thing on the machine that knows what a subscription can reach:
`~/.codex/models_cache.json` (visibility, priority, and whether a model is being migrated away) and
`~/.claude/cache/model-catalog/*.json` (the newest, main section only — the overflow section is
previous versions, and putting those on a ladder means choosing an older model on purpose).
Antigravity keeps no such cache, so it is asked directly with `agy models`, which reaches the
network and is therefore kept out of the read path: `/api/models` is served on every page load.

The list on disk is still yours to edit. What changed is that a configured model the vendor no
longer offers is now dropped with a line saying so, and `forge models` shows where the ladder and
the vendor disagree — in both directions, because they mean opposite things.

Nothing reads a credential. The caches are model lists; `auth.json` is never touched.

Fable's exclusion is a **preference, not a limitation**, and is written in one place with that said
plainly — the account can use it, and `CatalogTest` pins that it is left out anyway.

**Also fixed, from the same screenshot:** a Node project describing itself as Python. The activity
line shows the first seventy characters of the agent's current command, and agents patch files by
piping a script into an interpreter — so `running python - <<'PY' import io p="e2e/race.spec.ts"`
was the visible evidence for what language the project was in. A command carrying a script is now
named by what it touches: *running a python snippet on e2e/race.spec.ts*. Ordinary commands are
still shown as typed, because a real command line is already the clearest thing available, and the
inline-script form is recognised only for actual interpreters — `grep -e "foo" app.ts` is a search,
and calling it a grep snippet would trade one wrong description for another.

---

## Watching the code explain itself (2026-09-20)

Asked for: a tool that takes the Maven parent, goes through every child project explaining how each
line functions, and ends with the walkthrough as a summary.

`forge tour` walks the parent's `<modules>` in the order the parent lists them — which in a layered
build is the order the layers stack, so it reaches each class having already explained what it sits
on — and turns every method into a run of scenes, one per line worth hearing. It writes
`logs\tour\tour.html`: one file that plays itself in the browser's own voice, chapters down the
side, the line being talked about lit up. `--video` adds a 720p frame and a recording per scene and
hands both to ffmpeg, so each line stays on screen for exactly as long as it is being said, which
no fixed frame rate manages over a few hundred scenes. Verified end to end: a real h264 and aac
`.mp4`, 1280x720, 77.6 seconds for one small class.

**Nothing calls a model**, here as in the walkthrough. The code is parsed with the JDK's own parser
(`com.sun.source`), so there is no library to disagree with the language and nothing to resolve —
a project that will not build still tours fine, which is when you most want it. Every sentence comes
from the source and the javadoc beside it, so a tour costs nothing to regenerate and cannot describe
the code as anything other than what it says.

**The wording rule is say something or say nothing.** A line with no honest reading comes back empty
and is left out. This is the whole design, and it was tested twice by being got wrong:

- The first real run narrated `return flow.isEmpty() ? work(text) : flow;` as *"answers with is
  empty from flow"* — a ternary read as a call, which says the method returns its own test. Now a
  choice is said as the question it asks: "answers one of two things, depending on whether flow is
  empty."
- It also narrated wrapped lines from their fragments: `return text.isEmpty()` on its own line reads
  like an answer and is the first third of a condition. A line that does not finish is now left to
  the line that finishes it.

Both were caught by reading the generated output rather than by a test, which is the argument for
generating something a person can actually watch. The tests came after and pin them.

The layers are one-way and worth keeping so: `tour.read` finds and parses, `tour.say` decides the
words, `tour` decides what the tour is and in what order, `tour.show` plays it back. A fourth way to
play it goes in `tour.show` and changes nothing above it.

`CodeTourTest` pins the invariant that makes this a walkthrough rather than a slideshow — the line
lit up is the line being talked about — and `LineSenseTest` pins the wording, including the lines
that stay silent. Writing them found two real faults: a mistyped `--only` produced a one-chapter
tour of nothing instead of a miss, and a module with sources but no pom of its own was dropped
without a word.

Scale, on Forge itself: `forge-report` alone is 49 chapters and 992 steps, about 76 minutes spoken.
`--only` takes a module or a class, which is what makes the tool usable for the question people
actually have, which is never "explain all of it".

**Then: worth watching.** Asked for next — animation, storytelling, music, sound effects. The first
version was a slideshow of stills, which is accurate and dull, and the thing standing in the way of
motion was the architecture: a frame per scene written as a PNG becomes tens of thousands of files
and gigabytes the moment anything moves. Frames now go straight down a pipe into ffmpeg and are
never written at all, which is what made the rest affordable.

At 24 frames a second the highlight fades onto a line instead of snapping to it, the code slides
when the narration moves down a long method, the sentence types itself in at roughly the pace it is
spoken — finishing a little early, because text that lands after the voice has said it is text you
read twice — and every module and class opens on a title card. Everything eases; linear movement is
the thing that makes generated video look generated. `MotionTest` pins the part that matters, which
is that the picture serves the voice.

The sound is generated too, for the same reason nothing else here is fetched: a track from a
library needs a licence, a network and a file to keep, and a few hundred lines of arithmetic needs
none of them. A lo-fi bed at 75 beats a minute — sevenths and ninths rather than triads, the
register kept in the middle, off-beats landing late, everything under a lowpass — with a tick as
each line lands and a rising chime as each chapter opens, ducked under the narration by ffmpeg's
sidechain compressor. The generator is seeded: the same tour always gets the same music, because
output that differs between two runs of the same input cannot be diffed.

Two earlier attempts are worth recording, because both were wrong in the same way. The first was a
low drone in A minor with stacked fifths and a decaying bell — accurately described as "omen-esque".
The second, lighter and in a major pentatonic, was pleasant and characterless. What fixed it was
being told the reference ("lo-fi study playlist"), which names a set of specific, reproducible
decisions. Asking for the reference is cheaper than iterating an octave at a time, and the lesson
generalises past music: when the goal is a matter of taste, the useful question is not "is this
better?" but "what should it be like?".

---

## Said out loud (2026-09-20)

Asked for: something that reads the code out loud and says what it does. Two tools, because the two
questions are different ones. "What did this project turn out to be?" is asked about a run, on the
dashboard, where the answer is the run's own files. "How does Forge fit together?" is asked about
this repository, from a terminal, often on the day it will not compile.

**On a card: Read it to me.** `walkthrough.Walkthrough` assembles a spoken paragraph from
`state.json`, `review.json`, `security.json` and the feature list, in the order a person actually
wants it: the verdict first, then how it went, what it claims to do, how it runs, what is *not*
proven, what the reviewer said, what security found, and what is waiting on a decision. The browser
speaks it, and the server writes `runs/NNN/walkthrough.md` beside the run and records
`walkthrough.wav` with the voices Windows already ships (`System.Speech`, offline, no key).

No model is called. That is the whole design: a walkthrough costs no quota, so it can sit on every
card without anyone weighing whether to press it, and it cannot describe a project as anything other
than what is on disk. The failure it is built against is the comfortable summary — a parked project,
or one nobody reviewed, narrated as though it were fine. `WalkthroughTest` pins that: an unreviewed
run says so in the first sentence, a parked one says why, and a run with nothing recorded says that
rather than filling the silence.

`walkthrough.Spoken` holds the wording rules, which are all the same rule — say it the way a person
would. `RaceLoop.java` is "Race Loop", 8,400 seconds is "two hours and twenty minutes", 3,600 is
"1 hour" and not "60 minutes", and markdown is stripped before it is spoken, because a synthesiser
reads `**` aloud as nothing at all and `#` as "number".

The words are shown as well as spoken, in their own element on the card rather than in the status
line: the status line is rewritten by the four-second refresh, so a muted machine, a browser with no
speech synthesis, and a listener who missed a sentence all used to end up watching the answer
disappear. The `.wav` stays linked from the card afterwards, which is what makes it something you
can listen to away from the machine.

**Outside Forge: `explain.bat`.** `explain.bat` reads what Forge is; `explain.bat jobs.queue` reads
one package from its `package-info.java`; `explain.bat ReviewStep` reads one class from its javadoc;
`-q` writes the transcript and recording and plays nothing. It reads `ARCHITECTURE.md`, each
module's `CLAUDE.md` and the package notes directly — no jar, no build, no editor — because the
moment you most want to be told how something fits together is the moment it is broken. Transcripts
and recordings land in `logs\`.

It is a PowerShell script deliberately: the documentation it reads is the documentation the guard
tests already keep honest, so there is nothing here to get out of date on its own. Two things that
did bite — Windows PowerShell 5.1 reads UTF-8 sources as ANSI unless told otherwise (an em dash
arrives as three characters and is spelled out), and `ARCHITECTURE.md` has several tables, so
scraping every row of all of them produces a paragraph that is accurate and impossible to follow.
Only the rules section is read.

Covered by `WalkthroughTest` (8), `UiServerTest`'s two walkthrough cases, and a Playwright test that
stubs `speechSynthesis` and checks the words arrive, contain no markdown, survive a card refresh, and
leave a transcript on disk.

---

## Packages the size of a subject (2026-09-19)

Asked for: an even division. A few packages held most of the code — `jobs` 26 classes, `agent` 25,
`ui` 23, `report` 21, `security` 19 — while `json`, `deploy`, `plan`, `git` and `gate` held three or
four. And each big one mixed three kinds of thing: records, the logic that uses them, and helpers.

Split by subject, with each subject's records in a `model` package of its own, anything over eight
classes split, and the small ones left alone. 21 packages became **69**, holding 241 classes:

| | before | after |
|---|---|---|
| largest package | 26 classes | 8 |
| packages over 12 | 8 | 0 |
| median | 10 | 3 |

No class changed except where the split found something wrong, and the tests are the same tests.

**What the split found.** Seven dependency cycles, each one a shared type sitting a level too high:
a package's entry point and its own sub-packages pointing at each other through machinery that sat
beside the entry point instead of below it. `RunContext` beside `Orchestrator`, `Html` beside
`ProjectReport`, `Http` beside `UiServer`, `JobPool` beside `JobRunner`, `Shell` beside
`SecurityCheck`, the `Agent` interface beside `Agents`, the `cli` helpers beside `Forge`. All moved
below their users. That is now written down as the rule for splitting a subject.

And four records that called back into the code that fills them in:

- `AgentResult.from(...)` classified itself through `Signals`. The factory moved to `Signals`.
- `SecurityReport.forRuling()` read the secrets layer's name off `SecretScan`. The name is part of
  what the report means, so it moved to `SecurityFinding`.
- `Complexity.modelFrom(tiers, ...)` mapped itself onto the model ladder. Moved to `ModelTiers.at`,
  which leaves the enum a judgement about the work with no opinion about models — which is what its
  own javadoc already claimed it was.
- `Wishlist.inRunOrder()` ordered itself through `Ranking`. Moved to `Ranking.runOrder`.

**A guard that was checking almost nothing.** `LayersTest` reads each package's `Depends on:` line,
and read it "up to the first period" — fine while every package name was one word, useless the
moment names contained dots: `agent.ledger, agent.model, agent.policy` was read as `agent`. It now
reads to the end of the list, with a test for a name containing dots. The rule it enforces had been
hollow for exactly as long as sub-packages existed, which was minutes, but the same mistake would
have hidden a real drift.

**And a new one.** `ArchitectureTest.packagesStaySmall` fails the build when a package passes eight
classes. `jobs` reached 26 one reasonable class at a time; nothing said no. Raising the cap is a
one-line edit somebody has to make on purpose.

Every `package-info.java` was written or rewritten: 69 of them, each saying what the package is for,
what belongs in it, what does not and where it went instead, plus the enforced `Depends on:` line.
`forge/ARCHITECTURE.md` gains the convention for splitting a subject.

---

## One ladder, read at every job (2026-09-19)

Asked for: the features reviewed on a lower model to cut consumption, then one big review on the
best; and the plan drafted by the best model, with lower models implementing it. Both are the same
observation — a run buys several kinds of work and was paying the top rate for all of them.

`spec.ModelChoice` now reads `models.json` at each of them:

| job | rung | why |
|---|---|---|
| planning | top | one call, and it decides the shape of every turn after it |
| implementing | middle, or the spec's own model | following a plan that exists is ordinary work |
| each repair round already spent | one rung up | another go from the model that just failed is not the fix |
| per-feature check | bottom | narrow, mechanical: is this claim actually in the code |
| the review | top | the judgement the pipeline turns on, made once |

**A new PLANNING phase.** One read-only turn before any code exists, on the best model the building
vendor has, writing `runs/NNN/plan.md`. `PlanText` puts it in front of every implementation turn,
which is what makes the cheaper rung reasonable — the cheap turns are not deciding anything, they
are following a decision already made. Middle rather than bottom for implementing is deliberate: a
model too weak to do the work fails the gate repeatedly, and three cheap turns plus three gate runs
cost more than one turn that works.

Never fatal, in three ways that each needed saying: a refused planner suspends the project in
PLANNING and spends no session; a planner that answers with nothing leaves no plan and the build
proceeds exactly as it did before the phase existed; and a plan already on disk is followed rather
than bought again — so a hand-written `plan.md` is honoured, and an interrupted run does not pay
twice.

**The cross-review in two passes**, by the same reviewer. Its cheapest model reads the code once per
feature the agent claims and answers `holds` / `unproven`; its best model then reviews the whole
change once, with the unproven claims in front of it as leads, stated as unconfirmed — a weak
model's miss must not arrive as a fact. One refusal stops the cheap pass instead of spending a call
per feature to be refused again, and the pass is capped at twelve claims.

Every verdict is kept in `review.json` and listed on the report, including the ones that held and
the ones nobody could check. That distinction is the point: a claim nobody looked at used to read
exactly like a claim that was fine, which is the same failure as treating an unavailable reviewer as
a clean one.

A spec that names a model keeps it as the **building** baseline and still climbs from there; it does
not pin the planning or feature-checking rungs. So 005 (`codex_model: gpt-5.6-terra`) now gets its
big review on `gpt-6-astra`, and its cheap pass on `gpt-5.4-mini`.

Pinned by `ModelChoiceTest` (the rungs, escalation, an unlisted model, a one-rung ladder),
`PlanningTest` (which model each turn of a whole run actually used, end to end),
`FeatureReviewTest` and the report's claim list.

---

## Restructured 2026-09-19: small pieces, one-way boundaries

Asked for directly: small classes and methods, no nested types ("easy to lose"), and nothing in one
place, because one place is where an edit touches something unrelated and where a model weaves
together things that should stay apart. Done in five steps, each ending with `verify.bat` green and
the dashboard's `/api/jobs` compared field for field against a snapshot taken before step 1.

1. **230 inline qualified names** replaced by imports.
2. **37 nested types** moved to files of their own. The Eclipse errors on `JobStatus.Budget()` came
   from these (Lombok builders on nested records).
3. **Untangled.** `run` depended on 12 of 13 packages and sat in 8 two-way dependencies, plus one
   longer loop (project → gate/review → security → project) that only showed once the pairs were
   gone. New packages `process`, `git`, `project` and `state`; prompts moved beside their users.
   The loop broke by splitting `project` (paths and phases, depending on nothing) from `state` (what
   `state.json` records, which includes gate and review results).
4. **Hubs split by responsibility.** `JobRunner` 1,082 → 243 lines, a facade over `JobPool`,
   `StartRules`, `JobQueue`, `PipelineJobs`, `Shutdown`, `Reopening`, `StatusBoard` and one class
   per button job. `Orchestrator` 643 → 125, dispatching to one step class per phase. `JobStatus`
   222 → 67, each group building itself. `UiServer` 373 → 87 with route classes; `Forge` 346 → 54
   with one class per command; `ProjectReport` 476 → 79 with one class per section;
   `Submissions`' 90-line `act` split into a refusal and a consequence. The three project reports
   rendered from copies of real runs by the old and new jars are byte-identical.
5. **Maven modules**: forge-core, -security, -review, -deploy, -marketing, -report, -pipeline, -app.
   Maven now refuses an upward import (shown by adding one). The fat jar is still written to
   `forge/target/forge.jar`, so no script that starts Forge changed; `forge.bat`'s staleness check
   did, because it only looked at the old `forge/src` and would have run a stale jar for ever.

**Documentation that cannot drift.** Every package has a `package-info.java` saying what belongs in
it, what does not, where to start, and a `Depends on:` line. `LayersTest` reads those lines as the
rule: a package that imports something its line does not name, or names something it no longer
imports, fails the build. Each module and each area with invariants has a `CLAUDE.md`;
`forge/ARCHITECTURE.md` is the map. The Forge-level `CLAUDE.md` is in `forge/`, not the repository
root, because every project's build session runs under the root and would load it.

Found on the way:
- `/api/quit` documented a default wait but read it as required, so a stop without `?wait=` got a
  400. Fixed; `QueryTest`.
- Five tests reached above their own module (a `JobRunner` test inside `RepoLockTest`, `Assignment`
  tests inside `VendorPolicyTest`, a `DecisionStore` test inside `JsonFileTest`, and two borrowed
  constants). Each moved to the package it tests; `LockHolder` and `Clips` are shared through
  test-jars.
- The JSON sweeps (`noPrivateMappers`, `noInPlaceJsonWrites`) would have silently shrunk to one
  module's sources. They moved to `architecture/JsonRulesTest` in forge-app, with a test that they
  see all eight modules.

---

## Security testing, deployment and advertising

Plan: `~/.claude/plans/calm-questing-muffin.md`. Security runs on its own; deploying and advertising
will only ever happen on a click.

**Where it sits.** A passing gate now goes to `SECURITY_CHECK` before the cross-review. Serious
findings earn one `SECURITY_REPAIR` turn (like a review repair: once, and never a repair round);
whatever survives becomes an item on the decision form, first in the list. The project is never
parked for security — the check informs the decision, it does not take it. **Run Security Check** on
a finished card runs it on demand without moving the project, so an accepted one stays accepted.
The phases were appended to the enum, so existing state files mean what they meant.

**The layers**, each reporting ran / skipped / not installed / failed, because a check that never ran
must not read as clean:

- **Secrets** — built-in, precise formats only (private keys, AWS, Stripe live, GitHub, Slack; Google
  browser keys as a note), over the tree git would commit and every commit in history via
  `git grep` in batches. Committed `.env` files with values. Values are redacted everywhere.
  A secret only in history is not serious (no repair can remove it) but still needs a ruling, since
  its owner has to revoke it.
- **Dependencies** — `npm audit --json` (written to a file: large audits overflow the output Forge
  keeps in memory); Maven via `dependency:list` and OSV, test scope excluded; pinned Python
  requirements via OSV, unpinned ones counted as unchecked. Serious means critical/high *with* a fixed
  version: a repair cannot upgrade to a version that does not exist.
- **Static analysis** — semgrep (`p/security-audit`, metrics off) when installed; otherwise a short
  built-in rule list. Always notes, never serious, test code skipped.
- **Live probe** — only with a spec `serve` command. `LocalServer` runs it on a port Forge picks, on
  loopback, and stops the whole tree. The probe refuses any non-loopback address before sending
  anything, and checks headers (notes), exposed `.git`/`.env` (serious), source maps and
  `package.json`, stack traces in error pages, credentialed CORS, and reflected input — serious only
  when a unique tag comes back verbatim in HTML. A deliberately careless test server trips the
  serious checks; a careful one produces no findings at all.
- **AI security review** — another model, given the diff and the scanners' results to confirm or
  dismiss. Serious only when it marks a critical/high finding confirmed. Reviewers fail over like the
  cross-review. Skipped when the previous report already read the same *tree* (not commit: commits
  here are `--allow-empty`, so an empty repair turn would otherwise buy a second paid read).

**Spec fields** `serve`, `serve_path`, `deploy`, `monetization` are validated on load (serve needs
`{port}`, deploy URLs must be https, ad ids named like credentials are refused) and omitted from
saved specs that do not use them.

**Found on the way:** saving in the editor built the spec from the form's own fields, so
`compact_after_sessions` reset on every save and any hand-written block the form had no field for was
deleted. The editor now starts from what it loaded. Pinned by a Playwright test that saves a spec
carrying `deploy` and `monetization`.

### Tried on the real projects

The four local layers were run against 003, 005 and 006 (no AI layer, so no quota), writing only to a
scratch folder. Two false results came out of it, both fixed with a test that fails without the fix:

- **A served `.env` on a project that has none.** Vite's preview server answers any unknown path with
  `index.html`, and its `content="width=device-width"` passed the "assignment" rule. An HTML response
  now never counts as a file, and `.env` needs `NAME=value` lines.
- **Six serious npm findings that were all test tooling.** Every advisory on 003 was in vitest,
  playwright or the vite dev server — nothing shipped to a player — and each would have earned a repair
  turn. A second `npm audit --omit=dev` decides what ships; the rest are reported at their real
  severity as development dependencies and are not serious.

What remained was real: the preview server shows a stack trace on a malformed URL (a note), 005 ships
commons-lang3 3.16.0 and log4j-api 2.24.3 with medium advisories fixed in later versions (OSV, GHSA
ids), and 006's only dependency is test-scoped JUnit. Each layer took under five seconds, the probe
about three including starting the app.

### Deployment

A spec's `deploy` block (`command`, `url`, `requires`, `check`) is run by **Deploy** on the card —
never by the pipeline, never by an agent. The button is live only when the project is ACCEPTED, its
last gate passed, a security check exists with every finding that needs a ruling ruled on (a ruling
saved on an accepted project's report counts, since submissions are written before any refusal),
and every `requires` tool is on PATH; otherwise it is disabled with the reason as its tooltip. A
confirm dialog shows the exact command before anything runs, and the runner re-checks every
precondition rather than trusting a page drawn four seconds earlier.

The command runs through a kept script with `FORGE_DEPLOY=1` and `FORGE_ADS_MODE`; public ad ids
become `FORGE_AD_<NAME>` only for `live`, and an id with characters no id has is left out of the
script. The URL is the spec's or the last https URL printed; the health check is the spec's command
or a GET with retries. A "not logged in" message stops with "run its login yourself" — Forge holds no
credential and runs no login. Every attempt goes to `deploy/deployments.json` and the report; the card
shows the deployed commit, URL and age, and "newer commits not deployed" when HEAD moves (read from
`.git` files, not a git process per card per poll).

A clean working tree is deliberately not required: gates rewrite tracked evidence, so it would block
almost every deploy. The record notes whether the tree was dirty instead.

### Advertising

- **Ads in apps** (`monetization.ads: none | test | live`, `provider`, public `ids`). House rules: one
  ad module with a provider adapter; mode read at build time from `FORGE_ADS_MODE`, so every gate,
  test and recording builds labelled test slots and only a deploy can go live; no hard-coded ids; ads
  never block the app; consent for live web ads; placements in the handoff. An ad offer the spec
  describes is a feature and gets recorded. (The plan's auto-proposed "Ad placements" feature was
  dropped in favour of that rule: the agent lists the real placements, which is more accurate.)
- **Project settings reach the agent.** `SPEC.md` is written once, so serve, deploy and ads settings
  are appended to `CLAUDE.md`/`AGENTS.md`, which are rewritten before every turn.
- **Marketing kit** — **Create Marketing Kit** on accepted projects. One agent turn in
  `runs/<id>/marketing/` (never the repository; the previous kit is cleared first), given the approved
  features, their recordings copied into `media/`, the digest and the deploy URL. It writes `kit.json`
  (tagline, descriptions, posts for X/Reddit/HN/LinkedIn, press paragraph, and `claims` each citing a
  feature id), `site/index.html`, and campaign drafts.
- **Checked by rule, not trusted** (`KitCheck`, `CampaignCheck`): every claim cites an approved
  feature; no invented users, downloads, ratings, stars, testimonials or "trusted by" anywhere; links
  only to the deployed site; X 280, HN title 80, Google headlines 30 / descriptions 90, Meta headline
  40 / primary text 125; ad URLs carry `utm_source`; every campaign has a daily budget, a total cap no
  smaller than it, and a stop condition. Over-limit text is reported with its count, never truncated.
  A kit for an undeployed project is marked as needing a deploy.
- The report's **Marketing kit** section shows each text with a Copy button and the problems, and says
  plainly that Forge never posts, publishes, uploads a campaign or spends money.

## Fixed in the 2026-09-14 review

### Any web page could plant and run code through the editor — critical

The editor binds to loopback and has no authentication, which was treated as its whole defence.
Loopback keeps out other machines; it does nothing about the browser on this one. Reproduced
against a throwaway root: a POST with `Origin: https://evil.example` and
`Content-Type: text/plain` — the shape any page can send without a CORS preflight — wrote
`666-planted.md` with `acceptance: calc.exe`. `/api/jobs/start` had the same hole, the gate executes
acceptance commands, and agents run with permission to do anything. The `Host` header was not
checked either, so DNS rebinding could read responses as well.

`RequestGuard` now classifies every context as a read or a write. All requests must name this
server in `Host`; writes must also come from this origin, be POST, and carry JSON. `CrossSiteTest`
sends the reproduced request over a raw socket; four of its seven tests fail with the guard removed.

### Agent-written pages were served with the API's own authority

Closing the cross-site hole moved the trust boundary to this origin — and `/runs/` serves
agent-written files from it: a project's `out.html`, any SVG in a repository. A prompt-injected agent
could write a page whose script calls `/api/jobs/start` with this server's own Origin. Everything
under `/runs/` except Forge's `report.html` is now served with `Content-Security-Policy: sandbox`,
giving it an opaque origin. Checked in a real browser: a planted page's scripts run, its document
origin is `"null"`, and its attempt to save a spec fails — while the same request carrying the real
origin succeeds. Directory listings, which printed file names unescaped, are escaped and sandboxed.

`/runs/` refused `..` but followed links: a junction in a repository — one an agent makes, or one
npm makes in `node_modules` — let it serve files from anywhere on the disk. Reproduced with a
junction to a folder outside `runs/`; its file was served. Paths are now checked again with links
resolved.

### Pruning deleted files outside the project, through junctions

On JDK 24 / Windows, `Files.walk` goes straight through an NTFS junction. Reproduced: a junction in
`node_modules` pointing at a folder elsewhere, and prune listed — and deleted — the files on the far
side. npm workspaces, `npm link` and pnpm all put junctions there, and prune runs unattended. Links
are now removed as links; deleting a junction was checked to leave its target intact. The same walk
counted disk usage, so that no longer follows them either.

### The dashboard re-read every file of every project every four seconds

The disk figure on each card was a full walk on every poll: about 500 ms of the 550 ms response, over
6,155 files, growing with every dependency. Measured at most once a minute now: the poll went from
~550 ms to ~46 ms.

### Feedback left during a session was never acted on

Submitting while a project ran said "it will be picked up when that stops", and nothing did — the
requests sat on disk until the form was submitted again. That was the reported experience. A marker
file now records it, and when the session finishes the saved requests reopen the project with their
brief. It never accepts: an old "fine as it is" is not a judgement on work done since.

### A spec id could put a project inside a real repository

The id names the run directory an agent is handed with full permissions, and it was not checked. An
id of `../../word-processor` would have scaffolded a project, and started an agent, inside one of the
repositories beside Forge — isolation by directory is the design's whole containment. Ids (and
parent ids) must now be letters, digits, `.`, `-` and `_`, with no `..`.

### Every gate searched the project once per evidence glob

`EvidenceCollector` walked the whole repository for each glob, went through every file in
`node_modules` before discarding it, and followed junctions — so a glob like `**/*.png` could copy
files from outside the project into evidence the report serves. 003's fifteen globs took about
690 ms per gate; one walk that never enters `.git`, `node_modules` or a link takes about 6 ms, with
identical results.

### A logged-out CLI answered a suggestion instead of passing it on

Suggestions and refinements fell back to another vendor only on a rate limit. A lapsed login —
the case the auth patterns exist for — was returned to the person waiting in the editor as
"NOT_AUTHENTICATED" while another subscription sat idle. Both now fall back on either.

### Antigravity's usage was never recorded

`Usage.from` read every vendor but Claude as Codex. Antigravity's closing event is
`{"event":"result","result":{"usage":{...}}}` — no `type`, no `turn.completed` — so each of its turns
recorded nothing, and `usage.json` had entries for Claude and Codex only, while Antigravity did
every review and ran out four times. That is exactly the measurement a window's size is inferred
from. It has its own reader now, tested on a real 003 review's figures.

### Anything a command started in the background outlived it

`CliRunner` killed the process tree only on a timeout. An acceptance command, or an agent turn, that
exited normally left whatever it had backgrounded — a preview server, a watcher — running, holding
its port and file locks into the next gate. The tree cannot be listed once its root has gone, so it
is now noted every half second while the command runs, and anything from it still alive at the end
is stopped. `ProcessHandle` checks start times, so a recycled process id is never hit. None of the
six specs relies on a server outliving the command that started it. Tested with a batch file that
keeps writing after its command returns: it grew from 51 to 63 bytes after the gate without the
fix, and not at all with it.

### A gate could test a server it did not start

003's `playwright.config.ts` has `reuseExistingServer: !process.env.CI`, Playwright's generated
default, and nothing set `CI`. So whatever already answered on port 4173 — a preview server an agent
left running with an older build, or another Vite project gating at the same moment on the same
default port — is what the acceptance suite would test and the feature recordings would film. Not
caught in the act (no listener on those ports when checked), but it is a pass for code nobody tested.
Gate scripts now set `CI=true`, in the script itself so rerunning it by hand reproduces the gate, and
the house rules tell agents to stop the servers they start.

### A note beside a filmed feature was accepted over

One feature request on 003 is still on disk, under **Worth seeing**, and has never reached an agent
(nothing in any log quotes it). The brief did carry feature remarks by then, but only if something else
reopened the project: with every feature filmed and every review item fine, the same submission
*accepted* it and the request was lost a second time. A feature's radio buttons rule on its
recording, so a note beside one is about the feature: a note no session has been given now reopens
the project instead of letting it be accepted. Notes are marked heard when handed over, so a
delivered remark neither blocks the next acceptance nor goes back with every later session. Notes
on declined features, and on review items left standing, behave as before.

### An agent killed Forge, and the project paid for it in repair rounds

005 parked on "No overview class was named in the handoff" with `sessions: 0` and all four repair
rounds spent. Its history is five commits, every one "Recovered work from an interrupted … turn",
and none of its four Claude invocations has an exit line. The first ends on the agent running
`Get-Process java | Stop-Process -Force` — every Java process on the machine, Forge included
(`claude.log` line 181, the last line but one). The other three show nothing of the kind; forced
stops during rebuilds, before `quiesce` existed, are the likely cause.

Two fixes. The house rules now forbid stopping processes by name and say why. And a recovered turn
counts as a session — a model did work, and that is what bounds a project that keeps dying — but
its failing gate no longer costs a repair round: with no handoff it fails on the overview whatever
was built, so the round was charged for a turn the agent never got to finish. The flag is kept in
the run state, so a crash between recovery and gate does not lose it.

### Antigravity's refusals were read as reviews that failed to parse

The rate-limit pattern was a guess from Google's wire values, and the real message matched none of
it: "Individual quota reached. Please upgrade your subscription to increase your limits. Resets in
141h2m15s." Four refusals across 002, 003 and 006 were classified as ordinary failures, so the
ledger never marked Antigravity out, the review loop did not move on to Claude or Codex, and each
project's review said "no JSON object found in the reviewer's reply" — a reviewer apparently
answering badly, when it had never answered. The phrase is matched now, and "Resets in 141h2m15s"
is read as a countdown, so the vendor is recorded as out for the six days it said.

Behind it was a second gap: a reply that could not be parsed has no findings, so it counted as
clean. The review loop stopped there and the project went to DONE, with Claude and Codex never asked.
An unreadable reply now moves on to the next reviewer, and is kept only if nobody after it answers.

### A failed build left the editor down

`verify.bat` stops the editor, cleans and builds. When a test failed, `clean` had already deleted the
jar and none was built, so the restart step found nothing: "No jar; nothing to start". It happened
this session, on a test-cleanup race. Both `verify.bat` and `redeploy.bat` now keep the running jar
in `forge/last-good/` and restart on it when the build produces none. Checked by adding a test that
always fails and running `verify.bat`: it reported the failure and the editor answered 200
afterwards.

The race itself: a finished project leaves the running set before it writes its report, so tests
waiting on "not running" plus a fixed 100 ms were deleting a directory still being written. They
now wait on a count of job threads that have not finished their cleanup.

### A stop that timed out left Forge unable to start anything

`quiesce` set `draining` and never cleared it. When a turn outlasted the wait, `redeploy.bat` said
"Forge is still running and still working" and rebuilt nothing — and the Forge it left running filled
no capacity and refused every Start as "shutting down", until restarted by hand. A stop that gives up
now resumes the queue. The same wait threw straight out on an unreadable state file, which failed
the request with Forge still draining; an unreadable project now counts as working, so the stop is
refused rather than let through unchecked.

### Two specs could share an id

Nothing checked. The id names the run directory, so two specs with one id build in one repository
against one state file, each resuming the other's work. The editor takes ids typed by hand, and a
suggestion is told the ids in use but free to ignore them. A new spec whose id and title matched an
existing one also overwrote that file silently, since both render to the same filename. Saving and
validating now refuse an id another wishlist file holds.

The same hole had a second door: a run directory outlives its spec, so a new 007 written after the
old one was deleted would resume the old project's state, repository and reviews. An id whose
`runs/<id>` holds a project under a different title is refused too; the same title is let through,
because that is someone restoring the spec they deleted.

Suggestions no longer walk into either refusal: the model's id is kept when it is free, and
otherwise replaced with the next number past every spec and run directory.

### Every gate added the feature recordings to git history again

A gate deletes and re-records each feature's clip, and the next commit — Forge's own `git add -A`
after a turn, or the agent's — stored the new take as another binary. 003's history held 73
media blobs, 21.8 MB, from six commits that changed nothing else in `artifacts/feature-*`, two of
them agent commits titled "Refresh … recordings". Forge now puts `artifacts/feature-*` in
`.git/info/exclude` (not `.gitignore`, which is the project's) and drops them from the index before
every turn; the files stay on disk, where the gate and report read them. Existing history keeps what
it has. The first turn on an older project commits the untracking, which counts once as a change.

### The repository lock was taken after the state was read

Both runners read a project's state to decide it may run, then took the lock later, on a worker.
If the other Forge process finished a step and let go in that gap, the stale snapshot was advanced
and saved over the newer file — sessions uncounted, a handoff lost, or a completed turn "recovered"
again because the snapshot still said `turnInFlight`. Found by reading, not observed; the lock is
new and the scheduled task is not registered here. Once the lock is held, the file now wins unless
nobody has written since, in which case the snapshot is kept for the Start override it may carry.

### Two Forge processes could run the same project at once

The editor's auto-run and the scheduled `forge run` both start eligible projects, and each knew only
what it was running itself. The README recommends registering the scheduled task; had it been, both
could have picked the same project — two agents in one working tree, racing state writes, double
quota. Each run now holds an operating-system lock beside its repository (shared with improvements,
which work in their parent's tree). An OS lock rather than a marker, so a killed holder releases it.
Tested against a real second JVM, including one killed while holding it.

### Successful turns were thrown away as rate-limited

`Signals.classify` matched phrases like `rate limit` against *everything* a CLI printed — for
stream-json agents that is the model's prose and every file it read — before looking at the exit
code. Project 006's own template data says "manages rate limits"; two of its turns exited 0 after
five and eight minutes of work and were classified as refusals, which suspends the project, blocks
the vendor for everyone, and reruns the turn. A login page saying "please sign in" was the same trap
for the auth patterns. Across 91 recorded invocations every genuine limit exited non-zero, so exit 0
is now `COMPLETED` before any text is read.

### A reset time seconds in the past blocked Codex for a day

Codex reports minutes. Refused at 6:55:43 PM and told "try again at 6:55 PM", the parser read a time
already past as tomorrow: Codex was blocked for 24 hours and project 004 sat suspended all night. A
stated time up to fifteen minutes behind is now "now". The weekly limit's dated form ("try again at
Sep 13th, 2026 12:40 AM") was not matched at all and fell back to a default wait; it is parsed.
The live ledger and 004's state were corrected (backups kept).

### A refused Codex call reported the previous turn's handoff as its own

Codex only writes `codex-last-message.txt` when a turn produces a message, and Forge read whatever was
there. The quota ledger recorded "status: complete … all acceptance commands pass" as the reason
Codex was blocked, and a call that did nothing could hand the orchestrator the last session's
finished plan. The file is deleted before each call.

### The concurrency cap did not hold, and Start could launch a project twice

`running` was filled by the pool thread's first line, not at launch. `fillCapacity` checks the cap
inside the loop that launches, so one pass could start everything eligible; a second Start could put
two orchestrators on one repository. With registration moved back, the cap-of-two test starts
**three**. Registration is now synchronous.

### A step that threw was relaunched as fast as it could fail

An exception was handled like a stop: slot freed, `fillCapacity` run, project still eligible in the
same phase, launched again — forever, rewriting its report each pass and never saying anything on the
card. It now records `coolOffUntil` and a visible error; auto-run waits ten minutes, an explicit Start
overrides.

### Notes could be lost to a kill mid-write

Only `state.json` was written atomically. `DecisionStore`, the quota and usage ledgers, reviews and
settings wrote in place — and every loader reads an unreadable file as empty. A kill half-way through
writing `decisions.json` would leave a truncated file, the next load would see no notes, and the next
submission would save only itself over all of them. `JsonFile.write` is now the only way JSON is
persisted, and a sweep test fails any new in-place write.

### One bad state file blanked the dashboard and stopped the whole queue

`RunStore.load` threw through the job list and the queue's sort, so a single hand-edited or
Notepad-saved `state.json` (Notepad adds a byte-order mark Jackson refuses) removed every card and
prevented every project from starting. Surveys now skip it and give it a card saying what is wrong;
code that acts still fails, so a damaged project is never recreated over its own history.

### The detached editor wrote its output nowhere

It stopped on its own overnight — no reboot, one exit path in the code — and nothing recorded why.
`restart.ps1` now keeps `logs/forge-ui.log`, archives the last twenty under `logs/archive`, and
long-running commands stamp every line with the time. **Why it stopped remains unknown**; the log will say next time.

---

## Done since the previous review

- `Forge.java` argument handling tested (was 0%).
- One `Eligibility` for both schedulers, after the third divergence.
- One `ObjectMapper` for everything that persists, with a sweep test.
- Inline fully-qualified names removed.
- Unsaved-changes guard in the editor.
- `CliContractTest` pins each CLI's flags without spending quota.
- `JobStatus` built by name, with a reflection sweep for unset components.
- Submission handling moved out of `JobRunner` into `Submissions` (JobRunner 1,092 → ~870 lines).
- Graceful rebuild: `quiesce.ps1` / `redeploy.bat` wait for an agent turn to commit instead of
  killing it.
- `JobStatus` grouped into identity, progress, budget, handoff and review records. Each group is
  `@JsonUnwrapped`, so the dashboard's JSON is unchanged field for field — pinned by a test that
  serialises through the API mapper — and the null sweep now recurses into the groups, where the
  flat version would have passed with every field inside them null.
- The dashboard shows each vendor's current window (turns, tokens, cost where the vendor gives one)
  and the range earlier windows ran out in, from `/api/usage`. Deliberately no "remaining" figure:
  the windows seen here ended between 15M and 135M tokens. Cards show the last gate's duration and
  name any acceptance command running well past its own history.
- `Suggester`'s two request paths share one ask, one parse and one fallback. The copies had
  drifted: only one guarded against a null error. The rewrite also sizes Antigravity's model to the
  judged complexity, which a suggestion had left on its CLI default.
- "Slower than usual" needs 30 seconds more as well as 1.5×. Once it reached the cards, the old
  5-second floor put a warning on every project here — `mvn -q test` at 6s against 3s, `pip install`
  at 14s against 8s — which is a warning on none. The two Playwright suites that really had slowed
  (94s against 51s, 93s against 31s) still show.

---

## Open, in order

### 1. The `ui` package is the least tested

`Suggester` now has tests with stub agents (fallback, sloppy replies, refinement) and `/api/rank` is
driven over HTTP; the `/api/suggest` and `/api/refine` handlers themselves still only run by hand.

## Leave alone

- **`JobRunner` as a facade.** It is the one lock; splitting the lock with the class would let two launches interleave.
- **Hand-rolled argument parsing.** Less to audit than a library.
- **The batch and PowerShell scripts.** `verify.bat` does not verify itself; a harness would cost more
  than it returns. Know that a change to them has no safety net.
- **Coverage as a target.** A threshold gets met by testing whatever is easiest.
- **`index.html` as one file.** Splitting it buys little until there is JavaScript worth unit testing.

## Do not trade away

- **The gate decides done, not the agent.** It now extends to recordings: the gate deletes them and
  requires the acceptance commands to regenerate them.
- **Exit code before prose.** A CLI's output is the model's words; only failure makes them evidence.
- **Every endpoint is a read or a write**, and a write needs this origin, POST and JSON.
- **Nothing an agent wrote is served with this origin.** Only Forge's own report escapes the sandbox.
- **Never walk a tree you might delete or count without refusing links.** Windows junctions are
  followed by `Files.walk`.
- **What a person writes is saved before anything can refuse it.**
- **Persist through `JsonFile.write`.** Loaders forgive broken files; writers must never make them.
- **`not_verified`** — the most useful text on any report.
- **Failed and refused invocations cost neither a repair round nor a session.**
- **A planning turn is never fatal, and never bought twice.** No plan, a refused planner, or a
  `plan.md` written by hand must each leave the project as buildable as it was without the phase.
- **Which model runs a turn is `ModelChoice`'s decision**, not a model name at a call site.
- **The architecture rules**: `LayersTest` (documented, one-way packages), `ArchitectureTest` (no
  nested types, no inline qualified names), `JsonRulesTest`, and the module boundaries themselves.
  Loosening one is an edit someone has to make on purpose, in the open.
