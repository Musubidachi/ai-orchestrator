# Forge — notes for a reviewer

Written 2026-09-27. This is the document I would want if I were picking this project up cold and
had been asked to judge it. It covers what Forge does, how, why it was built that way, and — at the
end, at length — the shortcuts, assumptions, risks and open questions I would want someone else to
look at.

**Provenance, so you can weight what follows.** Most of Forge predates me; I have read it, changed
parts of it, and describe it here as I understand it. Sections marked *(mine)* are work I did
recently and can speak to first-hand. Everything else is my reading of someone else's design, and
my reading could be wrong. Where I am inferring rather than knowing, I say so.

---

## 1. What Forge is

An unattended build pipeline that drives coding-agent CLIs — Claude Code, OpenAI Codex, Google
Antigravity — against a wishlist of project specifications. You write a spec; Forge scaffolds a
repository, runs agents against it in sessions, checks the result itself, has a *different* vendor's
model review it, runs a security pass, and presents you with something to accept or reject.

It runs on one Windows machine, against consumer CLI subscriptions, over loopback only. It holds no
credentials and never uses an API key.

**Scale.** 8 Maven modules, 341 main classes in 83 packages, 94 test files. Roughly 33,000 lines
of main Java, plus a ~2,500-line single-file dashboard and eleven `.bat`/`.ps1` scripts at the root.
*(Measured 2026-10-17. It was 281 classes at the audit in §15, and 286 just after it.)*

## 2. The one idea

**Forge decides whether a project is done, not the agent.**

Every spec declares acceptance commands. Forge runs them itself, against the files on disk, in a
clean process. An agent saying "all tests pass" is treated as a claim, not a result. This is the
load-bearing idea; most of the rest of the design follows from taking it seriously.

Consequences worth noticing:

- A spec without acceptance commands is refused at load. Otherwise it could only ever be marked done
  on an agent's say-so.
- Evidence globs are declared in the spec, and the gate collects them. A feature with no artefact is
  an unproven feature.
- The cross-review is by a *different vendor*, because a second opinion whose blind spots are the
  first one's is not a second opinion.

## 3. The shape of a run

Phases, persisted **by name** into `runs/NNN/state.json`:

```
NEW → PLANNING → SCAFFOLDED → GATING → SECURITY_CHECK → REVIEWING → DONE → ACCEPTED
                      ↘ REPAIRING / CONTINUING / REVIEW_REPAIR / SECURITY_REPAIR ↗
                                    ↘ PARKED   ↘ SUSPENDED
```

- **PLANNING** — one read-only turn on the best model the building vendor has, writing `plan.md`.
  Never fatal: a refused planner suspends rather than failing, a plan already on disk is honoured
  rather than bought again, and no plan at all means the build proceeds as it did before the phase
  existed.
- **GATING** — Forge runs the acceptance commands.
- **REPAIRING / CONTINUING** — an agent turn in response to what the gate found, or to unfinished
  work. Each repair round escalates one rung up the model ladder.
- **REVIEWING** — the cross-review (§5.6).
- **PARKED** — out of repair rounds or sessions. A verdict.
- **SUSPENDED** — blocked by something environmental, and explicitly *not* a verdict (§5.2).
- **Waiting for a person** — not a phase but a hold (`needsPerson` in the state) that can sit on any
  phase: a logged-out CLI, a broken sandbox, or the third identical failure in a row. Only Start
  lifts it. *(Added after the audit, §15.)*

`turnInFlight` is written to the state file before an agent is called and cleared after the commit.
It is the authority on whether stopping now would destroy work — see §5.9.

## 4. Architecture

Eight modules, each only able to use the ones below it; Maven enforces the direction between
modules and `LayersTest` enforces it between packages *inside* them.

```
forge-core        json, process, git, agent, spec, plan, project   (no capability at all)
forge-security    the security layers and its AI pass
forge-review      the gate, the cross-review, run state
forge-deploy      publishing, only on an explicit click
forge-marketing   the marketing kit
forge-report      pages, the spoken walkthrough, the code tour
forge-pipeline    the orchestrator, the job queue, dashboard status
forge-app         the HTTP server, the dashboard page, the CLI
```

**Three guard tests**, all in `forge-app` because only that module sees every other:

| Test | Forbids |
|---|---|
| `LayersTest` | a package cycle; an import a package's `Depends on:` line does not declare; a package with no `package-info.java` |
| `ArchitectureTest` | nested types; inline fully-qualified names; a package over 8 classes; `@Builder` on a record |
| `JsonRulesTest` | shapes that would break persisted files |

The `Depends on:` line in every `package-info.java` is *the* dependency declaration — the test reads
the documentation and compares it to the imports, so the docs cannot drift from the code. Adding an
import means editing the prose, deliberately.

The 8-classes-per-package cap is the unusual one. It is a forcing function against hub classes:
you cannot let one package accumulate, so responsibilities get named and separated. It has produced
a lot of small packages; whether that is better than a few large ones is a matter of taste, and it
is the structural decision I would most expect a reviewer to argue with.

## 5. Subsystems

### 5.1 Specs and the wishlist

Markdown files with YAML front matter in `wishlist/`. Id, title, priority, stack, acceptance
commands, evidence globs, caps (`max_repair_rounds`, `max_sessions`, timeouts), optional model pins.
`SpecLoader` validates; the dashboard's Check and Save go through the *same* loader the overnight
run uses, so the error you see in the browser is the error Forge would raise. An invalid spec is
refused rather than written — a file that fails to load is worse than no file, because the run skips
it silently and the project simply never gets built.

### 5.2 Agents and vendors

`AgentKind` is CLAUDE, CODEX, ANTIGRAVITY. Each has an `Agent` implementation that builds a command
line and parses a JSONL event stream. Antigravity is a reviewer by default, not a builder: its free
tier is a request cap, which is ample for one read-only review turn and nowhere near a build.

**`AgentOutcome` is the important type here.** The distinction that carries the most weight is
between `FAILED` and everything else:

| Outcome | Means | What follows *(corrected after the audit)* |
|---|---|---|
| `COMPLETED` | the turn ran | a session; the gate judges the work, and a failed gate costs a repair round |
| `TIMED_OUT` | killed at its wall-clock cap | the same as `COMPLETED`: a session, then the gate |
| `FAILED` with changes on disk | it ran and genuinely failed | the same: a session, then the gate |
| `FAILED`, nothing changed | the call itself failed | suspend ten minutes, no budget; the third in a row waits for a person |
| `RATE_LIMITED` | quota exhausted | suspend until the stated reset (fifteen minutes if none) |
| `NOT_AUTHENTICATED` | no valid session | waits for a person; vendor held fifteen minutes in the ledger |
| `TOOL_BROKEN` *(mine)* | the CLI's own sandbox started no process | waits for a person; vendor held fifteen minutes |

The original version of this table said `FAILED` was "the only one" that costs a repair round. No
outcome costs one directly; a failed gate does. And it said a blocked project "suspends and waits for
a person" — it suspended with no resume time, which the queue read as *ready now*, so it relaunched
at once, for ever, without counting a session. For `TOOL_BROKEN` that meant back-to-back 6–25-minute
agent turns. See §15.

**Classification is deliberately conservative.** `Signals.classify` treats a clean exit as a finished
turn regardless of what the output says. This is a scar: phrases were once matched against
everything a CLI printed, and a project that *generates business ideas* produced the words "a proxy
API that pools and manages rate limits" in its own output — two real turns were discarded as
refusals. Measured before it was changed: across 91 recorded invocations, every genuine rate limit
exited non-zero. **Never match a phrase against model prose.** `TOOL_BROKEN` obeys this by reading
`stderr` only, where the CLI's own logger writes and a project's content cannot reach.

*(Corrected after the audit.)* That rule was only half kept: a clean exit was safe, but every
non-zero exit was still matched against the whole transcript, so a failed turn on any project with
a rate limiter or a sign-in page was read as a quota refusal or a logged-out CLI. Failed calls are
now matched against `stream.CliWords` only — stderr, non-event lines, the CLIs' failure events, and
the model's last words only when it used no tool (which is how Claude reports "Not logged in").

### 5.3 The model ladder *(mine, recently)*

`models.json` at the root lists each vendor's models cheapest-first. `ModelChoice` reads it at every
job:

| Job | Rung | Why |
|---|---|---|
| planning | top | one call, and it shapes every turn after it |
| implementing | middle, or the spec's own model | following a plan that exists is ordinary work |
| each repair round already spent | one rung up | another go from the model that just failed is not the fix |
| per-feature check | bottom | narrow and mechanical: is this claim in the code |
| the review | top | the judgement the pipeline turns on, made once |

The list is **discovered, not remembered**. `agent.catalog` reads the caches the CLIs keep for their
own model pickers — `~/.codex/models_cache.json` and `~/.claude/cache/model-catalog/*.json` — because
that is the only thing on the machine that knows what a particular subscription can reach. A
configured model the vendor no longer offers is dropped with a line saying so, rather than costing a
run a turn to discover. `forge models` shows where the ladder and the vendor disagree; `--refresh`
rewrites the file. Antigravity has no cache, so it is asked with `agy models`, which reaches the
network and is therefore kept out of the read path (`/api/models` is hit on every page load).

Only model lists are read. Never `auth.json`.

**Fable is excluded although the account can use it.** It is a preference, not a limitation, and it
is written in exactly one place with that said plainly. The reason is concrete: it sits at the top
of Claude's ladder, escalation reaches it after a couple of repair rounds, and it then refuses every
turn for want of usage credits — so the rounds that most needed to succeed were the ones that could
not run at all. One project logged seven consecutive refusals that way.

### 5.4 The gate

Runs the spec's acceptance commands in a clean process from the project folder, collects the
declared evidence globs, and records the result. Timeouts are per-spec. Commands that run far past
their own history are reported as slow, because a gate that silently takes three times as long is
usually telling you something.

### 5.5 Security

