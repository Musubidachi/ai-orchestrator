# Forge

An unattended build pipeline that drives the **Claude Code** and **Codex** CLIs against a wishlist
of project specs. You write specs; it builds, verifies, cross-reviews and reports.

Both CLIs authenticate against their consumer subscriptions, so no API key is involved and no
metered API billing is incurred.

## The one idea

**Forge decides whether a project is done, not the agent.**

Every spec declares `acceptance` commands. Forge runs them itself, in a fresh process, against the
files on disk, and ignores whatever the agent claimed. This is not paranoia about honesty — it is
that self-reports are unreliable in a way that looks fine. Observed on this machine: Claude Code
reported `subtype: "success"` for a turn that did nothing at all, because the CLI was logged out.

A project with no acceptance commands is refused, because there would be nothing to check.

**Nominated evidence is required, not optional.** If a spec's `evidence` list names a screenshot or
a rendered page, a run that passes its tests while producing none of it fails the gate. Naming an
artefact is saying *this is how a human checks the work* — passing tests with nothing to look at
does not satisfy that. The repair prompt names the missing files.

**Every project owes a `run.bat`.** It sits in the project's repository root
(`runs\NNN\repo\run.bat`) and is what you double-click to try the thing: it installs and builds
whatever is missing, then starts the real program — the server and a browser for a web app, the
window for a desktop app, a worked example for a command-line tool. The gate fails a project that
has none, or one that only prints and pauses. It reads the file and never runs it, because a run
file opens a window and does not exit; whether it works is the one check that is yours. A project
begun before this rule is told about it in its next turn's brief, so it adds the file in a turn it
was having anyway rather than failing a gate to find out. A project that is already DONE gets one
only if it is sent back for something.

**Agents are asked what a passing gate does not prove**, not to argue that it does. The commands
can only check what the spec author thought to check, and that blind spot is the point: "the tests
cover parsing but nothing renders the HTML and looks at it" is worth more than any case for
correctness. It appears in the report directly under the verdict. A confident argument for code
that does not work is worse than no argument at all, which is why the handoff asks for the gaps
instead.

## One class that shows the shape of the whole thing

Every project must leave exactly one class that reads as its top-level flow: the steps in order,
each delegating to a named collaborator. It is what you read to decide whether you agree with the
approach, without opening the repository — Forge shows it in full in the project report, directly
under the verdict.

It has to be **real code on the executed path**, not a summary written beside it. A description
that cannot go stale is worth more than one that can.

Enforced in three layers, because no single one is enough:

| layer | catches |
|---|---|
| the gate | not writing one, naming a path that does not exist, or naming a stub |
| the report | shows the file in full, so you judge the approach yourself |
| the cross-review | whether it *actually* matches what the code does — raised as blocking if not |

The gate check is deliberately shallow: it verifies the named file exists and is not a stub, and
stops there. Whether a flow is a good one is a judgement, and a judgement is not something to fake
with a file-size threshold.

## Setup

```bash
npm i -g @anthropic-ai/claude-code    # then run `claude` once to log in
npm i -g @openai/codex                # then run `codex` once to log in
mvn -f forge/pom.xml package
```

Antigravity (`agy`) is optional and reviews rather than builds; without it the cross-review falls to
whichever of the other two did not build the project. **ffmpeg** is optional too, and only for
`forge tour --video` — without it you still get the page and the recordings.

**On a fresh clone there is no state.** `runs/`, `logs/`, `index.html` and the various `*.json` at
the root are all working data, excluded from the repository and rebuilt on first use. What you
clone is Forge itself plus `house-rules.md`, `skills/` and `backups/`. The specs in `wishlist/` are
excluded — they are your own ideas, and they are input to Forge rather than part of it — with one
exception: **`wishlist/TEMPLATE.md.txt` ships**, because `SpecLoader` refuses an incomplete spec and
a clone with nothing to copy has nowhere to start. Copy it to `wishlist/001-your-project.md` and
fill it in; it is saved as `.md.txt` so the loader ignores it until you do. `models.json` is
discovered from what your own subscriptions can reach (`forge models`), so there is deliberately no
copy of someone else's in the repository.

Codex needs **0.153.4 or newer**. Older builds reject the `ultra` reasoning effort that the current
desktop app writes into `~/.codex/config.toml`, and fail at config load before doing anything.
Forge's Codex command line and its reading of Codex's output were last checked against **0.159.3**
(2026-10-01).

## Use

Use `forge.bat`. It finds the jar, sets the root to its own directory, fixes console encoding, and
builds on first use — so it works from any directory:

```bat
forge-ui.bat            REM write specs, and open the browser for you
forge.bat run           REM build every eligible project
```

Then open `index.html`.

| command | |
|---|---|
| `ui` | edit the wishlist in a browser, on `localhost:7777` |
| `run` | advance every eligible project, then write the reports |
| `status` | one line per project |
| `report` | regenerate the HTML from state on disk |
| `rank` | renumber the wishlist: projects first, then improvements |
| `prune` | delete build output from runs finished over N days ago (default 7) |
| `tour` | narrate a Maven project's code, as a page and optionally an mp4 |
| `models` | what each vendor offers, and what the ladder uses |
| `build` | rebuild the jar (`forge.bat` only) |

### Changing Forge itself while it is running

The editor holds `forge.jar` open, so a rebuild has to stop it first — and stopping it during an
agent turn orphans the CLI it was waiting on, because Windows does not reap the child. Whatever
that turn had written but not yet had committed is lost with it. That has happened, and the project
parked on a complaint about a handoff the turn never got as far as writing.

So neither script kills anything:

```bat
redeploy.bat            REM stop when the turn ends, rebuild, start again
verify.bat              REM the same, with both test suites in between
```

