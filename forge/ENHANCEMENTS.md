# Forge — enhancements to make checking easier

Proposed 2026-11-06, while building the checks for the Twin Thrones event mockups
(`runs/008-pictures/tools/`). Each says what it would save you and what in Forge it builds on, and
**Built:** under one that has been.

## For Forge

### 1. Check a spec's pictures when it loads

Every `[image: …]` a spec names should exist, and every row of its pictures table should have its
screenshot in `evidence`. Today `SpecLoader` checks only the front matter (id, title, parent,
deploy, monetization), so a spec that names a missing picture, or a half-updated one, is found
when an agent trips on it, which costs a turn.

**Built:** `spec.SpecPictures` reads the pictures and the table; `pipeline.MissingPictures`, called
once, first in the pipeline's `Outline`, holds the project for you before any step, naming the picture.

### 2. Each spec picture beside its screenshot, in the report

`PolishSection` already shows the chosen design, before and after, side by side, because "did it
come out like the picture" is a question neither the gate nor a review answers. Specs with
`design: attached pictures` ask the same question in their pictures table ("Picture | State | Your
screenshot of it") but the report does not pair them. Pair each row: the picture, the agent's
screenshot, and the state between them.

**Built:** `report.outward.PicturesSection`, one line in `ProjectReport`, pairs each row of the
spec's (or the enhancement's) pictures table under its state, and says when a screenshot is not
there yet.

### 3. Mark what changed since you last accepted

`LookCheck` compares one screenshot, the default look, with a held reference and fails on a change.
The same comparison over every evidence screenshot, against the copy from the last version you
accepted, shown as a red overlay rather than a failure, would let you look only where something
moved. Compare by shape rather than colour where a theme is expected to recolour: the event
checks do this with grey correlation per 16 px cell after straightening the picture (see
`runs/008-pictures/tools/lockmap.py`).

**Built:** `report.changes.ScreenChangesSection`, one line in `ProjectReport`. It finds the
family's version accepted last (accepted versions keep their evidence, so nothing is copied at
accept time) and compares every screenshot both versions saved under the same name, 16 px cells
by grey correlation, a couple of pixels of slack. Changed screens are shown beside the accepted
one, washed red, and written under the run's `changes/`; the rest are listed as the same. It fails
nothing.

### 4. A "passed, but look" result

An acceptance command can only pass or fail. Some checks know something is worth a person's eye
without knowing it is wrong: the event check scores interface changes of 6–11 cells as LOOK and 12
or more as PROBLEM, because finished screens reach 13 and planted mistakes start at 14. Let a
command exit with a reserved code meaning "passed, a person should look", with its output going
into **Your review** as an item instead of failing the gate.

**Built:** exit code 42 (`CommandResult.LOOK`) passes. `state.GateLooks` records each output line
starting `LOOK` after every gate (attached after the gate step in `Outline`) and adds them to the review items the
form, the quick review and saving all use. They start unticked and are dealt on their own, like a
blocking finding. A later gate that no longer asks takes them away. The event check
(`tools/events.py check`) exits 42 on a LOOK, so it is ready to be an acceptance command.

### 5. Forge compares the words itself

The event specs ask the agent's Playwright suite to "assert that every screen's labels and figures
are the same as in the default theme", so the agent's own test grades the agent. If the suite also
saved each screen's visible text, default and themed, to named files, the gate could compare them
itself, in keeping with "the gate decides done, not the agent".

**Built:** `gate.words.SameWords`, attached in `Outline`: cleared before the gate step,
checked as one of its gate checks. The suite saves each
screen's visible text to `artifacts/words/<screen>.<look>.txt` (`default` for the default theme).
The gate empties that folder before the commands run, and a gate that passed fails when a look's
lines differ from the default's, in any order, listing what differs for the repair turn. A spec
asks for it by listing the files in `evidence`; a suite that saves none is not checked. The Twin
Thrones event specs ask for it: six screens, in the event's theme and the default, twelve files each.
Each event names its own pair (`halloween-main-game.default.txt` beside
`halloween-main-game.halloween.txt`): every event's suite runs in the same gate, and a shared
`main-game.default.txt` would be whichever suite wrote it last, perhaps from another game state.