A layered check — secret scan, static analysis, dependency audit against OSV, and a live probe —
plus an AI pass. Constraints that are deliberate and should stay:

- The probe is **loopback only**.
- Secret values are **redacted**; only shapes and locations are reported.
- OSV receives **package names and versions only**. *(Added after the audit:)* the npm layer also
  runs `npm audit`, which sends the lockfile's whole dependency tree to the npm registry.

### 5.6 The cross-review, in two passes

The reviewer is a different vendor from the builder. Its *cheapest* model reads the code once per
feature the agent claims and answers `holds` / `unproven`; its *best* model then reviews the whole
change once, with the unproven claims in front of it as leads, stated as unconfirmed — a weak
model's miss must not arrive as a fact. One refusal stops the cheap pass rather than spending a call
per feature to be refused again; the pass is capped at twelve claims.

Every verdict is kept, including the ones that held and the ones nobody could check. That last
distinction is the point: a claim nobody looked at used to read exactly like a claim that was fine,
which is the same failure as treating an unavailable reviewer as a clean one.

### 5.7 Deploy and marketing

Both run **only** when a person clicks the button, and the dashboard shows the exact command before
anything runs. Forge never logs in, never installs, never spends money. Marketing claims must cite
approved features.

### 5.8 What a person reads and hears

- **Reports** — an index and a page per project, each section its own class.
- **Walkthrough** *(mine)* — the "Read it to me" button. A spoken paragraph assembled from the run's
  own files, in the order a listener needs: verdict first, then how it went, what it claims to do,
  what is **not** proven, what the reviewer said, what security found, what is waiting on you. No
  model is called, so it costs no quota and cannot describe a project as anything other than what is
  on disk. The failure it is built against is the comfortable summary — a parked or unreviewed
  project narrated as though it were fine.
- **Code tour** *(mine)* — `forge tour`. Walks a Maven parent's modules in the order the parent
  lists them, parses each file with the JDK's own compiler parser, and explains each class a line at
  a time. Output is a self-playing HTML page, a `.wav` per scene, and an mp4 when ffmpeg is present.
  Ends with the walkthrough when the toured code is a run Forge built.
- **`explain.bat`** *(mine)* — reads Forge's own docs aloud. Needs no jar, no build and no editor,
  which is the point: the day you most want to be told how something fits together is the day it
  will not compile.

The wording rule in `tour.say` is **say something or say nothing**. A line with no honest reading
comes back empty and is left out. Adding a fallback clause for the leftovers would make every line
narratable and the tour unlistenable; the empty string is the feature.

### 5.9 Jobs, the queue, and stopping safely

`JobRunner` is a facade over a worker pool, a queue, start rules, and per-button job classes.
At most one Claude call and one Codex call run at a time, however many projects are in flight.

**Stopping is the part with the most thought in it.** Killing Forge mid-turn orphans whatever the
agent has written but not committed — that is not hypothetical; a project here lost a handoff
exactly that way and parked complaining about a field the turn never got as far as writing.

`Shutdown.quiesce` stops taking new work and waits for `turnInFlight` to clear. It waits for the
**turn**, not the project: a project may run for hours, but the moment between two turns is
perfectly safe, because the state file is written after every transition and the next start picks up
where this left off. If the wait runs out it says what is still working and **leaves it alone** —
deciding to kill something is a judgement for the person at the keyboard.

"Busy" is broader than `turnInFlight` alone: a cross-review, a security check and a planning turn are
all agent calls that `turnInFlight` does not cover, and planning is the most expensive call the
project will make.

*(Corrected after the audit.)* There was a gap: the orchestrator's loop never looked at the drain
flag, and `quiesce` exited half a second after it found nothing busy. A gate that failed in that
window started a repair turn, and the exit's shutdown hook killed it — the lost-handoff incident this
section is about. The loop now stops between steps while draining, and a pooled project counts as
busy in any phase whose step calls an agent, not only once `turnInFlight` is written.

*(mine)* The dashboard now has **Finish and stop**, which calls the same `/api/quit` that
`quiesce.ps1` and `verify.bat` use — one shutdown path, not two. It asks first, names what it is
waiting for, and reports three outcomes distinctly: stopped, still-running-and-nothing-killed, or
the process actually gone (detected by the poll starting to fail, so the page says so instead of
freezing on a snapshot of a world that has ended).

### 5.10 The dashboard

One file, `ui/index.html`, ~1,300 lines, served by Forge rather than being static so that Check and
Save can validate through the real loader. Polls `/api/jobs` every four seconds. Handlers stay thin:
every endpoint is a read or a write, with the logic in `jobs`.

## 6. Invariants that must not be traded away

These are house rules, and most of them exist because something went wrong once.

1. **Never use an API key**, or a credential or OAuth code someone pastes. Subscription CLIs as they
   are logged in.
2. **Never log in, install a tool, spend money, or run a remote installer** on anyone's behalf.
3. **Never deploy, publish or post** except when a person clicks the button for it.
4. **Never let an agent work outside its `runs/NNN/repo`.** *(Audit note: this is a rule for how
   Forge is designed, not something it enforces. Claude runs with permissions bypassed and no
   sandbox; transcripts show agents using `%TEMP%` routinely. See §15.)*
5. **Never treat an agent's own claim of success as done.** The gate decides.
6. **Never kill an agent mid-turn.**
7. **Never match a phrase against model prose** to classify an outcome (§5.2).
8. **Test against temporary directories or fixture roots, never the live `runs/`.**

## 7. How it is verified

`verify.bat` from the root: the Java tests, then the Playwright suite against the built jar, then it
redeploys the editor. On a failed build it restores `forge/last-good/forge.jar` so the editor keeps
running.

Current: **935 Java tests**, **51 Playwright tests**, all passing (measured 2026-10-17 by a full
`verify.bat` run; 743 and 29 on 2026-09-27, and 721 before that date's audit fixes). Instruction
coverage 84.0% across all modules, reported and deliberately not enforced:

```
 56%   forge-app       (cli about 8%: the command classes only wire and print; Args is tested)
 83%   forge-report
 85%   forge-security, forge-deploy, forge-core
 88%   forge-marketing, forge-pipeline
 93%   forge-review
```

Those are rounded from the aggregate JaCoCo report the same run wrote to
`forge/target/site/jacoco/`, which is where to check them rather than trusting this file.

*(History, so the figures here can be trusted: the first set written in this section — 83% overall,
forge-app 64%, cli 13% — did not match the JaCoCo report generated the same afternoon, which said
81.5%, 56% and about 8%. The audit corrected them. Every number in this section since has come from
a report on disk.)*

There is no coverage threshold and should not be one. The number is a symptom to read, not a target
to hit; the `cli` figure is low because those classes wire and print, which is the right shape for
them.

**Guard tests are revert-checked**: each is run against the code *before* its rule existed and shown
to fail with the expected violations. A guard nobody has seen fail is a guard nobody has tested.

## 8. Operating it

| Script | Does |
|---|---|
| `forge.bat` | the CLI: `run`, `ui`, `status`, `report`, `rank`, `prune`, `tour`, `models` |
| `forge-ui.bat` | starts the editor and opens a browser |
| `verify.bat` | tests + Playwright + redeploy |
| `quiesce.ps1` | asks Forge to finish and stop |
| `restart.ps1` | starts the editor detached (with `javaw`) |
| `redeploy.bat`, `register-task.ps1`, `explain.bat` | rebuild-and-restart, scheduled run, docs aloud |

**The rebuild dance matters.** The editor runs from `forge/target/forge.jar` and holds it open.
Never `package` or `clean` while it is running — overwriting the jar leaves a process that cannot
even answer `/api/quit`, so the ordinary recovery is itself blocked. Use `verify.bat`, or
`quiesce.ps1` → build → `restart.ps1`.

`forge/CLAUDE.md` carries a "traps that have each cost a session" list. Every entry there failed
*quietly* — reported success, or said nothing, while doing nothing or the wrong thing. It is the
highest-value page in the repository for anyone working on this.

---

# 9. Shortcuts I took

Listed because they are the places most likely to embarrass someone later.

1. **`tour.say` is heuristics, not semantics.** It is a pile of regexes over single lines. It has no
   model of types, scope or control flow. The "say nothing" rule keeps it from being *wrong* most of
   the time, but it is thin rather than clever, and unusual code gets described shallowly or not at
   all. I fixed two cases where it was confidently wrong (ternaries read as calls; wrapped lines read
   as whole statements) — I would assume there are more I have not seen.

2. **`MavenParent` parses `<modules>` with a regex, not an XML parser.** Profiles, property
   substitution, and commented-out blocks are not handled. A module inside a comment would still be
   toured. Adequate for well-formed poms, wrong for clever ones.

3. **`Activity`'s command naming uses hardcoded lists** — a set of interpreter names and a set of
   file extensions. A script run through an interpreter not on the list is shown raw; a file with an
   unlisted extension is not named.

4. **`agent.catalog` reads undocumented private files** belonging to two vendor CLIs. See §11, risk 1.
   This is the shortcut I am least comfortable with, and I took it because there is no supported API
   and the alternative — a hand-written list — is what caused the bug it replaced.

5. **The `TOOL_BROKEN` threshold of 3 is arbitrary.** The real incident logged 258 rejections. Three
   is "more than a hiccup" and nothing more principled than that.

6. **`ClaudeCatalog` ranks models by a hardcoded list** (`haiku` < `sonnet` < `opus`). The catalog
   says what exists, not what it costs. Anything unrecognised sorts last, which is the dangerous
   direction — an unknown new model would land at the *top* of the ladder and be reached by
   escalation. A reviewer should decide whether unknown should sort first instead.

7. **`AntigravityCatalog`'s ordering is a judgement** — flash before pro, version ascending, then
   low/medium/high. It replaced six hand-curated rungs with eleven derived ones. More rungs means
   finer complexity mapping but also more rungs for escalation to climb.

8. **The tour's music and frames are arithmetic and Java2D**, deliberately, so nothing is downloaded
   or licensed. The ceiling on both is correspondingly low.

9. **Speech is Windows-only** — `System.Speech` driven through PowerShell. There is no other
   implementation and no abstraction pretending there could be.

10. **`explain.ps1` duplicates in PowerShell what `tour` does in Java** (reading the docs, converting
    javadoc to prose). Deliberate — it must work when nothing compiles — but it is duplication, and
    the two can drift.

11. **The shutdown UI does not disable the other buttons while draining.** Start will be refused by
    the server and the banner explains why, but the page still offers actions that will bounce.

# 10. Assumptions

Things the design takes as given. Each is a place it would break if untrue.

1. **Windows, one machine, one user.** `.bat`/`.ps1` scripts, `javaw`, `System.Speech`, NTFS ACLs,
   loopback-only binding. There is no multi-user story and no attempt at one.
2. **The vendor CLIs are installed and logged in** with consumer subscriptions. Forge holds no
   credentials and cannot fix an authentication problem; it reports one.
3. **A clean exit means the turn finished** (§5.2), measured over 91 invocations but *assumed* to
   keep holding. A logged-out CLI that exits 0 would cost one repair round before the gate noticed —
   the cheap direction, chosen deliberately.
4. **`turnInFlight` in the state file is the truth** about whether killing would lose work. It is
   written before the call and cleared after the commit; anything that breaks that ordering breaks
   safe shutdown.
5. **Phase names are persisted**, so renaming a constant silently invalidates every existing
   `state.json`. New phases are appended.
6. **`runs/NNN/repo` are git repositories; the orchestrator root is not.** Forge's own source is not
   under version control here, which surprised me and is worth knowing before you go looking for
   history.
7. **`models.json` edits by a person win** over discovery, except that an unavailable model is
   pruned.
8. **The Codex `priority` field orders capability** (lower = more capable), so reversing it gives
   cheapest-first. *Inferred from the data, not documented.*
9. **The Claude catalog's `overflow` section is previous versions.** *Inferred.* If it ever means
   something else, the ladder silently omits models it should include.
10. **ffmpeg is optional.** Without it there is still a page and recordings.

# 11. Risks

Ordered by how much they would cost if they went wrong.

1. **The vendor cache formats are private and undocumented.** Any CLI update can change
   `models_cache.json` or the Claude model catalog without warning. Failure mode is *soft* — the
   reader returns empty, discovery finds nothing, and the hardcoded fallback ladder takes over — so
   it degrades rather than crashes. But it degrades **silently**, and the fallback is exactly the
   stale-list problem this replaced. `forge models` will show it; nothing will announce it.

2. **Agents are confined by the CLI's sandbox, and by almost nothing else.** *(Updated 2026-10-17;
   this entry previously said Codex was broken outright, which it no longer is.)*

   Forge sets `windows.sandbox` per invocation from `vendors.json`, currently `unelevated`, so Codex
   turns run normally. The machine's *global* `~/.codex/config.toml` is still `elevated`, and that
   still fails to provision — `helper_unknown_error: apply deny-read ACLs`, because applying
   deny-read ACLs needs an elevated parent process and Forge's is not. Verified both ways on
   2026-10-17: `codex sandbox -- cmd /c echo ok` fails, and the same command with
   `-c windows.sandbox=unelevated` succeeds. So a bare `codex` outside Forge is still broken here,
   and the per-invocation override works around that rather than fixing it.

   What the override costs is the part worth reviewing. `unelevated` is the mode without the
   deny-read layer, so an agent can read more of the disk than a working `elevated` sandbox would
   allow; writes stay restricted. *(Corrected after the audit:)* an earlier version of this entry
   called that a "second layer", implying Forge provided a first. It does not. Forge confines
   nothing beyond choosing the working directory, the gate runs whatever an agent writes into its
   tests with full permissions, and until the audit Forge's own `git commit` ran any hook an agent
   left in the repository. **Read agent code as running with the privileges of the user who started
   Forge.**

