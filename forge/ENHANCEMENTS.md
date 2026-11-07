# Forge — enhancements to make checking easier

Proposed 2026-11-06, while building the checks for the Twin Thrones event mockups
(`runs/008/art/tools/`). None of these is built yet. Each says what it would save you and what in
Forge it builds on.

## For Forge

### 1. Check a spec's pictures when it loads

Every `[image: …]` a spec names should exist, and every row of its pictures table should have its
screenshot in `evidence`. Today `SpecLoader` checks only the front matter (id, title, parent,
deploy, monetization), so a spec that names a missing picture, or a half-updated one, is found
when an agent trips on it, which costs a turn.

### 2. Each spec picture beside its screenshot, in the report

`PolishSection` already shows the chosen design, before and after, side by side, because "did it
come out like the picture" is a question neither the gate nor a review answers. Specs with
`design: attached pictures` ask the same question in their pictures table ("Picture | State | Your
screenshot of it") but the report does not pair them. Pair each row: the picture, the agent's
screenshot, and the state between them.

### 3. Mark what changed since you last accepted

`LookCheck` compares one screenshot, the default look, with a held reference and fails on a change.
The same comparison over every evidence screenshot, against the copy from the last version you
accepted, shown as a red overlay rather than a failure, would let you look only where something
moved. Compare by shape rather than colour where a theme is expected to recolour: the event
checks do this with grey correlation per 16 px cell after straightening the picture (see
`runs/008/art/tools/lockmap.py`).

### 4. A "passed, but look" result

An acceptance command can only pass or fail. Some checks know something is worth a person's eye
without knowing it is wrong: the event check scores interface changes of 6–11 cells as LOOK and 12
or more as PROBLEM, because finished screens reach 13 and planted mistakes start at 14. Let a
command exit with a reserved code meaning "passed, a person should look", with its output going
into **Your review** as an item instead of failing the gate.

### 5. Forge compares the words itself

The event specs ask the agent's Playwright suite to "assert that every screen's labels and figures
are the same as in the default theme", so the agent's own test grades the agent. If the suite also
saved each screen's visible text, default and themed, to named files, the gate could compare them
itself, in keeping with "the gate decides done, not the agent".

### 6. Picture jobs as Forge jobs

The picture work (car masters, event screens) runs outside Forge from job lists, and the image
agent writes its own **Record**, which is the agent's claim. The job lists already have what a
spec needs: a check command that exits non-zero on a problem (`tools/events.py check`,
`tools/finish.py`) and a review sheet per job. As a spec kind, the check would be the acceptance
command and the sheet the evidence; Forge would keep the record, resume after a usage limit, and
**Quick Review** would deal the sheets as Keep / Redo cards.

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
