# Forge — enhancements to make checking easier

Proposed 2026-11-06, while building the checks for the Twin Thrones event mockups
(`runs/008/art/tools/`). Each says what it would save you and what in Forge it builds on, and
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
`runs/008/art/tools/lockmap.py`).

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
blocking finding. A later gate that no longer asks takes them away.

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
asks for it by listing the files in `evidence`; a suite that saves none is not checked.

### 6. Picture jobs as Forge jobs

The picture work (car masters, event screens) runs outside Forge from job lists, and the image
agent writes its own **Record**, which is the agent's claim. The job lists already have what a
spec needs: a check command that exits non-zero on a problem (`tools/events.py check`,
`tools/finish.py`) and a review sheet per job. As a spec kind, the check would be the acceptance
command and the sheet the evidence; Forge would keep the record, resume after a usage limit, and
**Quick Review** would deal the sheets as Keep / Redo cards.

**Not built yet, on purpose.** Unlike the others it is not one class attached in one place; it
needs three decisions first:

- **Where the work happens.** An agent may only work in its `runs/NNN/repo`, and the picture work
  writes into `runs/008/art/` and, on accept, into run 008's pictures and the event's spec. Either
  each picture batch becomes a repository of its own (and `accept` a separate, clicked step), or
  the rule gets an exception, which `CLAUDE.md` says never to make.
- **Which agent draws.** Forge's agents are the coding CLIs. The pictures come from one model's
  built-in image tool, so the job needs that vendor and a brief that says "one call per image,
  prompt as written", and nothing in Forge checks which tool made a file.
- **What a Redo does.** A card sent back has to become the next turn's instructions for that one
  job, not a repair of the whole batch, which is how the review form works today.

Once those are settled, the pieces are a spec kind (the check as the acceptance command, the sheets
as evidence) and a card kind in `report.deck` for the sheets.

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