3. **Tests that pin exact wording are brittle by construction.** `LineSenseTest` asserts specific
   sentences; `MotionTest` asserts timing relationships. A deliberate rewording fails them, which is
   the intent — but it means a reviewer must read *why* a wording test failed rather than updating
   the expectation reflexively.

4. **The Playwright suite runs against a live fixture server.** A route stub that stops matching
   sends real requests to it. This bit me today: a shutdown test whose route handler never responded
   released the request on page close, quit the fixture server, and failed two unrelated tests. The
   tests now assert their stubs actually intercepted, but the class of failure remains.

5. **`forge tour --video` on a whole repository is untested at scale.** `forge-report` alone is 49
   chapters, 992 steps, ~76 minutes spoken. Recording runs at the speed of speech, so a full-repo
   video is hours of synthesis and tens of thousands of frames streamed through a pipe. I have only
   verified a 3.5-minute render end to end.

6. **`verify.bat` refuses to build while a turn is in flight.** Correct, and it means you cannot
   rebuild during long runs. On a busy day the only safe windows are between turns.

7. **The detached editor inherits handles from whatever started it.** `restart.ps1` launches it with
   `Start-Process -RedirectStandardOutput/-StandardError`, which on Windows PowerShell creates the
   process with inheritance enabled — so the editor also inherits *other* open handles belonging to
   the calling shell. Observed: a `verify.bat` run whose output was redirected to a file finished
   cleanly, its shells exited, and the wrapper never returned, because the editor held the log file
   open. Any automation that wraps `verify.bat` with output redirection will see the same hang, and
   it resolves only when the editor is next restarted. Harmless to Forge, confusing to anything
   scripting it.

8. **Escalation interacts badly with unavailable top rungs.** Fable proved this. Any model that is
   listed but cannot actually run will be reached precisely when the project is in trouble. The
   pruning in §5.3 addresses the known case; the pattern is worth watching for.

9. **Repair rounds and sessions are consumed by environmental failures** unless the outcome is
   classified as external. `TOOL_BROKEN` closes the case I found. Others may exist.

# 12. What I am unsure about

Honest uncertainty, not hedging. These are the things I would ask someone else to check.

1. **`TOOL_BROKEN` has never fired in anger.** I verified it against the recorded log and in unit
   tests; I have not seen a live run hit the broken sandbox and suspend correctly. If the next Codex
   turn burns a session instead of suspending, the threshold or the pattern is wrong.
   *(Audit answer: the detection was right — 240 of the 258 recorded rejections are on stderr lines —
   but what followed it was not. It would have suspended, been relaunched at once, and repeated
   without limit. Now fixed and covered by `WaitingForAPersonTest`. Still never seen live.)*

2. **Whether the tour is actually worth listening to.** I cannot listen to it. I can tell you the
   line lit up matches the line being spoken, that no markup reaches the synthesiser, and that the
   sentences are derived from the source — I cannot tell you whether an hour of it is bearable. The
   narration was described as robotic, and the engine is a large part of that, but so is hearing
   "takes X from Y" several hundred times.

3. **Whether eleven Antigravity rungs beat the six hand-curated ones.** I changed it because
   discovery is more honest than a list, not because I measured that the mapping got better.

4. **Whether the lo-fi bed at 30% gain is right under long narration.** Judged on a 3.5-minute
   sample.

5. **What `mxc` is.** It is a valid `windows.sandbox` value and it works, and I will not recommend a
   security setting I cannot explain.

6. **Whether unknown models should sort first or last** in the Claude ladder (§9.6). I chose last;
   I now think first is probably safer and did not change it because it is a judgement about
   escalation behaviour, not a bug. *(Audit answer: neither. The top rung is used by every planning
   turn and every review, not only escalation, and the bottom by every feature check. A ladder built
   from discovery now leaves an unranked model off entirely; `forge models` lists it as available
   and unused until a person places it in `models.json`.)*

7. **How much of the pre-existing design I have understood correctly.** I have read a lot of this
   code, but "read" is not "worked on". The descriptions in §5 of the gate, security layers and
   marketing kit are the thinnest, because I have touched them least.

# 13. Polluted state, as recorded on 2026-09-27

Not defects in the code — artefacts in the live data that would mislead you if you read the
dashboard as fact.

- **004 (Center Defense)** spent **about seven turns** (not nine sessions — its first sessions predate
  the breakage) on the broken Codex sandbox. The turns exited cleanly and wrote coherent summaries
  of having done nothing. Its recorded "diagnosis" of a Playwright video deadlock is disproven: the
  "repair" commit changed no source, and the same suite then passed 293 times in a row. The one
  176-minute gate that prompted it most likely slept through a machine standby (see §15).
  *(Added after the audit:)* 004 then spent three days re-running its gate every twelve minutes and
  throwing on its handoff's `overview`, which held a sentence rather than a path. Fixed; see §15.
- **005 (DOCX/PDF editor)** had **seven calls** refused by Fable before the ladder was corrected.
  *(Corrected:)* they did not consume repair rounds — each was a failed call that changed nothing,
  retried every ten minutes without charge, which is its own problem (§15).
- If either parks at its limit, that is an artefact, not a verdict, and reopening is the right call.

*(Updated 2026-10-17.)* Both have since moved on: **004 is `ACCEPTED`** and **005 is `SUSPENDED`**,
each showing 0 repair rounds and around 26 sessions. The section is kept as a record of how the
counters came to read the way they did, not as a description of the dashboard today — check the
state files before acting on any of it.

# 14. What I would look at first

If I had an hour to review this and wanted to find the real problems:

1. `Signals.classify` and `AgentOutcome` — the most consequential 60 lines here. Everything about
   budget and suspension turns on them, and the failure mode is silent.
2. `Shutdown` and `/api/quit` — the code that decides whether work is destroyed.
3. `agent.catalog` — newest, reads other people's private files, and fails soft.
4. The `Depends on:` lines against `LayersTest` — confirm the documentation-as-enforcement idea
   actually holds, by breaking one and watching it fail.
5. `forge/CLAUDE.md`'s traps list — the cheapest way to understand what has actually gone wrong here.

