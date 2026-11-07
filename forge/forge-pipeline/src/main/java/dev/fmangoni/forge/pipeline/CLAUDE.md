# pipeline

Drives one project through its phases. **`Outline` is the whole run in order**: what is done once
before any step (`Recovery`, `MissingPictures`), then one step per phase, with what is attached
before or after each and the checks the gate adds after the commands (`step.GateCheck`). Read
`Outline.of` top to bottom. `Orchestrator` is the loop only: it follows the outline, saving
`state.json` after each step. Every step shares one `RunContext` (spec, paths, state, agents,
workspace, wishlist, models), records its outcome in the state, and sets the next phase itself.

**Attach, don't edit.** Something new to do around a step goes in `Outline` as `first`, `before`
or `after`; something the gate must hold a passing build to is a `GateCheck` in the gate's list.
Neither touches the step. `OutlineTest` fails on a phase with no step or an attachment to one.

| Phase | Class |
|---|---|
| NEW | `ScaffoldStep` |
| PLANNING | `PlanStep` (read-only, top of the ladder, writes `plan.md`) |
| SCAFFOLDED, REPAIRING, CONTINUING, SECURITY_REPAIR, REVIEW_REPAIR | `Turns` (which brief), `AgentTurn` (runs it) |
| GATING | `QuickGate` (the quick commands only, while the builder says partial), otherwise `GateStep` |
| SECURITY_CHECK | `SecurityStep` |
| REVIEWING | `ReviewStep` |

## Which model runs a turn

Never `spec.modelFor(...)` directly: ask `run.models()` (`spec.ModelChoice`). Planning takes the top
rung, implementation the middle one (or the spec's own model) climbing a rung per repair round
spent, the per-feature pass the bottom, and the big review the top. `PlanText` puts the plan in
front of every implementation turn, which is what makes the cheaper rung reasonable.

## Must not be traded away

- **The gate decides done.** Nothing the agent says reaches `GateStep`'s verdict.
- **Passing commands do not end a version its builder calls unfinished.** A gate that passes after
  a turn that changed something, with the handoff's `status` "partial" and sessions left, goes to
  CONTINUING rather than the review (`GateStep.moreToBuild`). 003.3 passed while its handoff listed
  four steps left, and the person found the missing half by playing it. Only the explicit status
  counts, not a non-empty `remaining`; never for Check Again; once sessions run out it goes to the
  review as before. The agent's word can hold a version back, never pass one.
- **Progress is code, not screenshots.** Both "more time" checks (`moreToBuild`,
  `unfinishedRatherThanBroken`) ask `OwnedFiles.builtSince`, which ignores what Forge and the suite
  write. Every gate saves the screenshots again, and counting them gave 003.3 five sessions in
  which nothing was built. `ContinueBrief` says so when the last gate passed: telling that builder
  its commands failed kept it calling the work partial.
- **A quick gate never passes a version.** After a turn whose handoff says `partial`, that changed
  code, with sessions left, and never after an interrupted turn or for Check Again, `step.QuickGate`
  runs only the commands before the first that usually takes `SLOW_SECONDS` or more, and always
  goes to CONTINUING, where the full gate would have sent it too (a changed look or a moved clip,
  which would have parked it, waits for the full gate). Every other gate is the full one, so nothing
  reaches the review without it. A command never timed is run, so a new one is never skipped. What
  a quick gate left out is `notRunAtLastGate`, named in the next brief (`QuickGateBrief`) and in the
  report's verdict, and cleared by the next full gate. 003.3 waited ten minutes and more on its
  browser suite before each of its sixteen continuations.
- **A person's request gets sessions of its own.** Requested changes (`Turns.continueWork`) and Run
  Another Session (`Reopening`) call `RunState.oweSessionsToARequest`: five sessions past wherever
  the project stands, or the spec's limit if that is higher. Every check of the limit asks
  `state.sessionLimit(spec.maxSessions())`, never the spec alone. 003.3 was asked for two features
  at session 33 of 30 and parked on its first failing gate, with two tests left it had named.
- **State is saved after every step**, so stopping between turns is always safe, and
  `turnInFlight` is set before an agent runs so `Recovery` can rescue its work.
- **A rejected or empty agent call is not a session** (`AgentTurn`): it suspends or backs off
  without spending the session or repair budget.
- **What waiting cannot fix waits for a person.** A logged-out CLI or a broken sandbox is held by
  `run.Suspension` until someone presses Start; so is the third agent call in a row that failed
  without doing anything (`run.PersonHold`). Every suspension carries a resume time — one without
  was read as "ready now", and relaunched the same call the moment the slot freed.
- **A spec's pictures are checked before any step** (`MissingPictures`, first in `Outline` after
  `Recovery`, using `spec.SpecPictures`). Every `[image: …]` the spec names must be in the
  repository, and every screenshot in its pictures table must be in `evidence`; otherwise the
  project is held for the person, naming the picture. No agent can mend a spec or the pictures
  folder, so a turn would only trip on it. `[image: …]` inside backticks is prose and not checked.