Both refuse and say which project is working if an agent turn or a cross-review is in flight. That
is the intended answer: the moment between two turns is perfectly safe to stop in — the state file
is written after every transition — and it arrives on its own. Wait for it, or stop that project
from the dashboard first.

Under them, `quiesce.ps1` asks the running editor to finish up (`POST /api/quit`) and waits for that
process to exit, and `restart.ps1` starts it again and checks it actually answered. A stop waits for
an agent turn in flight and for anything a button started — a request for designs, a marketing
kit, a deploy — since those have no turn boundary to stop at. If the editor stops but another Forge
is still running from the jar (a scheduled `forge run`, say), nothing is rebuilt underneath it and
the editor is started again as it was. `restart.ps1` does nothing if an editor is already on the
port, and fails, saying what holds it, if something that is not Forge does.

`forge-ui.bat` and `forge ui` follow the same rule when an editor is already running: it is asked
to finish its turn and stop, the launcher waits (up to fifteen minutes), and only then is the new
one built and started. If it is still working when the wait runs out it is left running and
nothing new starts. `set FORGE_FORCE_STOP=1` first if you mean to kill it anyway, which is for an
editor that cannot answer. What is waited for is the agent turn in flight, not the whole project:
the project carries on from the same place when the new editor starts.

No `forge` command rebuilds the jar while a Forge is running from it. `forge status` in a second
window uses the jar that is there, and says so if the sources are newer.

#### Stopping Forge from the dashboard

The scripts above are for rebuilding. To stop Forge and leave it stopped, the dashboard has
**Finish and stop** in its header, which is the same courtesy in one press: it stops taking new
work, waits for any agent turn to reach its commit, and only then exits. Nothing is killed, and the
confirmation names the projects working at that moment so you know what is being waited for.

A bar across the top then says what it is waiting for, and the page says plainly when Forge has
gone rather than freezing and looking broken. If the wait runs out with a turn still in flight,
Forge stays up, goes back to taking work, and the bar says which project held it — press it again
to keep waiting, or stop that project from its card first. Pressing it repeatedly is safe for the
same reason: a Forge being asked to stop does not start a fresh turn between attempts.

Start it again with `forge-ui.bat`. There is no button for that, because nothing is left listening.

The root defaults to wherever `forge.bat` lives, but your own `--root` wins because Forge takes the
first occurrence of an option. That is how you keep the generated projects on another drive:

```bat
forge.bat run --root D:\forge
```

Running the jar directly works too, if you would rather not use the wrapper:

```bash
java -jar forge/target/forge.jar run --root .
```

### The dashboard

`forge ui` opens on **Projects**, in dark colours. The bar across the top shows each vendor as
*open* or *back at* the time its quota returns, and a closed vendor gets one notice saying how many
projects are waiting on it — said once, rather than on every project it is holding up.

On a wide screen the projects are a table, grouped by what each is waiting for: **Needs you**, then
**Working**, then one group per thing holding projects up (*Waiting for review · CODEX is out of
quota until 1:04 AM*), then queued, paused, not started and accepted. Each line shows the rank, the
stage, time worked, tokens and what is still open on it. Beside the table are the details of the
project you chose — on arrival, the first that needs you:

- **Your move**: what it needs from you in one sentence, with the buttons for it — the decision, the
  fix, another session — directly under it.
- The five stops it makes — build, gate, security, review, accepted — each saying what it found.
- The numbers, the agent's own plan and handoff, and — while a turn is running — the agent's latest
  action, read live out of its event stream. That line is the one that distinguishes working from
  stuck during a half-hour turn.
- Everything else, grouped: **Look** (read it to me, notes, the recording), **Check** (cross-review,
  security) and **Improve and ship** (Suggest Updated Design, Deploy, the marketing kit). A group with nothing to
  press is not shown.

On a phone, or any window narrower than 1200px, the same page becomes one list in rank order. A line
opens that project's details under itself, and tapping it again closes them. Filters with counts —
*all*, *needs you*, *waiting*, *working*, *accepted* — stand in for the table's headings. **Play**
says *on the PC* there, because the window it opens is on the machine Forge runs on. The editor still
answers only to this machine; reaching it from a phone needs a private route to it, which Forge does
not set up.

The details carry **Start** / **Stop** as a manual override on the queue. Start ignores any quota
wait, which is the point: you are saying you know better than the reset time the CLI last reported.
Stop kills the agent process, not just the waiting thread — otherwise the CLI would keep running
orphaned, still spending quota. Work the stopped turn had already written stays on disk and is
committed and gated on the next start rather than paid for twice.

A vendor held for quota is named in a bar at the top, with until when, and **Try Codex Now** (or
whichever it is). Press it once you have reset or topped up that vendor's limit: Forge lets the hold
go, and the next call finds out. A hold is otherwise let go only by a call that completes, and
nothing calls a held vendor, so a limit lifted early went unnoticed until the time the vendor first
gave. If it is still out, that call is refused without spending a session and the hold comes back.