And the question I would put to the author, which I cannot answer myself: **is the 8-classes-per-
package cap earning its cost?** It has produced 83 packages for 341 classes. It reliably prevents
hub classes. It also means a lot of navigation, and a lot of `package-info.java` prose to keep
honest.

---

# 15. Audit follow-up (2026-09-27)

A second reviewer read these notes, audited the code against them, and fixed what could be proven.
Everything below was reproduced first — by a test that failed against the old code, or by the logs
in `runs/` — and each fix has a test that fails without it. The sources as they were beforehand are
in `.scratch/before-audit-fixes-2026-09-27.zip`, since Forge itself is not under version control.

## What was wrong, and what changed

1. **004 was stuck in a loop for three days.** Its handoff's `overview` held a sentence, "not
   verified: workspace access failed…". The gate resolved it as a path, and on Windows the colon
   threw — after every acceptance command had passed. A cool-off retried it every twelve minutes:
   293 full passes of its suite, and its report failed to write each time. `Gate` and
   `ApproachSection` now treat anything that is not a path inside the repository as a missing
   overview, with a reason.
2. **Refusals that no wait fixes were relaunched without limit.** A suspension with no resume time
   read as "ready now". `TOOL_BROKEN` and `NOT_AUTHENTICATED` never had one, and neither was
   recorded in the vendor ledger, so each was relaunched as soon as its slot freed. They never
   counted a session, so they never parked. For `TOOL_BROKEN` that meant back-to-back full agent
   turns. The same was true of planning and of reviews. Now both wait for a person
   (`pipeline.run.Suspension`), both hold the vendor in the ledger for fifteen minutes, and every
   other suspension carries a resume time.
3. **Failed calls that changed nothing retried for ever.** The javadoc said the session budget
   bounded them; nothing on that path spent a session. 005's Fable refusals were this. The third in
   a row now waits for a person (`pipeline.run.PersonHold`). So does the third time Forge itself
   throws on a project, which is what would have stopped item 1 after half an hour.
4. **Phrases were still matched against model prose on every failed call** (§5.2). Now
   `stream.CliWords`. Claude's `is_error` on a clean exit is classified too, so a logged-out CLI is
   reported as one.
5. **The shutdown could kill a turn as it started** (§5.9). Fixed in `Orchestrator` and `Shutdown`.
6. **Sleep counted against a command's time cap.** The 176-minute gate behind 004's "deadlock"
   ended at 08:56:57, when Windows logged a session transition. The monotonic clock ran on through
   standby, and the first check after waking killed a suite that had run for about a minute. That
   cost a repair round. `process.Deadline` now discounts any gap between checks longer than 30
   seconds, and says so in the transcript. The standby explanation is likely, not proven. The
   discount is right either way.
7. **Hardening, where it is cheap and verifiable.** Agents start without the vendors' API-key
   variables (`agent.vendor.SubscriptionOnly`). Before this, the no-API-key rule held only because
   none were set on this machine. Claude turns use `--strict-mcp-config`, so they load none of the
   person's MCP servers. Forge's git calls run with `core.hooksPath=NUL` and `core.fsmonitor=false`.
   A hook, or an fsmonitor command, planted in a run's repository was confirmed to execute during
   Forge's commit without them.
8. **Unranked models stay off auto-built ladders** (§12.6).
9. **Smaller things:**
   - `Signals`' orphaned VERIFIED note, and `AgentOutcome`'s blank lines and wrong javadoc;
   - `ARCHITECTURE.md`'s package count (69, not 79);
   - a duplicated `Depends on:` fragment in `pipeline.turn`;
   - `quiesce.ps1` calling `localhost` where Forge binds `127.0.0.1`;
   - the dashboard's quota banner, which never showed a hold with no stated reset;
   - the containment wording in `README.md`, `RunPaths` and the module notes.

## Follow-up (2026-09-30): blocking findings, and what you rule on

The person this is for asked for two things. Every blocking finding must be fixed, not argued.
And what they review should be kept as history, with anything that comes back shown again, unticked.

- **Why blocking findings kept coming back.** A finding sent back from the form reached the agent as
  one line — file and summary — with the reviewer's explanation dropped. The next review was a fresh
  read with no idea what had been sent back, so an unfixed defect returned under new wording. After
  the single automatic repair, every project went to DONE whatever the review said. Evidence at the
  time: 001's division rounding, 002's auto-bounds and 005's DOCX ordering had each been sent back,
  and each was in that week's reviews again.
- **The loop now** (`pipeline.run.ReviewLoop`):
  - Findings carry ids, shared with the review form.
  - The repair brief is mandatory. A dispute needs a test, and the reviewer decides it.
  - The handoff reports `review_findings` by id.
  - The next review rules on each (`fixed` / `still_present` / `withdrawn`) before raising anything
    new. What it does not settle is carried forward with its attempts and the reviewer's reason.
  - Two attempts per finding and four repair turns per loop, then the project is held for a person
    in `REVIEW_REPAIR`. The review form accepts a ruling in that state.
  - An unreadable reply to a recheck holds rather than finishing.
  - The dashboard's Review button now rechecks known findings instead of discarding them.
- **A bug found on the way:** a hold on a phase that would otherwise run did not stop the
  orchestrator's loop. It only halted on terminal or suspended phases, so a held project went
  straight round again. The first test run hung on exactly that. `Orchestrator.halted` now counts a
  hold.
- **The form and the history:**
  - Every submission is appended to `review-history.json` and shown as "Your past reviews".
  - Any item ruled on before — in the history, or in `decisions.json` from before it existed — shows
    unticked with a "came back" line. So does every blocking finding.
  - A submission with an unticked item is refused, in the browser and on the server. Notes are
    kept, and nothing is recorded as a review.
  - A finding sent back now carries the reviewer's explanation.
- **Also:** fourteen `package-info.java` files had duplicated or stale text after their `Depends on:`
  list, invisible to `LayersTest`, which stops at the list's period. Removed.

## Follow-up (2026-10-01): Codex 0.159.3

Codex was updated from 0.157.1 to 0.159.3. Everything here was checked against the installed CLI:
its help text, `codex doctor`, `codex sandbox`, `codex mcp list` (none of which call a model), and
seven small `codex exec` calls, whose output is the source of the new test fixtures.

- **A resumed session no longer keeps its sandbox.** `CodexArgs` relied on it carrying over, which
  was true on 0.153.4. On 0.159.3 a resumed turn given no sandbox ran read-only and was refused a
  write in its own workspace. Every turn after a project's first is a resume, so no Codex project
  could have progressed. Resume takes no `-s`; `-c sandbox_mode=…` works and is now always passed.
  `LiveAgentTest.codexStillWritesWhenResumed` pins it against the real CLI.
- **The sandbox was still broken after the update.** `codex doctor` says: backend `elevated`,
  provisioning failed, `helper_unknown_error`. `windows.sandbox=unelevated` runs, writes inside the
  workspace and is denied outside it, on fresh and resumed turns alike. (The first test of that was
  invalid — `workspace-write` also allows the temp directory, and the probe was under `%TEMP%`.)
  Forge now passes the sandbox named in `vendors.json` on every call. The person's own
  `config.toml` is untouched.
- **Codex agents had been using the person's plugins.** This was the open question in "What was
  left alone" below, and the logs answer it: 2,223 plugin tool calls, 1,250 through computer use
  ("Find a file viewer") and 461 through the GitHub connector. With the sandbox broken, the agents
  were trying to reach the files by driving the desktop. Forge now passes `--disable` for the
  features `vendors.json` lists and switches off the MCP servers it names.
- **Stderr is not only the CLI's voice.** Codex's tool router logs a failed tool call and prints the
  tool's output under it. For a patch that did not apply — 404 records in the logs — that is the
  patch's own text, written by the model. Last round's `CliWords` took all of stderr as the CLI's.
  It and the broken-tool check now keep a record's first line and drop what follows it.
- **Output shapes.**
  - The events are unchanged: `thread.started`, `turn.started`, `item.*`, `turn.completed`,
    `error`, `turn.failed`.
  - An upstream error arrives as JSON inside the message string. `JsonlEvents.plain` unwraps it,
    so a card says "400: The '…' model is not supported…" instead of the envelope.
  - The dashboard's live line now recognises `mcp_tool_call`, `collab_tool_call`, `web_search` and
    warning items. It reads a file change's path from `changes[]`; it had been reading a field
    that does not exist.
- **Not verifiable until it happens:** how 0.159.3 words a usage limit. Every one in the logs is
  plain text, the last from 24 September. A 429 wrapped the way a 400 is would be recognised by its
  status (a test marked PROVISIONAL), but its reset time would not be parsed, so it would wait the
  default fifteen minutes.
- **New models.** The account now offers `gpt-6-luna` and `gpt-6-sol`. `models.json` was left as it
  was; `forge models` lists them as available and not used.

## Follow-up (2026-10-01): a button for blocking findings

The mandatory loop only starts at a review, so a project that finished before it existed sat at DONE
with its findings and no direct way in. **Fix Blocking Findings (N)** on the card
(`Reopening.fixBlocking`, `/api/jobs/fix`) puts the project straight into `REVIEW_REPAIR` with the
findings it already has, their attempts unspent, and starts it. It is offered on DONE and on a
project held after its findings' attempts ran out. It refuses a parked project, where the gate has
not passed and the repair brief would say it had, and an accepted one. The card's "before
accepting" line now counts blocking findings; with nothing else to report it used to say the review
had found nothing blocking.

Found while testing the button: **no card button had ever shown its answer.** The page looked up
the card's message area as `jobmsg-<id>`, an id nothing assigned, so "already running", "CLAUDE is
out of quota until…", "at capacity" and every other reply were dropped. It is now found through the
card, shown, and cleared after a minute. The Playwright test for the new button is what caught it.

Seen working on real projects since the earlier follow-ups:
- 005 went through a recheck on 30 September. The reviewer ruled on both carried findings and
  cleared them.