- **The loop stops between steps when asked** (`Orchestrator`'s stop check, which the job queue
  wires to a draining shutdown). A stop never lands inside a step.
- **The person picks the vendor, even mid-project.** `Recovery` honours a changed `assign:` at any
  point. On a started project that is a takeover: both vendors' conversations are dropped, and
  `takeover` puts "you are taking this project over from …" first in the next brief, whatever kind of
  turn it is, until one reaches a model. `auto` never moves a started project.
- **The agent works to the spec as it stands.** Before every turn `Workspace.refreshSpec` brings
  `SPEC.md` (or `IMPROVEMENT.md`) up to date in its own commit; the diff is kept on the state as
  `specChange` and put first in the brief (`SpecChangeBrief`) until a turn reaches a model.
- **A gate checked again starts no turn.** Check Again sets `gateOnly`; `GateStep` clears it, and
  a gate that still fails parks again rather than leading to a session.
- **A polish is reviewed by what it changed.** `polishBase` is set when a picked design is built;
  `ReviewStep` asks `PolishScope` first. Only stylesheets, images, fonts and documents since then:
  no cross-review. Code: one question to the reviewer's cheapest model (`ScopeCheck`), and the full
  review only on "more than appearance" or no answer. Never on a gate that has not passed, and never
  for Review Again, which clears it. **Never for a polish built to a picked design** (`polishDesign`):
  that one is always reviewed in full, and `ReviewBrief` has the reviewer open the picture and the
  builder's after-screenshot and raise what is missing or simplified as blocking. It is cleared when
  a review leaves nothing standing, so every review in its repair loop compares with the picture.
  The reviewer must reply `"compared_with_design": true`; one that does not is passed over for the
  next reviewer, and if none compares, the project is held for the person, never DONE.
  A spec naming `design: attached pictures` (`ReviewBrief.OWN_PICTURES`) is held to the person's own
  pictures the same way, with no design picked: each picture against its own screen's screenshot.
- **A clip moving between names parks.** When a gate finds a clip that was missing now made and one
  that was made now missing (`MissingClips.moved`), it is one feature approved under two names, and
  another session would only move it back.
- **A review never launders a failure.** `ReviewStep` parks a project whose gate never passed,
  however clean the review.
- **One repair turn** for serious security findings; after that a person decides.
- **Every blocking review finding must be fixed** (`run.ReviewLoop`). Findings carry ids; the next
  review rules on each (`fixed`, `still_present`, `withdrawn`) before raising anything new, and one
  it does not settle is carried with its attempts. A `fixed` or `withdrawn` with no note settles
  nothing: the next reviewer is asked, and if none explains, the project is held for a person. Two attempts per finding and four repair turns
  per loop, then the project is held for a person — never DONE with a blocker on it. A hold stops
  the orchestrator's loop whatever the phase. The builder may defend its code instead of changing
  it (`disputed`, with a test as evidence); the recheck is shown that test's file and rules on the
  defence, withdrawing the finding or saying what the test misses.
- **The planning turn is read-only and never fatal.** No plan, or a refused planner, must leave the
  project exactly as buildable as it was before the phase existed.
- **A plan already on disk is not bought again** — it may have been written by hand.
- **Phases are appended to `project.Phase`** and never renamed: `state.json` stores them by name.
- **The house rules lose nothing to the skills.** `Workspace` writes the rules (dropping only the
  `forge:only` sections a spec does not need, `RuleSections`) and installs `skills/` into
  `.claude/skills` and `.agents/skills` (`SkillInstall`), out of git. A procedure may move to a
  skill; the rule it serves stays in the rules. Check with `backups/verify-migration.js` before
  removing text, and keep `HouseRulesTest` green.
- **Agents work in `runs/NNN/repo`**, and `Workspace` rewrites their rules file every turn. That is
  their working directory, not a sandbox: nothing here can stop a Claude turn reaching outside it,
  so never design anything that assumes it cannot.

## Adding a phase

Append it to `Phase`, write a step class taking `RunContext`, add a `step` line to `Outline.of`
where it falls in the run, and add it to `Compaction.atACleanBoundary` (a compile error reminds you).

## Tests

`OrchestratorTest` drives whole runs with stub agents and real git in temporary directories (it
seeds `plan.md`, so it tests the phases rather than the planner); `PlanningTest` pins which model
each turn ran on; `CompactionTest`, `WorkspaceTest`.