### 6. Picture jobs as Forge jobs

The picture work (car masters, event screens) runs outside Forge from job lists, and the image
agent writes its own **Record**, which is the agent's claim. The job lists already have what Forge
needs: a check command that exits non-zero on a problem (`tools/events.py check`,
`tools/finish.py`) and a review sheet per job.

Decided 2026-11-09:

- **Where the work happens.** Each batch is a run folder of its own (`runs/008-pictures`), with a
  `batch.json` and no `state.json`, so nothing that looks after projects sees it. The agent draws
  in its `repo/`; the cards, the tools and the checks sit beside it, out of its reach. Placing a
  kept picture in the project it is for (the cards' accept) is a step a person clicks.
- **Which agent draws.** Astra, outside Forge, with its built-in image tool. Forge writes the jobs
  left to draw (`repo/next-jobs.md`) and the one message that starts it (`repo/astra-prompt.md`),
  which stays the same after a usage limit because the list holds only what is left.
- **What a Redo does.** That job alone is drawn again: the same prompt, with the person's note
  added as the one sentence saying what to fix. The other jobs carry on.

**Built:** `pictures.PictureBatches` (forge-pipeline) and `ui.pictures.PictureRoutes`, attached in
`UiServer`. A watcher (`pictures.work.PictureWatch`) looks at every batch every five seconds: it
runs a card's check on each picture once it is saved (exit 42 is a LOOK), keeps the record
(`record.json`), and writes the agent's list again. A card per batch on the dashboard
(`extras/pictures.js`) counts the jobs and copies the prompt; `/pictures` deals the review sheets
as Keep / Draw again cards and sends them at once; Place runs each kept picture's place command,
adding the batch's `anyway` word when a person kept one whose check found a problem. **STOP** and
`Only after` lines hold the jobs after them until the pictures before are kept.

### 7. A quick gate while the builder says unfinished

After a turn whose handoff says `partial`, the gate can only lead to another session: a pass goes
to CONTINUING (`moreToBuild`), and so does a failure once the turn built something. Yet each of
those gates ran the whole suite. 003.3 had sixteen continuations, each waiting ten minutes and more
on its browser suite before the next session could start.

**Built:** `pipeline.step.QuickGate`, the GATING step in `Outline`, standing in front of `GateStep`.
When the handoff says `partial`, the turn changed code, sessions are left and every command has
been timed before, it runs the spec's commands up to the first that usually takes two minutes or
more (`SLOW_SECONDS`, against the middle of its last runs), and the next session starts. The full
gate runs when the builder says `complete`, when sessions run out, after an interrupted turn and
for Check Again, so no version reaches the review without it. The commands left out are kept on
the state (`notRunAtLastGate`): the next brief names them (`QuickGateBrief`), and the report says
"The quicker acceptance commands passed" rather than "all". The evidence, the recordings and the
gate checks (the words, the look, the owned files) wait for the full gate too.

## For how the work is run

- Keep the pilot-then-stop pattern: two jobs, a look, then the rest.
- Change the card template after the pilot, not during it, and regenerate every card with the
  one command (`tools/make_cards.py`).
- Give every picture job a check command before the image agent starts, so "is it right?" is a
  command, not a judgement.
- Keep the image agent's prompts in the folder where it works (`astra-prompt.md`), so the same
  prompt is resent after each limit.
- Write "must stay exactly" rules as checks, not prose in specs.
- When pausing a run on purpose, write the reason where it will be seen, so it is not taken for
  a hang.
- Say at the start of a session if the editor was started by hand.
- Queue the next job list before a limit resets, so the window is not spent waiting on a decision.
- Review the sheets in one sitting per batch, not one at a time.