- 004 ran two real Codex turns on 1 October with the `unelevated` sandbox, then met a usage limit
  worded as before.

## Follow-up (2026-10-01): what was stopping the editor

The editor had been found dead three times with nothing in its log: once before this review
(`restart.ps1` records it), at about 20:50 on 27 September, and between 16:06 and 16:09 on
1 October, that time with 004's Codex turn. It was not exiting. Forge was killing it.

- **Cause.** After a command finishes, Forge kills whatever the command left running, and found it
  with `ProcessHandle.descendants()`. Asked of a process that has exited, that matches children by
  parent id alone. Windows reuses process ids. The editor is started detached, so its parent is a
  launcher that has already gone; any command that was later given the launcher's old id (a git
  call, a gate command, a test run in the same checkout) appeared to be the editor's parent, and
  the editor and everything under it were killed as that command's leftovers. Reproduced on
  purpose: an unrelated orphan was reported as a descendant after 6,356 spawns.
- **Fix.** `ProcessTree` (`forge-core`, `process`) walks the tree a level at a time and counts a
  process as a child only if it started no earlier than its parent. One whose start time cannot be
  read is left alone. `CliRunner`, `ProcessRegistry` and `LocalServer` all go through it, and a
  build rule (`noDescendantsCalls`) fails on any call to `descendants()`, tests included.
- **What it cost.** Each time, whatever turn was in flight. `turnInFlight` meant the work on disk
  was committed and gated at the next start, so nothing was lost except the turn's quota.
- **A second copy killed the first one's work.** Found by doing it. The editor was opened by hand
  at 16:29 while the build for the fix above was finishing, and `restart.ps1` started another at
  16:30. The second read `children.pids`, took the first one's running gate command for something
  "orphaned by a previous run", killed it nineteen passing tests into 004's e2e run, and only then
  found the port taken. The gate recorded exit 1 and 004 went to a repair turn it did not need. On
  its way out the second copy also emptied the record, so the first one's agent turn went
  unrecorded. Now the record's first line names the Forge that wrote it, nothing in it is swept
  while that Forge is alive, and a second Forge keeps no record rather than write over the
  first's (`ProcessRegistry.writtenByALiveForge`). `restart.ps1` refuses to start when the port
  already answers. This part is in the source and passes its tests; it was not deployed at the
  time of writing because 004's turn was in flight.
- **The launcher still killed.** `verify.bat`, `redeploy.bat` and the dashboard's Finish and stop
  all ask the editor to finish its turn and wait. `forge.bat ui`, which is what `forge-ui.bat`
  runs, did not: it found a Forge on the port and ran `taskkill /F`, turn or no turn. It now goes
  through `quiesce.ps1` like the others, leaves a working editor running if the wait runs out, and
  kills only when `FORGE_FORCE_STOP=1` is set. Each branch was run against a stand-in on a spare
  port; it has not yet been run against a real editor mid-turn.
- **No rebuild underneath a running Forge.** Every other `forge.bat` command (`status`, `run`,
  `report`) rebuilt the jar when the sources were newer, without checking whether an editor was
  running from it. The comment there said the editor's lock prevented that. It does not: the jar
  was replaced under a running editor at 16:29:32 on 1 October. `forge.bat` now uses the existing
  jar and says so when a Forge is running. Still open: with no jar at all it builds, even if
  another Maven build is already running in the same tree, which is how that editor came to be
  started in the middle of one.
- **Also here: a stale message on a resumed project.** 004's card said "CODEX is out of quota"
  while it was running again, which read as though it should have been parked. Suspended is the
  phase for that and it resumes by itself; the text was simply never cleared. `Recovery.resumeIfDue`
  now clears it.

## Follow-up (2026-10-01): a file to double-click

None of the six projects had anything a person could start without reading and typing. 002, 003
and 004 had a README with commands; 001, 005 and 006 had no instructions at all. They had all been
run, every gate, but only by their own tests: the Playwright suites start a server themselves, and
the Java projects have test classes that drive the program and write the feature clips. The one
party with no way in was the person.

- **The rule.** `house-rules.md`, "A way to run it": a `run.bat` in the repository root that
  installs and builds what is missing and starts the real program.
- **The gate.** `gate.RunFile` fails a project with no `run.bat`, or one in which no line starts
  anything. It is checked after the acceptance commands pass, like evidence and the overview
  class, and it is never executed.
- **Projects under way.** `brief.RunFileBrief` adds a short note to any turn's brief while the
  file is missing. Without it a project begun before the rule would learn of it by failing a gate,
  at the cost of a repair round and a session.
- **Not covered.** Nothing checks that a `run.bat` works. Projects already DONE (001, 002, 005)
  have none and will not get one unless they are sent back for another reason.

## Follow-up (2026-10-02): Play, and a league table

- **Seen working.** 004 resumed at 21:04 on 1 October and added a `run.bat` in its first turn
  back (commit `670cd16`), before any gate had failed on it: the gate log has no run-file failure.
  The file checks the Node version, installs and builds only what is missing, starts the preview
  server on 127.0.0.1 and pauses on any failure. The project went on to DONE. The agent log does
  not record the brief, so whether it was the note or the refreshed rules that told it is not
  shown; nobody has yet double-clicked the file.
- **Play** (`jobs.button.Play`, `/api/jobs/play`). A button on any card whose project has a
  `run.bat`; it opens the file in a console window of its own. Not a job: no worker, no lock, and
  not recorded as something Forge started, so nothing tidies it away. Refused while the project
  is running.
- **What Play found.** The first version opened a window that said "'run.bat' is not recognized".
  A hardened shell sets `NoDefaultCurrentDirectoryInExePath`, which stops `cmd` running a file
  from the current folder by its bare name, and an editor restarted from such a shell inherits
  it — and passes it to every gate command. A project whose acceptance command was `mvnw test`
  would have passed or failed depending on who had started the editor. `restart.ps1` now removes
  the variable, and Play names the file as `.\run.bat` and removes it too.
- **League** (`status.league`, `/api/league`). Vendors ranked from `state.json` and `review.json`,
  recomputed on each read and stored nowhere. Scoring is in `Tally`. It is deliberately read by
  nothing but the dashboard: it does not feed assignment, and no brief mentions it.
- **Its limits.** A review is overwritten by the next one, so a reviewer's record is its latest
  review of each project. With six projects the ordering is not evidence of much; the counts
  beside the points are the part to read.

## Follow-up (2026-10-02): Quick review, and Polish

- **Quick review** (`/review?id=NNN`, `report.deck`, `ui/review.html`). The report's form asked for
  8 to 20 rulings on one page, in the words of whoever would fix each thing. The quick review deals
  the same items one at a time. `Deck` sorts them: security findings, blocking findings and
  anything that came back must each be answered alone, with nothing chosen; unanswered features are
  shown as their clips; the rest is one list that starts as fine. It shares the form's ids, its
  came-back line and its blocked notice, and posts to the same `/api/jobs/decide`, so nothing
  about what a ruling means lives in the new page.
- **Plain words.** `Finding` gained `plain` and `stakes`, and `ReviewBrief` asks the reviewer for
  both. The builder's handoff is asked to word `not_verified` for a person who has not read the
  code. Neither costs a call. Reviews already on disk have no plain line and show their summary.
- **Stricter than the form in one place.** The form starts a security finding ticked "Fine as it
  is". The quick review does not: it is in the pile that must be answered.
- **Polish**, first version (`brief.PolishBrief`). Sets the project's feedback to a request about
  its looks and sends it to CONTINUING, exactly as a "needs work" ruling does, so the gate,
  security and review follow. DONE only. Replaced the same day by the two-step version below.
- **Not covered.** No reviewer has yet written a `plain` line, so how well it reads is unknown.

## Follow-up (2026-10-02): choosing a design before it is built

Polish was one press that told an agent to "improve the graphics", with the look left to it. It is
now two presses, and the project is untouched until the second.

- **Designs** (`design`, in `forge-marketing`). `DesignStudio.ask` asks every installed vendor
  for two looks. Each vendor works in `runs/NNN/design/<VENDOR>/`, beside the repository, with a
  capped copy of the source and screenshots (`References`), and writes still, self-contained
  pages. `DesignCheck` keeps only plain `option-N.html` files of a sane size that load nothing
  from the network; anything dropped is listed with its reason. The board (`board.json`) is saved
  after each vendor, so the page shows options as they arrive.
- **The choice** (`jobs.polish.Polish`, `/polish`, `ui/polish.html`). The picked page is copied
  into the repository as `artifacts/polish-design.html` and the request names it, as a picture to
  match and not code to paste. Polish left `Reopening`, which was at its package's class cap.
- **The report** (`report.outward.PolishSection`). The chosen design, the picture before and the
  picture after, side by side. It says the screenshots are the agent's own and unchecked, and
  flags an "after" older than its "before".
- **Why every vendor.** Three ideas from one model are closer together than they look. It costs
  one call per vendor, on a click, like every other button that spends quota.
- **Not covered.** No vendor has yet been asked for a real design, so whether the mock-ups
  resemble the program is unknown, and so is whether a builder matches one. A mock-up is a still
  of the main screen only. Nothing checks that the result looks like the choice: that is what the
  three pictures in the report are for.

## Follow-up (2026-10-03): an audit of this session's own additions

Nineteen findings, all fixed. The ones that could lose work or mislead:

- **The Polish page could stick.** A request for designs saved its board as "asking" first, and an
  interrupted request left it so; the page then spun for ever with every button disabled. A stale
  "asking" with nothing running is now reported as interrupted (`Polish.view`), a stopped request
  marks itself so and refuses late writes from vendor threads (`DesignRound`), and the page trusts
  what is running rather than the board.
- **A stop killed button jobs mid-call.** `Shutdown` counted only projects whose phase calls an
  agent; a design request, marketing kit, rescan or deploy runs on a DONE or ACCEPTED project and
  went straight through. Button jobs are now named in the pool and waited for whole.