**Stopping everything the projects left running.** `stop-projects.bat` in the repository root stops
every program running from a project folder (`runs\<id>\`): `run.bat` windows, preview servers,
game and desktop windows, a project's own Java or Python. It never touches Forge, nor any project
Forge is working on at that moment (it asks the editor), because killing an agent turn or a gate
orphans the turn or fails it. It lists what it found before stopping anything; `stop-projects.bat
/list` only lists. A browser tab a `run.bat` opened is not its child and stays open.

**Moving a project to another model.** Change its **Assign to** in the wishlist editor (the spec's
`assign:`) and save; the next start honours it, even for a project already under way. That is a
takeover: the new model starts a conversation of its own, and its first session is told whose work
it is continuing and that it must run every acceptance command itself rather than trust the
handoff. Use it when a project is stuck with a model that cannot finish it here — Codex, for one,
cannot launch a browser on this machine, so it never sees browser tests fail. A parked project also
needs room to work: raise its `max_sessions` and press **Run Another Session**. `auto` never moves a
project once it has started.

A finished project that still has blocking review findings on it carries **Fix Blocking Findings
(N)**. It sends them straight back to the agent that built the project — no review bought first, no
form to fill in — and from there it is the ordinary loop: each finding is fixed with a test or
disputed with one, the gate runs, and the reviewer rules on every finding by id. Each gets two
attempts; one still present after that leaves the project waiting for you, where the same button
reads **Try Blocking Findings Again**. It is not offered on a parked project, which is parked on
something else, or on one you have accepted.

A project parked because an approved feature has no clip asks about each one in its details. It
says when the agent no longer lists that feature — renamed or removed — and offers **Same Feature**
as one that was filmed (the likeliest preselected), **Not Worth a Clip**, or **Still Want It**. The
first two decline it, the first with a note saying which feature it is. **Still Want It** keeps it,
and the question then reads as answered; anything typed in the box above the buttons goes with it to
the next session as your request, parked project or not. None of the three starts anything, and
what each did is said under the buttons.
**Check Again**, on any project parked on its gate, then runs the acceptance commands and the checks
after them, and nothing else: no agent session is spent, so the session budget does not stop it. If
the gate passes, the project carries on to the security check and the review; if not, it parks again
saying what failed. Run Another Session would brief the agent with the old failure instead, and an
agent told a clip is missing films it — under the name a person has just taken off.

The gate's message lists every approved feature with the exact name its clip is owed under, filmed
or missing, and tells the agent not to rename one clip to satisfy another. A gate that finds a clip
that was missing now present, and one that was present now missing, parks the project for you rather
than spending another session: that is one feature approved under two names, and only you can say
which name stays. 003 spent twenty-three sessions moving one clip between "Report customization" and
"Report export" before this existed.

A project with a `run.bat` carries **▶ Play**, which opens that file in a console window of its
own, exactly as double-clicking it would. Forge does not watch or stop what it starts: closing the
window does. It is refused while the project is being worked on, because a build running under a
gate holds the files the gate is about to delete.

Under the table is the **League**: the vendors ranked by what the runs on disk say each did. A
vendor earns 3 for a project that passed the gate, 2 more once you accept it, 1 more if it needed
no repair round, and loses 2 for one that parked; as a reviewer it earns 1 for each finding that
then got fixed and loses 1 for each it withdrew. The counts are shown beside the points, because
with a handful of projects one parked project moves a vendor two places. The table is recomputed
from `state.json` and `review.json` each time and stored nowhere. **Nothing reads it but you:** it
does not choose a vendor, and no agent is shown it or told about it.

Stats per project: total elapsed, time in the current phase, repair rounds used, commits, disk
usage, blocking review findings, and — for a suspended job — the local time it resumes.

### Your review

Each project's report ends in **Your review**: one row per outstanding item — blocking findings,
what the gate does not prove, work the agent says remains — each ruled *Fine as it is* or *Needs
work*, with a note. Fine on everything accepts the project; anything marked Needs work goes back to
the agent, with the reviewer's own explanation when it is a finding, and starts a session.

Every submission is kept in **Your past reviews** below the form: when, each item, what you said,
your note, and what happened. Once submitted, a ruling stays there; it is never carried forward as
the answer to the same item next time. An item that **comes back** — listed again after you ruled
on it — appears in the form with nothing ticked and a line saying when you last ruled on it, and so
does every blocking finding. Submit refuses until each of those has a ruling.

**Quick Review**, beside Review & Decide in the project's details, is the same review dealt as cards. It rules
on the same items under the same ids and submits to the same place, so either page can be used for
any sitting and each shows what the other recorded.

- First, one at a time and with nothing chosen: security findings, blocking findings, and anything
  that has come back. *Fix it* or *Fine as it is* (← and → on the keyboard), with an optional note.
  There is no way past one except by answering it.
- Then each feature nobody has ruled on, as its clip: *Keep it*, *Change it* (which needs a note),
  *Drop it*, or decide later.
- Then everything that starts as fine — what the gate does not prove, and work the agent says
  remains — as one list to skim, with a *Fix it* on each.
- Last, the whole sitting read back, and one button: **Accept**, or **Send back N things**.

A reviewer's finding leads with a plain sentence saying what you would notice, and a stakes word —
cosmetic, wrong result, data loss, security, or upkeep. The reviewer is asked for both in the review
it already writes. A review written before that has neither, and its summary stands in. The
technical wording is under *Details* on the card. Nothing on the page times you or scores you.

**Screenshots in notes.** Any note that goes to an agent takes a picture: the notes in Your review
and Quick Review, the note on a missing clip, and the note on Suggest Updated Design. Paste one
(Ctrl+V straight from the Snipping Tool), drop an image file on the box, or press **Add image**
beside it. Forge saves it in the project's repository under `.forge-images/` (kept out of git, so
never in its history or review diff) and adds a line such as `[image: .forge-images/20261011-141802-1.png]`
to your note. Your words and the line go wherever the note goes, and the house rules tell the agent
to open the picture and reproduce what it shows before changing anything. A designer gets its own
copy beside the project it is drawing for. PNG, JPEG, GIF or WebP, up to 10 MB; anything else is
refused by its contents, whatever its name says.

### Suggest Updated Design

Every project that has run, and is not being worked on, carries **✨ Suggest Updated Design**, which
opens a page of its own. It works in two presses, and the project is not touched until the second.

1. **Ask the models for designs.** Every vendor that is installed is asked to draw two looks for
   the project's main screen, with an optional line from you about what you want. Each works in
   `runs/NNN/design/<VENDOR>/`, beside the repository and never in it, with a copy of the source
   and screenshots to read. What it draws is a still, self-contained page: no scripts, nothing
   from the network. A vendor that is out of quota, or draws something that cannot safely be
   shown, is listed with the reason and the others go ahead. The designs appear on the page as
   they arrive.

   **More Like This**, under each design, is for one you like but not all of it. Type what to
   change and press it: every designer is handed that page and told to keep its layout, colours,
   type and text and to vary only what you typed. The design stays on the new board, marked as the
   one you started from, where it can still be built. **Ask again** starts from nothing and
   replaces every design on the board.
2. **Build it**, which does what fits where the project is, and the page says which before you
   press it:
   - **Finished (DONE):** the design is copied into the project as `artifacts/polish-design.html`,
     and the project is sent back with a request, in your words, to make the program look like that
     picture. Most of the request is about what must not move: what the project does, its tests, the
     spec's non-goals.
   - **Accepted:** an enhancement is added and started, `NNN.1` *"… visual update"*, with the
     design as its brief and the project's own acceptance commands, so a restyle that breaks what
     you accepted is not accepted either. Your acceptance stands.
   - **Anything else** (still building, waiting, parked): the pick is **kept**, with anything you
     typed beside it, nothing is built, and nothing goes into the repository. Once the project
     finishes, its details on the dashboard remind you, and the same page builds it, with your note
     back in the box to change or send as it is.

You can also skip the designs and let the agent decide the look, on a finished or accepted project.

#### Extras: themes, variants and animations beside the look you have

Above the box on the same page is a choice: **Replace the look**, which is everything above, or
**Add an extra, keeping the look it has**. An extra is one of:

- **Theme**: a whole alternative look the program can switch to, such as a holiday version.
- **Variants**: other versions of one thing, such as upgraded tiers of one item, each addressable so the
  program can show whichever the player has.
- **Animation**: new motion, such as a burnout. These designs are allowed to move (CSS only), so
  you watch the animation before you pick it.

Say in the box what you want ("Halloween", "three upgraded versions of each item"). The designers
draw the extras on top of the current look, and the page marks them as extras. Building one adds
it as a pack, named after the design, through the same three routes: a session for a finished
project, an enhancement (`NNN.n "<title> — <name> theme"`) for an accepted one, kept for later
otherwise.

The first extra a project gets also makes the place packs go. The current look becomes the
default pack, and the project's own tests save a screenshot of it as
`artifacts/look-default.png` on every run. **From then on the default look is held still:** the
first gate that passes keeps that screenshot as a reference (beside the repository, so an
enhancement is held to the same one), and every gate after that fails if the default look has
changed by more than render noise. Replacing the look on purpose clears the reference, and the
next gate adopts the new one. How a player gets a pack (a menu, a date, a purchase) is up to the
spec; if it says nothing, the builder adds a simple picker. A shop that sells the upgraded tiers is a
feature like any other, so write it into the spec. An extra built to a design is reviewed against
that design, like any polish with a picked design.

The gate and the security check run after a polish as after any session, so one that breaks a test
is caught. A polish built to a design you picked is always cross-reviewed in full, and the reviewer
is asked to open the picture and the builder's after-screenshot and send back anything the result
leaves out or simplifies: artwork drawn as plainer shapes, a missing badge or ribbon. Those come
back as blocking findings, so the builder fixes them before the project returns to you. A polish
with the look left to the agent is reviewed by what it actually changed: only stylesheets, images,
fonts and documents, and it is not reviewed again; code, and the reviewer's cheapest model is asked
one question — did this change anything besides appearance? — and only a yes, or no answer, sends it
to the full review on the reviewer's best model. The project's details say which happened. You
still decide, with the before and after pictures in the report.

The report gains a **Polish** section: the design you chose, the program before, and the program
after, side by side. The two screenshots are the agent's own (`artifacts/polish-before.png` and
`artifacts/polish-after.png`), taken because it was asked to. Nothing checks them, and the section
says so; it also says when the "after" is older than the "before".

### Hearing it instead of reading it

Each card has **Read it to me**. Forge says what the project is — the verdict first, then what it
claims to do, the class that shows its shape, what the passing checks do not prove, what the
reviewer said and what is waiting on you. The words are read off the run's own files, so it costs
no quota, works with every vendor rate-limited, and cannot describe a project as anything other
than what is on disk.

The browser speaks it, the words appear in the project's details, and both the transcript and a recording are
left beside the run:

```
runs/003/walkthrough.md      what was said
runs/003/walkthrough.wav     the same, recorded, for listening to later
```

To hear about **Forge itself** rather than a project, there is a script that needs no jar, no build
and no editor — which matters on the day Forge will not compile:

```bat
explain.bat                 REM what Forge is: its modules, and what the build refuses
explain.bat jobs.queue      REM one package, from its package-info
explain.bat ReviewStep      REM one class, from its javadoc
explain.bat ReviewStep -q   REM write the transcript and recording, play nothing
```

It reads `ARCHITECTURE.md`, the module notes and the `package-info.java` files, speaks through the
voices Windows already has, and leaves `logs\explain-<topic>.md` and `.wav` behind. Nothing is
generated and no model is called: it says only what is written down.

### Watching the code explain itself

`forge tour` points at a Maven parent, walks every child project in the order the parent lists them,
and explains each class a line at a time — the guards, the refusals, the loops and what each method
answers with, with the line being talked about lit up on screen:

```bat
forge tour                        REM Forge itself: every module, top to bottom
forge tour --only forge-review    REM one module
forge tour --only ReviewStep      REM one class
forge tour --of ..\some-project   REM any Maven parent, not just this one
forge tour --video                REM also record it and mux an mp4
```

It writes `logs\tour\tour.html`, one file that plays itself in the browser's own voice: chapters
down the side, code in the middle, the sentence being said underneath. A project Forge built ends
with its own walkthrough — having heard how the code works, the thing worth knowing next is whether
anyone trusts it, and that is the one question the source cannot answer.

`--video` draws it at 24 frames a second and hands the frames straight to ffmpeg, so each line stays
on screen for exactly as long as it is being talked about. The highlight fades onto a line rather
than snapping to it, the code slides when the narration moves down a long method, the sentence
types itself in as it is said, and each module and class opens on a title card. Underneath runs a
lo-fi bed at 75 beats a minute with a tick as each line lands and a chime at each chapter, all
generated from arithmetic — nothing is downloaded and nothing is licensed — and ducked under the
voice whenever it speaks. `--no-music` leaves the ticks and drops the music.

ffmpeg is optional and is never installed by Forge; without it you still get the page and the
recordings, and only the single shareable file is missing. Recording is the slow part — a
synthesiser runs at the speed of speech — so scenes already recorded are left alone and a second
run costs only what changed.

No model is called here either. Every sentence comes from the code and the javadoc beside it, which
is why a tour costs nothing to regenerate and cannot describe the code as anything other than what
it says.

### The editor

`forge ui` serves a form over the wishlist directory. It is hosted by Forge rather than being a
static page for one reason: **Check** and **Save** validate the draft through the same
`SpecLoader` the overnight run uses, so the error you see is the error Forge would raise, not a
reimplementation of the rules that has drifted. An invalid spec is refused rather than written —
a file that fails to load is worse than no file, because the run skips it silently and the project
just never gets built.

Filenames are derived from the id and title, so renaming a spec in the form renames the file and
removes the old one.

**Editing the spec of a project that has already started reaches it.** Before every agent turn,
Forge compares the project's copy (`SPEC.md`, or `IMPROVEMENT.md` for an improvement) with the
spec as it now stands. If you changed it, the copy is rewritten in a commit of its own, and that
session's brief opens with **The spec has changed**: the diff, and that the new version wins over
anything decided earlier. The notice is kept until a session has actually been given it, so a turn
refused for quota does not swallow it. The reviewer and the security reading always used the spec
as it stands; the agent now does too. Settings in the header — serve, deploy, ads — reach it through
`AGENTS.md`, which is rewritten every turn, as before.

The server binds to `127.0.0.1` only. It writes files and has no authentication, so it must not be
reachable from the network.

`.\register-task.ps1` schedules `run` every few hours so it works unattended.

## How a project moves

```
NEW → PLANNING → SCAFFOLDED → GATING ─┬─ pass ─→ SECURITY_CHECK ─→ REVIEWING ─┬─→ DONE → ACCEPTED
                      ↑               │                                       │
                      ├── REPAIRING ←─┴─ fail                                 └─→ REVIEW_REPAIR ─┐
                      │      └── rounds exhausted ──→ PARKED                                     │
                      ├── CONTINUING ←── a failed gate the handoff called unfinished             │
                      ├── SECURITY_REPAIR ←── findings from the security check                   │
                      └──────────────────────────────────────────────────────────────────────────┘
                                     every repair loop returns through the gate

        any agent turn hitting a quota limit ──→ SUSPENDED, resuming where it was
        a CLI logged out or broken, or the same failure three times ──→ waiting for you (Start)
```

Fourteen phases in all. The four easiest to miss: **CONTINUING** is a failed gate that was honest
about being unfinished (see *Needing more time is not failing*), **SECURITY_REPAIR** is the
security reading's own repair loop, **REVIEW_REPAIR** the cross-review's, and **ACCEPTED** is the
one only you can cause.

- **PLANNING** is one read-only turn on the best model the building vendor has, before any code
  exists. It writes `runs/NNN/plan.md`, which goes in front of every turn that follows — see below.
  A plan already on disk is followed rather than bought again, so you can write one by hand.
- **PARKED** is an honest failure with the failing command, its output, and a rerunnable script.
  It is a better outcome than a green tick over unverified code.
- **Every blocking review finding must be fixed.** Each goes back to the builder with the
  reviewer's full explanation, and the builder must fix it with a test that fails without the fix,
  or dispute it with a test showing the behaviour is already right. The next review is given each
  finding by id, with the builder's answer, and must say whether it is `fixed`, `still_present` or
  `withdrawn`; one still present goes back with the reviewer's reason. A finding gets two attempts.
  One still present after that, or a reviewer still raising new blockers after four repair turns,
  holds the project **waiting for you** rather than finishing it. Pressing **Review** on a
  finished project rechecks its findings by id and starts the budget again.
- **SUSPENDED** costs no repair round. Quota exhaustion is not the project's fault, and the CLI's
  own message carries the reset time, which Forge parses to schedule the resume. A refusal that
  names no reset waits fifteen minutes.
- **Waiting for you** is what happens when no amount of waiting would help: a CLI that is not
  logged in, a CLI whose own sandbox will not start a process, the third agent call in a row that
  failed without doing anything, or the third time Forge itself threw on the project. The card
  says why, nothing starts it automatically, and pressing **Start** is how you say it is dealt
  with. Each of these used to be retried unattended, some of them for days.
- State is written after *every* transition, so a run survives reboots and quota windows.
- An agent turn is committed *after* it returns, so a crash in between leaves finished work with the
  phase still saying a build is owed. On the next run Forge spots the dirty working tree, commits it
  as recovered, and sends it to the gate rather than paying for another agent turn to reproduce what
  is already on disk. Build output is git-ignored by the scaffold precisely so that "dirty" means
  real work.

## Models, per spec

Each spec picks its own model for each vendor, with a slider in the editor:

```yaml
claude_model: opus
codex_model: gpt-6-astra
```

Leftmost is **the CLI's own default** — leaving the slider there sets no flag at all. Per spec
rather than global because the projects differ in kind: a throwaway script and a code generator do
not warrant the same model, and paying the top tier for the former spends a quota window the latter
needed.

The order comes from `models.json` at the Forge root, written on first use and yours to edit. It is
configuration rather than a constant because the ordering is a **judgement**: smallest and largest
are clear, the middle is not, and nothing here can tell you whether one mid-tier model is better
than another for this kind of work. Edit that file when you disagree, or when a vendor ships
something new.

The list itself is **discovered, not remembered**. Each CLI keeps a cache of the models its own
picker offers, and that is the only thing on the machine that knows what a particular subscription
can reach: a ChatGPT plan does not see everything OpenAI publishes, and a model can be retired
underneath a ladder that was correct when it was written. Forge reads those caches, so a model the
account cannot use never reaches the slider, and one already in `models.json` that has since gone
away is ignored with a line saying so rather than spending a turn being refused.

```bat
forge models             REM what each vendor offers, what the ladder uses, and where they disagree
forge models --refresh   REM rewrite models.json from the vendors
```

Nothing but the model lists is read — never a vendor's `auth.json` or anything else holding a
credential. Availability is public; the account is not.

Two entries are deliberate exceptions. **Fable** is left out although the account can use it: it
sits at the top of Claude's ladder, escalation reaches it after a couple of repair rounds, and it
then refuses every turn for want of usage credits, which is a loop rather than a failure.
**Antigravity** is listed with Google's own models only, though `agy` also fronts Anthropic's and
OpenAI's — choosing one of those to review a project the same vendor built would quietly undo the
cross-review.

### One ladder, read at every job

The jobs in a run are not equally hard, and paying the top tier for all of them is what makes a
project expensive. So each one takes its own rung:

| job | rung | why |
|---|---|---|
| **Plan** the project | top | one call, and it sets the direction of every turn after it |
| **Build** it | middle, or the spec's own model | following a plan is ordinary work |
| after each failed repair round | one rung up | another attempt from the model that just failed is not the fix |
| **Check each claimed feature** | bottom | narrow, mechanical reading: "is this really in the code?" |
| **Review** the change | top | the one judgement the whole pipeline turns on, made once |

A spec that names a model keeps it as the **building** baseline, and repair rounds still climb from
there. It does not pin the planning or feature-checking rungs, which are about how good the reading
has to be rather than about this project.

The cross-review is therefore two passes by the same reviewer: its cheapest model reads the code
once per feature the agent claims and says which it cannot find, then its best model reviews the
whole change once, with those claims in front of it as leads. The report lists every claim and what
was made of it — a claim nobody checked never reads as one that held up.

### New ideas from your idea generator

When `idea-generator.json` beside the wishlist names one of your projects, the wishlist's
description box gets **💡 New Idea**, with an optional category and highest effort:

```json
{"project": "006", "mainClass": "com.passiveincome.IdeaGeneratorApp",
 "categories": ["browser extension", "chrome/api wrapper", "cli tool", "content/seo site", "template/asset pack"]}
```

It runs that project's own program, from its compiled classes, with a fresh seed, and puts the idea
it produces in the description box: name, description, audience, category, how it makes money,
effort and build time. Not good? The button now reads **🎲 Reroll**; press it for another. Like it?
Press **Suggest** to turn it into a spec, then Save. The program runs in a scratch folder that is
deleted afterwards, so its report files never land in the project's repository. Nothing calls a
model and nothing goes over the network. If the project has not been compiled, the box says so.

### The suggester positions the sliders

Drafting a spec from a description also sizes the work, and the sliders land accordingly. The agent
is asked **how demanding the project is**, not which model to use:

| | |
|---|---|
| `routine` | well-trodden shape — a CLI, a parser, a report generator |
| `involved` | several moving parts, or a domain with real edge cases |
| `hard` | the approach itself is unclear, or it needs heavy algorithms or research |

That split is deliberate. A model ranking other models is guessing: the tiers here are newer than
most training data, it cannot see quota or cost, and a confident wrong ranking is worse than none.
Sizing the work is something it can actually read off the spec, and mapping a size onto a ladder is
arithmetic over *your* ordering — so the judgement and the ranking stay separate.

It sizes the thinking rather than the typing: ten routine files is `routine`; one function nobody
knows how to write is `hard`. The reason is shown next to the sliders, so you can disagree with it
before saving.

## Ranking

The wishlist is one dense ordering: **projects 1..P, then improvements P+1..P+E**. Every entry has
a unique, contiguous rank, so there is never a question of what comes next.

```bash
forge.bat rank        # renumber: projects first, then improvements
```

The dashboard has **Renumber 1..N** and per-project ↑/↓ buttons, so you never hand-edit the numbers.
Renumbering rewrites only the `priority:` line in each spec file — comments and formatting survive.

**Projects rank above improvements as policy**: finish the things that do not exist before
polishing the things that do. It is an ordering preference, not a barrier — an improvement still
runs whenever the projects above it are done, parked, or waiting on quota. Moving is confined to a
spec's own group for the same reason; an improvement cannot be promoted above the projects.

Improvements are ordered flat among themselves, not grouped under their parent, so the sequence is
yours to set outright. Grouping would have made an improvement's position depend on its parent's
rank, silently reshuffling an order you had already chosen.

### How many run at once

Rank decides what starts next. It does not change how much runs together, which is set by two
different limits:

| limit | value | what it bounds |
|---|---|---|
| **per vendor** | 1 by default | concurrent calls to that CLI — `concurrency` in `vendors.json` |
| `--workers` | 4 | projects *in flight*, including those in their own gate |

So the number of turns that can be talking to a model at once is the sum of the vendors'
`concurrency`, which on the defaults is one per installed CLI. Raise one when a vendor has headroom
and you want the wall clock back; leave it at 1 when quota is tight.

`--workers` is deliberately the larger: a project spends much of a turn running its own tests,
holding no agent slot, and capping in-flight work at the vendor limit would leave a CLI idle.

A running turn is never interrupted by a higher-ranked arrival — the quota already spent would be
thrown away and the project left mid-edit for nothing.

The dashboard shows each queued project as *next up* or *#3 in queue*, or the reason it cannot start
("waiting for 001 to be accepted first"). A spec blocked on its parent gets no queue position,
because it is not in the queue in any useful sense.

## Sub-specs: improvements to a finished project

A wishlist entry can name a `parent`, which makes it a change request against that project rather
than a project of its own:

```yaml
---
id: "001.1"
parent: "001"
title: Handle overlapping field definitions
acceptance:
  - mvn -q test -Dtest=OverlappingFieldsTest
---
```

Three consequences, each of them structural rather than a policy choice:

- **It runs in the parent's repository**, against the existing code and history. It keeps its own
  logs, evidence and state, but there is one repo per project, not one per request. The parent's
  `SPEC.md` stays put — an agent asked to improve something needs to know what it was for — and
  the request is written alongside it as `IMPROVEMENT.md`, which is kept up to date with the
  improvement's spec the same way (see *The editor*).
- **Its gate is the parent's acceptance commands plus its own**, ancestors first. A change that
  delivers the new behaviour by breaking the old one is not an improvement, and running the
  originals first means a regression is reported before the new feature's own checks bury it.
- **It waits for the parent to be `DONE`**, and improvements to one project run one at a time.
  They share a working tree, so running them together would have them editing each other's files.

Improvements chain: an improvement to an improvement still works in the original project's
repository, and inherits the whole ancestry's commands. A `parent` naming a spec that is not in the
wishlist is refused rather than quietly treated as a new project — it would otherwise scaffold an
empty repo and try to improve nothing.

## Time budget and handoffs

Every agent turn is told how long it has, and is required to end with a handoff:

```json
{"status":"partial",
 "summary":"Parser and model done; codegen not started.",
 "completed":["Record lexer","Field tree model"],
 "remaining":[{"step":"Generate the resource classes","detail":"Reuse FieldTree; one per record",
               "estimate_minutes":45}],
 "sessions_needed":2,
 "risks":["Overlapping field definitions are not handled yet"]}
```

The stated budget is deliberately **less than the hard cap** — 25 minutes of a 30-minute turn. A
process killed at its cap reports nothing, so an agent told it has the full thirty will be mid-edit
at thirty and get destroyed holding everything it learned. The reserve buys back the few minutes
needed to write the handoff.

That handoff is fed into the next turn's brief, so a project too large for one session continues
instead of restarting from the spec and rediscovering the same decisions at full price. The
dashboard shows steps remaining, sessions expected, and what the agent said it would do next.

A plan claiming `complete` changes nothing about acceptance — the gate still decides. It is a
claim, not evidence.

### Sessions are resumed, not restarted

Each project keeps one live agent session per vendor (`claude --resume`, `codex exec resume`), so a
turn continues the same conversation rather than cold-starting and re-reading the spec. Claude's
session id is assigned by Forge up front, so it survives even a turn killed before it reported
anything; Codex names its own on the first event, so a turn killed before that falls back to the
written handoff.

### Needing more time is not failing

Two separate budgets, because they measure different things:

| | |
|---|---|
| `max_repair_rounds` | attempts to fix something **broken** — the agent thought it was done and the gate disagreed |
| `max_sessions` | total agent turns, however spent — the backstop for a project that never converges |

When the gate fails, Forge checks whether the agent's handoff said the work was unfinished **and**
whether the turn actually changed the tree. If both hold it is a *continuation*, which spends a
session but not a repair round — a six-session project should not park for being large. An agent
that claims progress while changing nothing is treated as stuck, and does spend a repair round.

## Layout

```
wishlist/         one spec per idea; TEMPLATE.md.txt is the one that ships — copy it
house-rules.md    constraints copied into every workspace as CLAUDE.md and AGENTS.md
skills/           procedures agents load when they need them, copied into every workspace
backups/          house-rules.md as it was before the skills, and the script that checks nothing was lost
runs/NNN/
  repo/           the generated project — its own git repo, a commit per attempt
  logs/           agent transcripts, gate output, rerunnable acceptance scripts
  evidence/       artefacts the spec asked for; images render in the report
  state.json      the resumable state machine
  report.html
index.html        the morning skim
forge/            this orchestrator: one Maven module per layer, forge-core ... forge-app
  ARCHITECTURE.md where a change belongs, and the rules the build enforces
  target/forge.jar  the jar every script runs
```

### The house rules and the skills

`house-rules.md` holds what every project must and must not do, and Forge rewrites it into each
project as `CLAUDE.md` and `AGENTS.md` before every turn. Two kinds of text are kept out of it, so
that every turn of every project does not carry them:

- **Procedures are skills**, in `skills/<name>/SKILL.md` with any helper files beside them:
  `record-feature` (filming a feature, with a tested GIF writer), `run-bat` (with templates per
  stack), `check-viewports` (a script that screenshots a page at phone, landscape, laptop and desktop
  sizes and reports what is wrong) and `performance-tests`. Forge copies them into each project before
  every turn, where Claude Code (`.claude/skills/`) and Codex and Antigravity (`.agents/skills/`) look,
  kept out of git. An agent loads one when it needs it. The rule each one serves stays in the house
  rules, so a skill nobody loads costs a failed gate, never a broken rule.
- **Sections for some projects only** are marked `<!-- forge:only ads -->` … `<!-- forge:end -->`
  (or `deploy`). Forge leaves them out for a project whose spec has no ads, or no deploy step. The
  rule against ever deploying or logging in stays outside the marker, for every project.

To change either, edit the file; the next turn of every project picks it up. Before removing text
from `house-rules.md`, check nothing was lost:
`node backups/verify-migration.js backups/house-rules.2026-10-11.md house-rules.md skills` lists any
paragraph of the backup that is in neither the rules nor a skill, and fails if there is one.
`HouseRulesTest` also fails the build if the rules name a skill that does not exist, or a marker is
misspelt or left open.

Forge is split into eight Maven modules, each able to use only the ones below it, so Maven refuses
an import that points the wrong way. `forge/ARCHITECTURE.md` is the map. Every module and package
says what belongs in it (a `CLAUDE.md` per module, a `package-info.java` per package), and
`LayersTest` fails the build when a package imports something its documentation does not name.

In Eclipse, import `forge/` with *File → Import → Existing Maven Projects*: it finds the parent and
all eight modules. A workspace that had the single `forge` project from before needs it removed and
re-imported.

## Concurrency

Projects run in parallel, but each vendor is called `concurrency` times at a time — **one by
default**. The limit sits on each agent call rather than on the project, because a project does not
use one vendor throughout: a Claude-built project is reviewed by Codex. Fanning out more sessions
against a single subscription drains the same quota window faster and invites a throttle
mid-project, which is why the default is one and raising it is a decision about a particular plan.

## The risk worth knowing

Both CLIs run with elevated permissions so they can work unattended: Claude with
`--permission-mode bypassPermissions`, Codex with `-s workspace-write`. Each project works in its
own `runs/NNN/repo`, passed as the working directory and never a path above it, with a git baseline
commit so every change is a reviewable diff and a one-command revert.

Be clear about what that does and does not do. **It is where an agent works, not a wall around
it.** Claude has no sandbox: with permissions bypassed it can read and write anything your account
can, and its transcripts here show it using `%TEMP%` routinely. Codex's `workspace-write` sandbox is
a real boundary when it works. As your own Codex is configured on this machine (`windows.sandbox =
"elevated"`) it does not start at all when Forge launches it, so Forge names one that does; see
*Codex's settings* below. And whatever either agent writes into its
tests, the gate then runs with your full permissions, because running the project's own commands is
what the gate is for. In practice the agents are trusted with your user account, and the house
rules are what keeps them in their lane.

What Forge does enforce:

- agents are started without the vendors' API-key variables, so they cannot bill by the token;
- Claude turns load none of your own MCP servers (`--strict-mcp-config`);
- Forge's own git calls run none of the repository's hooks or `core.fsmonitor` command, so an agent
  cannot plant code that Forge would run for it when it commits the turn;
- the dashboard serves anything an agent wrote in a sandboxed origin, so an agent's HTML cannot
  drive the dashboard's API.

- Codex turns are started without the features and MCP servers `vendors.json` lists for it — by
  default computer use, browser use, apps and plugins. They were being used: Forge's logs hold 2,223
  plugin calls from Codex agents, 1,250 of them driving the desktop and 461 searching GitHub under
  your account.

### Codex's settings, in `vendors.json`

Codex remembers nothing between turns that Forge can rely on, so everything is said on every call.
Every vendor gets a block; this is the Codex one from the machine Forge was built on, so it shows
settings that are not defaults — `builds: false` here means this account spends Codex on reviewing
rather than building, which is a choice about two particular subscriptions.

```json
"CODEX" : {
  "builds" : false,
  "concurrency" : 1,
  "windowsSandbox" : "unelevated",
  "disabledFeatures" : [ "computer_use", "browser_use", "apps", "plugins" ],
  "disabledMcpServers" : [ "node_repl" ]
}
```

| setting | what it does |
|---|---|
| `builds` | whether `auto` may hand this vendor a project of its own. Building is where quota goes — a dozen turns, each carrying a growing conversation — while reviewing is one read-only turn. Turning it off is not "use this vendor less"; it is "spend it on the work that unblocks other work". **Claude and Codex build by default; Antigravity never does.** The example above is one machine's answer to a lopsided pair of plans, not the default. |
| `concurrency` | how many calls may reach this vendor at once. Default 1, never below 1 whatever the file says. It does not change what the work costs, only how fast the window empties: two at a time finishes two projects in the time one would take, at the price of being cut off with two half-built projects instead of one. |
| `windowsSandbox` | which Windows sandbox Codex uses for Forge's turns: `elevated`, `unelevated` or `mxc`. Omit it to use whatever your own Codex is configured with. On this machine `elevated` cannot start a process from Forge, and `unelevated` writes inside the workspace and is denied outside it. Your `~/.codex/config.toml` is not touched. |
| `disabledFeatures` | passed as `--disable`. `codex features list` names them all. |
| `disabledMcpServers` | MCP servers written directly into your Codex config, switched off for Forge's turns. `codex mcp list` shows them. |

Two commands answer "does the sandbox work here" without calling a model:

```bat
codex doctor
codex sandbox -c windows.sandbox=unelevated cmd /c echo ok
```

After a Codex update, run the live tests before trusting a build to it — they are the only thing
that can say what the installed CLI actually does, and they are how a resumed session losing its
write access was caught:

```bat
mvn -f forge/pom.xml test -pl forge-core "-Dexcluded.groups=" "-Dtest=LiveAgentTest" "-Dforge.codex.sandbox=unelevated" "-Dforge.codex.mcpOff=node_repl"
```
