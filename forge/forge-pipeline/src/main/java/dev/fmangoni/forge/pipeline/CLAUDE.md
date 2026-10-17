# pipeline

Drives one project through its phases. `Orchestrator` is the loop and the dispatch only: it runs
`Recovery` once, then one step per phase, saving `state.json` after each. Every step shares one
`RunContext` (spec, paths, state, agents, workspace, wishlist, models) and records its outcome in
the state.

| Phase | Class |
|---|---|
| NEW | `ScaffoldStep` |
| PLANNING | `PlanStep` (read-only, top of the ladder, writes `plan.md`) |
| SCAFFOLDED, REPAIRING, CONTINUING, SECURITY_REPAIR, REVIEW_REPAIR | `Turns` (which brief), `AgentTurn` (runs it) |
| GATING | `GateStep` |
| SECURITY_CHECK | `SecurityStep` |
| REVIEWING | `ReviewStep` |

## Which model runs a turn

Never `spec.modelFor(...)` directly: ask `run.models()` (`spec.ModelChoice`). Planning takes the top
rung, implementation the middle one (or the spec's own model) climbing a rung per repair round
spent, the per-feature pass the bottom, and the big review the top. `PlanText` puts the plan in
front of every implementation turn, which is what makes the cheaper rung reasonable.

## Must not be traded away

- **The gate decides done.** Nothing the agent says reaches `GateStep`'s verdict.
- **State is saved after every step**, so stopping between turns is always safe, and
  `turnInFlight` is set before an agent runs so `Recovery` can rescue its work.
- **A rejected or empty agent call is not a session** (`AgentTurn`): it suspends or backs off
  without spending the session or repair budget.
- **What waiting cannot fix waits for a person.** A logged-out CLI or a broken sandbox is held by
  `run.Suspension` until someone presses Start; so is the third agent call in a row that failed
  without doing anything (`run.PersonHold`). Every suspension carries a resume time — one without
  was read as "ready now", and relaunched the same call the moment the slot freed.
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
- **A clip moving between names parks.** When a gate finds a clip that was missing now made and one
  that was made now missing (`MissingClips.moved`), it is one feature approved under two names, and
  another session would only move it back.
- **A review never launders a failure.** `ReviewStep` parks a project whose gate never passed,
  however clean the review.
- **One repair turn** for serious security findings; after that a person decides.
- **Every blocking review finding must be fixed** (`run.ReviewLoop`). Findings carry ids; the next
  review rules on each (`fixed`, `still_present`, `withdrawn`) before raising anything new, and one
  it does not settle is carried with its attempts. Two attempts per finding and four repair turns
  per loop, then the project is held for a person — never DONE with a blocker on it. A hold stops
  the orchestrator's loop whatever the phase.
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

Append it to `Phase`, write a step class taking `RunContext`, add a case to `Orchestrator.step`,
and add it to `Compaction.atACleanBoundary` (a compile error reminds you).

## Tests

`OrchestratorTest` drives whole runs with stub agents and real git in temporary directories (it
seeds `plan.md`, so it tests the phases rather than the planner); `PlanningTest` pins which model
each turn ran on; `CompactionTest`, `WorkspaceTest`.