- **A `run.bat` in the ANSI code page failed the gate.** Read as UTF-8, one em dash in a comment
  threw, and the gate said every line was a comment. It is read byte for byte now, and a
  byte-order mark is ignored. The note in the brief asks the gate's own question.
- **Mock-ups could reach the network.** `srcset` and a meta refresh passed the check. Design pages
  are now served with a policy that blocks every load, and the check catches both.
- **`quiesce.ps1` waited for every Forge.** With a scheduled run or the test server alive it timed
  out after the editor had gone, and `forge-ui.bat` said the editor had been left running. It now
  waits for the process on the port, and exit 3 means "stopped, but another Forge holds the jar";
  each launcher restarts the editor unchanged in that case.

And the rest: the league counted a reopened project as a clean gate (a lifetime
`repairRoundsEver` now); two security findings with one title shared a location on Quick Review;
design requests went one vendor at a time (now in parallel) and called vendors the ledger knew
were closed (now skipped); designers were given test inputs as screenshots (now `artifacts/` and
the newest evidence); `restart.ps1` succeeded when a non-Forge program held the port; asking for
new designs erased the record of what was built (now `polish.json`); Quick Review was not offered
on a project held for you; the Polish page rebuilt every project's status to read one title;
`JobRequest` carried Polish's fields (now `PolishRequest`); dead code and unused fields; a
positional constructor; and `ARCHITECTURE.md`. Writing that last one turned up a twentieth: the
prompt package imported the design package from two layers up, against the documented rule, and
the request text moved to `jobs.polish`.

## Follow-up (2026-10-03): a reset time on the hour

Claude refused at 11:30 AM with "You've hit your session limit · resets 4pm (America/New_York)". The
dashboard said Claude was back at 11:45 AM. The reset pattern required minutes, as in "resets
9:40pm", so "4pm" was not read; the ledger stored no reset time and Forge fell back to its
fifteen-minute guess. With the queue moving, that is a refused call and a suspension every
fifteen minutes until the real reset. `Signals` now reads a time with or without minutes, from
Claude and from Codex, and reads it in the zone Claude names rather than the machine's own; a name
that is not a zone falls back to the machine's without losing the time. Tested with the message
verbatim.

## Follow-up (2026-10-04): a table and one project, or a list on a phone

The dashboard was a stack of cards, every one showing every button it had — up to sixteen on a
finished project — and five projects stuck behind the same Codex window said so five times. It is
now a table grouped by what each project is waiting for, with the chosen project's details beside
it: what it needs from you in one sentence, with those buttons directly under it, then the five
stops it has made and what each found, then the rest of the buttons grouped under Look, Check and
Improve and ship. A closed vendor is one notice at the top, saying how many projects wait on it.
Below 1200px the same page is one ranked list whose lines open their details in place, with
filters for the groupings; it is meant for a phone, which still cannot reach the editor.

Each project is still one element, `#job-<id>`, holding its line and its details, laid out with
`display: contents` and CSS `order`. A narrow window and a wide one differ only in the stylesheet,
so nothing is moved between containers and nothing is rebuilt under the pointer, which was the
reason the cards were updated in place to begin with. The dark colours are now the only ones.

The Playwright tests that pressed buttons on several cards at once now pick each project's line
first, since only one project's details are on screen; three tests were added, for the grouping
and the details beside the table, the list on a phone, and its filters.

## Follow-up (2026-10-04): one clip, two names, and no way to say so

003 parked on "recordings: 1 feature(s) unfilmed" after 23 sessions. The person had approved
"Garage customization"; the agent later renamed it "Garage tuning", and that was approved too. One
feature, two approvals, two clips owed, one test filming it. Told which name was missing, the agent
renamed the clip from the other each session, so every gate found exactly one missing, a different
one each time, until the session budget ran out. Nothing in the dashboard let the person say they
were the same feature. The report's form could decline the old name, but the row was folded under
"all 10 ruled on", and the form would not submit without ruling the blocking finding too. Run
Another Session would then have briefed the agent with the old failure, and with 23 of 18 sessions
used, one failed gate parks it again.

Three changes:

- **The dashboard asks.** A project whose last gate failed on clips lists each missing one in its
  details (`StatusReview.unfilmed`, built by `MissingClips.of`). It says when the agent no longer
  lists that feature, and offers *Same Feature* as a filmed one (the likeliest, by shared words,
  preselected), *Not Worth a Clip*, or *Still Want It*. Each is one ruling through
  `/api/jobs/feature` (`Submissions.ruleFeature`), which saves it and starts nothing.
- **Check Again.** `/api/jobs/recheck` (`Reopening.recheck`) sends a project parked on its gate
  back to GATING with `gateOnly` set: the gate runs, no turn does. A pass carries on through the
  security check and the review; a failure parks it again, saying so. No session is spent, so the
  session budget does not stop it.
- **The loop is stopped where it starts.** A failed recordings check now lists every owed clip,
  filmed or missing, under the exact name it is owed by, and tells the agent not to rename one to
  satisfy another. `GateStep` remembers which clips the last gate owed and found missing; a gate
  that finds one made and another lost since (`MissingClips.moved`) parks the project for a person
  instead of starting another session.

## Follow-up (2026-10-04): Suggest Updated Design, a lighter review for it, and answers you can see

Reported: "I hit Still Want It and nothing happened." It had worked: the ruling was saved, and the
only sign was a line of small grey text, under a question that looked exactly as unanswered as
before. Asked in the same sitting: a design button on every project, and no full cross-review for a
change that is only visual.

- **Answers you can see.** A reply under the project's buttons is now full-strength text in a box.
  "Still Want It" is recorded as answered (`RunState.keptClips`, cleared by the next gate) and the
  question collapses to "still wanted: Run Another Session builds it and films it", with a way to
  change the answer. "The agent no longer lists it" now says "renamed or removed": 003's dealer car
  had been deleted, not renamed.
- **A note on a missing clip.** Each clip question has a box. Typed beside Still Want It, it goes
  to the next session as the person's request (`userFeedback`), parked project or not, and is marked
  heard so nothing delivers it twice. The report's form saved such notes on a parked project and
  sent them nowhere.
- **Suggest Updated Design** replaces Polish, on every project that has run. Asking for designs
  changes nothing in the project, so it is refused only while a session is working on it or before
  it has run. Building the pick depends on the phase: DONE builds it now, as before; ACCEPTED adds
  an enhancement, `NNN.1 "… visual update"`, with the design as its brief and the parent's own
  acceptance commands (`DesignEnhancement`), so the acceptance stands; anything else keeps the pick
  (`DesignStudio.pick`), puts nothing in the repository, and the dashboard says so once the project
  finishes (`StatusOutward.designKept`).
- **A polish is reviewed by what it changed.** Building a pick records the commit it started from
  (`polishBase`). At the review, `PolishScope` lists what changed since: stylesheets, images, fonts
  and documents only, and it is not cross-reviewed again; code, and the reviewer's cheapest model is
  asked one question (`ScopeCheck`) — did this change anything besides appearance — with the full
  review on a yes or no answer. The gate and the security check run as always, never skipped on a
  failed gate, and Review Again always reviews in full. The details say which happened.
- **A reset days away names its day.** "CODEX is out of quota until 5:39 PM" was shown on the Sunday
  for a reset on the Friday. `StartRules.local` adds the day whenever it is not today.

## Follow-up (2026-10-05): a spec edited under a running project now reaches its agent

003's spec was edited to allow a rewarded ad that gave a car from a dealer, and an ads setting was
added. The ads setting reached the agent, through `AGENTS.md`, which is rewritten every turn. The
edit to the prose did not: `SPEC.md` was written once, when the project started, on the reasoning
that rewriting the contract under a build would move "done". Sessions 25 to 30 read the September
copy, which still said "Do not add recovery currencies or reward systems", refused to rebuild the
dealer, and asked the person to decline it, which they then did. The reviewer had been reading the
current spec all along, so the agent and the reviewer were working to different contracts.

The reasoning protected the project from the one person entitled to change it. Now, before every
turn, `Workspace.refreshSpec` compares the repository's copy with the spec as it stands. On a
difference it rewrites the copy in its own commit, and the brief opens with "The spec has changed":
the diff, and that the new version wins over earlier decisions. The notice is kept on the state
(`specChange`) until a turn actually reaches a model, because the copy is already current by the
next turn and a refused one would otherwise have been the only notice. Run against 003's copy, the
diff holds the dealer exception and two earlier edits nobody had told the agent about either: the
browser test now asking for `race.webm`, and `race.webm` in the evidence list.

## Follow-up (2026-10-05): a design kept for later keeps its note

The design page has one box, "Anything you particularly want?", sent with whichever button is
pressed. On a finished project Build delivers it to the session. On an unfinished one the button is
Keep This One for Later, and `Polish.keep` saved the pick and dropped the box without a word: the
reply said "kept", and when the project finished and the design was built, the note was gone. It
came up on 004 Center Defense, where the change wanted was "clowns seen from above instead of the x
and the !".

The board now stores the note with the pick (`DesignBoard.chosenNote`). The page shows it beside the
kept design and puts it back in the box when the project is finished, so building it sends the same
words. The box stays what is sent: the note can be changed or cleared before pressing Build, and the
server does not add it behind the page's back. A new pick replaces the note; boards written before
this have none, and load as before.

## Follow-up (2026-10-06): more like one design, and a vendor's hold let go on a person's word

**Asking again lost the design that was liked.** On 003 the person liked "Sticker Garage" and asked
again: "I like the Sticker Garage (bright cartoon pit-lane), could you generate additional designs
with different car looks. The rest looks fine." Asking empties the design folder and starts from
nothing, and the note was the only thing carried into the brief. The designer could not see the page
the note named, drew two new screens, and changed everything that had been liked. The liked page was
deleted with the board; it was recovered from the Claude log, where the designer's own `Write` of it
was recorded in full.

Each design now has **More Like This**. It needs a note (what to change is the whole request) and a
design still on the board. The page is read before the folder is cleared, kept as `design/liked.html`,
put first on the new board as `LIKED` where it can still be built, and copied into every designer's
`reference/`. The brief says to start from it and keep its layout, background, panels, type, colours,
buttons and text, varying only what was asked. A round where nobody draws anything is still `none`,
and the page shows the liked design under that message rather than replacing it.

**A vendor's hold could not be let go.** The quota ledger lets a hold go only when a call completes,
and nothing calls a vendor that is held: not the queue, not the reviewer order, and not the design
studio, which skips it. Codex had recorded a reset on Friday 9 October; the person reset its limit on
the Tuesday and Forge went on refusing to ask it. The quota bar now has **Try Codex Now**
(`POST /api/quota/retry?vendor=`), which clears the hold. Being wrong costs one refused call, which
spends no session and records the hold again.

## Follow-up (2026-10-07): a polish built to a picture is compared with the picture

003 was polished to "Hard-Edged Die-Cast", a design whose point was its detailed cars: shaded
panels, two-tone glass, decals, chunky gold wheels. The builder matched the palette, panels and
buttons and drew the cars as flat silhouettes, and dropped the ribbon, the "YOURS" tag and the road.
`PolishScope` asked the cheapest model whether the change was appearance-only, it said yes, and the
project went back to the person unreviewed. Nobody compared the result with the picture until the
person ran the game. The polish brief contributed: it called the picture "where to get to, not code
to paste", which reads as licence to approximate, and a car that changes colour with the paint
picker is easier to redraw simply than to recolour.

A polish built to a picked design now records the design on the state (`polishDesign`) and skips
`PolishScope`: it is always reviewed in full, and `ReviewBrief` adds "The look it was asked for",
which has the reviewer open `artifacts/polish-design.html` and `artifacts/polish-after.png` and raise
each thing left out or simplified as blocking, cosmetic. The marker is kept through the repair loop
and cleared once nothing is standing. The polish brief now says the artwork is part of the look, to
be drawn at the picture's detail and made recolourable rather than simplified, and that it will be
compared. The review module may not import `DesignStudio`, so the two paths are repeated in
`ReviewBrief` and `OrchestratorTest` holds them equal. A polish with the look left to the agent is
scoped as before.

## Follow-up (2026-10-10): a started project can be moved to another model

`Recovery.honourExplicitAssignment` ignored a changed `assign:` once a project had had a session,
to keep the conversation with its vendor. That kept 004 with Codex, whose sandbox here cannot
launch a browser: it never saw its browser tests fail, wrote documentation instead, and parked on
its session limit twice. Only a person edits `assign:`, so it is now honoured at any point. On a
started project it is a takeover: both conversation ids are dropped, `sessionsOnConversation` goes
to 0, and a `takeover` note is put first in the next brief ("You are taking this project over from
CODEX ... Run every acceptance command yourself"). It goes through its own field rather than the
request channel because requests reach only continue turns, and a takeover can land on a repair.
Like `specChange` it is kept until a turn reaches a model. `OrchestratorTest` pins both: the old
"not moved mid-build" test was turned round, and a takeover onto a repair turn is checked end to end.

## Follow-up (2026-10-10): rechecks that could not see the fix, and a loop budget that never reset

A sweep of the stuck projects found two Forge faults behind most of them.

**Rechecks judged real fixes "still present".** A recheck asked the reviewer to decide "from the
diff" whether each previous finding was fixed, and the diff runs from the start of the project and
stops at 60k characters. On a large project the file the fix went into is past the cut. The
reviewer, told not to open files, said so — "DocxParagraphEdit is not included in the supplied
diff", "the supplied diff omits cameras.py", "the supplied diff contains no FieldUsageAnalyzer
changes" — and ruled the finding still present. 005 was held for a person over a fix that was in
place with two tests passing; 002 and 001 were on the same road. `ReviewRound` now carries the
current text of each file a previous finding names (30k per file, 90k in all), `ReviewBrief` shows
them under "Those files as they stand now", and the reviewer is told to decide from them.

**The review loop's four repair turns were counted across requests.** `reviewRepairs` was reset
only by Review Again and Fix Blocking Findings. A polish, a design or extra, a reopen, and changes
requested through the review form all inherited the count, so 003's Christmas theme was held "after
4 repair turns" on its first finding, having had none. Each of those now starts the count again.

Fixed by hand in the same sweep: 004's carnival restyle (built by Codex, which still cannot launch a
browser) moved the arena when the warning pill appeared and overflowed the start card at 320x568;
003's Christmas frost trim was anchored to the page instead of the tuning panel.

## Follow-up (2026-10-10): the idea generator Forge built, used by Forge

006, a passive-income idea generator Forge built, now feeds the wishlist. `ui.suggest.IdeaSource`
reads `idea-generator.json` beside the wishlist (project, main class, categories), runs
`java -cp runs/<project>/repo/target/classes <main> --count 1 --seed <random> --format json`
through `CliRunner` with a 30-second cap, in a temporary folder deleted afterwards (the generator
always writes `out.html`, and sometimes `out.json`, into its working directory, which must not be its
repository), and returns the first idea. `GET /api/ideas` tells the page whether one is configured,
and `POST /api/idea?category=&maxEffort=` produces one. The editor puts it in the description box
above Suggest, so the person still decides, Suggest drafts, and Save writes.

Nothing names 006 in code; without the config file the buttons are hidden. It runs on the JVM Forge
runs on (`ProcessHandle.current().info().command()`). An unbuilt project is reported, not built:
compiling a project's repository from the editor would be a side effect of pressing "New Idea".

## Follow-up (2026-10-11): a feature named with a whole request, and four held projects

**003 parked on a clip it had made.** A feature was added with the whole request as its name ("Add a
logo start screen, a short video showing two cars racing in a 3d rendering; After pressing ..."), so
the clip it owed was named after that sentence. Claude built the logo screen, tested it, and filmed it
as `feature-logo-start-screen.webm`; the gate found nothing under the long name. None of the clip
question's answers fit: Same Feature only offers approved features, and Not Worth a Clip declines the
feature. The approved entry in `features.json` was renamed to "Logo start screen", with the request
kept in its comment, and Check Again passed. The dashboard has no way to rename a feature; a short
name with the detail in the note avoids needing one.

**Fixed by hand, then sent back with Fix Blocking Findings** so the reviewer rules on each by id:

- 004: the inner chalk circle was `inset: 100px` of a ring that is 234px on a phone, about 34px
  across and under the avatar; both circles now keep the design's proportion. The typography finding
  was already fixed — both screenshots show bold sans-serif — and the next review should say so.
- 005: a line break pasted from Word (a vertical tab) landed invisible and untracked, and none could
  be typed. `LineBreakMarks` now shows and records breaks in inserted text, and Shift+Enter types one.
- 001: inline `PERFORM ... END-PERFORM` (new `Ast.PerformInline`, also in the generator-runtime copy),
  a trailing `ROUNDED` rejected rather than read as a paragraph, an unterminated literal rejected. The
  fourth finding, CALL preservation, was already fixed and tested; `FieldUsageAnalyzer.java` is 33k,
  past the 30k per-file excerpt, so the reviewer never saw the method. Expect it to need a person's
  ruling unless the excerpt is made to reach it.

## Follow-up (2026-10-11): rechecks see the code a finding is about, and long files are noted

**The excerpt is aimed.** A recheck's file over 30k characters was cut from the top, which lost
exactly what was asked about whenever it sat near the end. `review.FileExcerpt` now takes the code
names in the findings' own words (summary, detail, why it was still present), picks the one rarest in
the file, and centres a 30k window on where it is declared, skipping its Javadoc mentions and calls,
with "lines 1-14 left out" style markers at each cut. With no name to aim by it keeps the top as
before. On 001's `FieldUsageAnalyzer` it lands on `markCallArgumentReads`.

**Long files are noted.** `review.LongFiles` adds a note (stakes `upkeep`, never blocking) to every
review for each source file over 400 lines, tests and generated or minified output excluded. Counted,
so it costs no quota and says the same each round; the person can send it back from the review form.
`house-rules.md` now says so. Today it would note 001's `ProjectGenerator` (678), `FieldUsageAnalyzer`
(578) and `Parser` (561), and 003's `screens.ts` (640).

## Follow-up (2026-10-11): 004 on a desktop, and screenshots in notes

**004 broke in a desktop window.** `view-controls.css` still carried the original 800px desktop layout
above 700px wide (controls pinned top-right, 236px of masthead kept free), and the carnival restyle
had made the game a 390px poster at every size. Inside it the buttons stretched into tall pills and
the start card and arena were squeezed to half width. Nothing caught it because the spec named only
phones and landscape, so every test, screenshot and review picture was phone-sized. Fixed by hand
(`polish.css`: the poster keeps its phone layout above 700px), and the spec now asks for 1920x1080
and 1366x768 to be laid out correctly and checked in the browser tests; a session was started on it
(`max_sessions` 26 to 28, since all 26 were spent).

**Notes take pictures.** The person found that defect on their screen and had no way to show it.
`jobs.submit.Attachments` saves a PNG, JPEG, GIF or WebP (judged by its first bytes, up to 10 MB,
named by Forge) in the project's repository under `.forge-images/`, kept out of git through
`.git/info/exclude`; `ui/attach.js` handles paste, drop and an Add image button on any note box
marked `data-attach`, on the dashboard's clip notes, Quick Review, Suggest Updated Design and each
report's form. The note gets `[image: <path>]` in its text, so no brief, store or form had to
change; the house rules tell agents to open it. Designers work beside a copy of the project, so
`References` copies the folder into `reference/source/` and `DesignBrief` says where to look.

## Follow-up (2026-10-11): procedures moved to skills, without losing a rule

The house rules were 20.9k characters, carried through every turn of every project. Procedures moved
to skills in `skills/` (`record-feature`, `run-bat`, `performance-tests`, and a new
`check-viewports`); Ads and Deployment became sections Forge includes only when the spec has ads or a
deploy step. Every contract the gate enforces stayed, each with a pointer to its skill, and the
never-deploy-never-log-in rule stays outside the deploy section. A project without ads or deploying
now gets 15.9k (-24%), one with both 17.7k (-15%).

Order of work, as asked: `backups/house-rules.2026-10-11.md` (read-only, SHA-256 checked) first;
skills and their helpers written and tested; the slimmed rules drafted as a candidate; then
`backups/verify-migration.js` checked every heading, paragraph, bullet and code block of the backup
against the candidate and the skills — 90 still in the rules, 18 moved word for word, 0 missing —
and was itself checked by deleting a rule from a copy, which it reported. Only then was
`house-rules.md` replaced, and the check run again against the live file.

Where the CLIs look was read from the installed binaries, not assumed: Claude Code
`.claude/skills/`, Codex 0.160 and agy both `.agents/skills/` (Gemini 0.59 reads `.gemini/skills/`,
but is not one of Forge's vendors). `SkillInstall` copies each skill into both, replacing only those
Forge installed (a manifest), out of git. Helpers were run, not just written: the GIF writer and
terminal renderer produced clips Forge's own `Recording` check accepts (including a one-line output),
and `check-viewports` passed 004 as it is and flagged 004 as it was before the desktop fix (both
buttons 104x216, stretched, at laptop and desktop sizes only). It was tightened twice on the way: a
3:1 stretch threshold missed the real bug (1.7:1), and a page without a viewport meta tag hid its
overflow from phone emulation, so the missing tag is now reported itself. `HouseRulesTest` reads the
real files and fails if the rules name a missing skill, a marker is unknown or open, or a plain
project would lose a rule it needs. The gate's missing-clip message now names the skill.

001, later the same day, fixed by hand and sent back with Fix Blocking Findings (its 30th and last
allowed session): the lexer read any leading digit as a number, so `1-AMOUNT` became `1`, `-`,
`AMOUNT` (a COBOL word holding a letter is now a name; all-digit runs, level numbers and PIC clauses
unchanged; test `aFieldNameMayStartWithADigit`). And the five serious advisories waiting for a
ruling were all Jackson 2.18.8; two are fixed only on the 2.21 line, so it moved to 2.21.7, in its own
pom and in the pom template of the services it generates (the integration test builds and runs one).
One medium advisory is fixed only in Jackson 3 and was left. The AI review's unconfirmed note on
identifiers reaching generated Java and shell code unescaped was not acted on.

On 2026-10-12, 001 came back held on three new findings after four repair turns, and 004.1 on one.
For 001, two were fixed by hand: a period inside IF/EVALUATE/inline PERFORM was read past, so the
statements after it ran under the condition (now rejected, saying why; a period straight after
`EVALUATE X` stays tolerated because `examples/programs/DISCOUNT.cbl` relies on it and nothing can
land in the wrong branch), and `PROCEDURE DIVISION USING` could name a field that is not in LINKAGE,
or one twice. The third (P-scaled PICTUREs, exact COMP-1/COMP-2 storage) is scope: the spec asks for
"all forms" of numeric types, so each review finds another. The person chose to have it built, and
raised `max_sessions` from 30 (33 used) to 40. For 004.1 the reviewer blocked optional theme sound as
outside the appearance-only scope, but the person had approved "Theme-aware sound" as a feature; they
chose to keep it, so the spec now says so explicitly (off by default, remembered, nothing else
changes). They had also declined "Moonlit Haunting", the theme itself, by mistake; it is approved
again. Both were sent back with Fix Blocking Findings.

Later on 2026-10-12: 004.1 parked on one flaky e2e step (a tap read a moving enemy's box, then landed
beside it after a resize); the helper now re-aims up to three times and still requires the removal
(30/30 runs passed). Check Again then failed only on "Third look pack", approved by the person with no
third pack in the code; they chose to have one built, so its spec says so and `max_sessions` went 6
to 8. 002 parked on three clips owed under names the agent had since lengthened; ruled Same Feature
(Forge's own first suggestion each time), Check Again, and it reached DONE. 001's last finding and its
high security item were one bug: CALL never ticked the request budget, so a self-call fan-out ran
2^depth calls unchecked; every CALL is now a step, with a test that hangs without the fix.

The same work caught a bug in `Attachments`: `git rm --cached /.forge-images/` fails (a leading
slash is a path outside the repository), which was swallowed; both now pass the pattern without it.

On 2026-10-13, 003.1's "Reptile Attack" build did not match its design: the agent laid a honeycomb
over the old car art instead of redrawing the cars, and the review checked only that the pack was
applied. The person then gave a sheet of street-racing cars as the look wanted for every car, so all
nine were redrawn by hand (commit ba263a2 in runs/003/repo): one kit (`racerKit.ts`) builds each
model from its profile (`racerModels.ts`), with wide-body arches, a seeded splatter livery in the
paint and a per-paint accent, aero, LED lamps, number boards and deep-dish wheels. Unit 179/179 and
e2e 42/42 with CI=true. It changes the default look on purpose, so the look reference was set aside
as `runs/003/look-reference.before-street-racers.png` (what `LookCheck.forget` does for a replaced
look), and the next gate adopts the new one. The packs still draw over the new cars.

On 2026-10-14 the person wanted the cars exactly as painted, which drawn shapes cannot do, so they
generated side-view paintings of the nine models in the six paints (54 images, from
`Downloads\car images`). Each was cleaned of stray specks, trimmed, scaled to one length on one road
line and saved as `public/cars/<model>_<paint>.webp` (3.5 MB); `carImages.ts` loads them and holds
each model's wheels as measured, so the mechanic's custom rims still land on them (commit 078dd06).
The drawn cars stay as the stand-in until a picture loads, and in Reptile Attack, which redraws the
cars itself. Car canvases now render at the screen's pixel density. The default-look screenshot waits
for all 54 pictures (`data-cars-ready`), so it cannot catch a half-loaded page. Unit 179/179, e2e 42/42.

## Follow-up (2026-10-08): the comparison with the picture did not happen, and now cannot be skipped

The first polish reviewed under the rule above (003, "Hard-Edged Die-Cast") was not compared with
anything. Codex's review said "the design HTML and result screenshot were not included in the
supplied diff and were not inspected", filed it as a note, and the project went to DONE. The brief
opened with "do not run any commands — the complete diff is below and it is all you need", and the
request to open the two files came forty lines later; the reviewer obeyed the first. (The build
itself was good this time: the stronger polish brief produced the picture's detailed cars, ribbon,
tag, corners and road. It was found to be good by a person, which is the gap.)

The opening line now carries the exception when a design is owed a comparison, and the reviewer
must say `"compared_with_design": true`. A reply without it is treated like an unreadable one, so
the next reviewer is asked, and when nobody compares, `ReviewStep` holds the project for the person
instead of finishing it.

## Follow-up (2026-10-08): extras beside the look — themes, variants, animations

Asked for: holiday themes for Pink Slip, upgraded models of the cars a player could buy, and new
animations, "without losing the existing". Suggest Updated Design could only replace a look.

The page now has **Replace the look** or **Add an extra** (theme, variants, animation). The kind is
carried to the designers (`DesignBrief`: drawn on top of the current look, which stays; an
animation may use CSS `@keyframes`, the one exception to "a still picture") and kept on the board
(`DesignBoard.extra`), so designs drawn as a theme are built as a theme even from a page that did
not say. Building one sends `ExtraBrief` instead of the restyle request, through the polish's three
routes; an accepted project gets `DesignEnhancement.writeExtra`, titled after the pack. An extra
needs a picked design, whose name names the pack.

"Without losing the existing" is enforced, not asked for. The first extra makes the pack slot: the
current look becomes the default pack, and the suite saves `artifacts/look-default.png` on every
run. `LookCheck` adopts the first one from a passing gate as the reference, kept beside the
repository so enhancements share it, and fails later gates when more than 2% of pixels moved by
more than 40/255 in a channel, when the size changed, or when the screenshot stopped being saved.
A polish that replaces the look calls `LookCheck.forget`, and the next gate adopts the new one.

Known limit: the reference is adopted from the slot-making session's own gate. If that session
changed the default while moving it into a pack, the change becomes the reference. The review
compares the result with the picked design from the after-screenshot, and the before and after
pictures are in the report, but nothing compares the new default with the old one pixel by pixel.

## What was left alone, on purpose

- **The Codex sandbox setting.** `windows.sandbox = "elevated"` is in the person's own
  `~/.codex/config.toml`, which the Codex desktop app also uses. Changing it is theirs to decide.
  Until it is changed or Codex is shown to work by hand, 004's next Codex turn will be a
  `TOOL_BROKEN` one. It now costs one turn, then waits.
- **Codex's plugins and config.** Codex turns still inherit `~/.codex/config.toml`, including Gmail,
  GitHub (with create and merge set to approve) and computer use. `--ignore-user-config` would
  isolate them, but it also drops the sandbox and reasoning settings. Nobody has run a turn that
  way to see what that costs.
- **Real containment for Claude.** Nothing short of an OS-level sandbox would provide it. The docs
  now say so instead of claiming otherwise.
- **The Antigravity ladder.** Its top rung, `gemini-3.1-pro-high`, is the oldest generation on the
  list, above every 3.8 flash model. That is a judgement for the person, not a bug.
- **The 8-classes-per-package cap.** The reviewer's view: it is earning its cost. No file is over
  290 lines, there are no hubs, and the `Depends on:` lines are enforced and stayed honest. Prose
  nothing checks, like `ARCHITECTURE.md`'s count, is what drifted. None of the defects above were
  structural.

## Worth knowing

- The editor was found stopped at about 20:50 on 2026-09-27. There was no reboot, no sleep event,
  no crash file, and no shutdown line in its log after 17:00. `restart.ps1` records the same thing
  happening once before. The cause was found on 1 October; see "what was stopping the editor".
- Dashboard auto-run was turned off at 16:59 to stop item 1's loop, and is still off. 004 sits first
  in the queue.
