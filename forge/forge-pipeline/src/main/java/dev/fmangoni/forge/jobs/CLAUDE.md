# jobs

Which projects run, when, and on whose say-so. `JobRunner` is a thin facade and **the one lock**:
every public method that can launch something is `synchronized` on it, and the classes behind it
are not synchronized themselves.

| Concern | Class |
|---|---|
| workers in flight, capacity, stopping one | `JobPool`, `JobWorker` |
| why a project cannot start | `StartRules` (on top of `Eligibility`, shared with `Scheduler`) |
| auto-run, review-first order, what may start | `JobQueue`, `LockedElsewhere` |
| starting a project's pipeline, cool-off | `PipelineJobs` |
| button jobs | `DeployJob`, `MarketingJob`, `SecurityRescan` (via `ButtonJob`) |
| publishing to GitHub: on approval (from `JobRunner.decide`) and on Publish; off unless `publishing.json` says on | `PublishJob` (`jobs.publish`) |
| reopen, re-review, fix blocking findings, check a parked project's gate again | `Reopening` |
| Suggest Updated Design: asking the vendors for looks anywhere it has run, from nothing or for more like one on the board (`DesignBoard.LIKED`); building the pick now (DONE), as an enhancement (ACCEPTED, `DesignEnhancement`), or keeping it, with its note, for later; extras (theme, variants, animation) added beside the look as a pack instead, briefed by `ExtraBrief` | `Polish` (`jobs.polish`) |
| opening a project's `run.bat` | `Play` (not a job: no worker, no lock) |
| the review form | `Submissions` (saving) and `SubmissionOutcome` (what follows) |
| one feature's ruling from the dashboard (starts nothing) | `Submissions.ruleFeature` |
| the quick review's cards | `ReviewDecks` (reads only; submits through `Submissions`) |
| stopping without killing a turn | `Shutdown` |
| the dashboard's rows | `StatusBoard` |
| unattended `forge run` | `Scheduler` |

## Must not be traded away

- **Don't grow the facade.** A new button is a new small class, delegated from `JobRunner`.
- **Launch decisions happen under `JobRunner`'s lock.** Callbacks from worker threads
  (`afterJob`, the ticker) go back through synchronized `JobRunner` methods.
- **Deploy, marketing and security rescans run only on a click**, never from the queue.
- **The queue never clears a quota wait or a hold for a person**; only an explicit Start does.
- **A step that throws cools off, and the third in a row is held for a person** (`PipelineJobs`).
  A cool-off alone only slowed a deterministic failure down: 004 re-ran its gate every twelve
  minutes for three days.
- **Shutdown never kills a turn.** It drains and waits; if the wait runs out it resumes in full.
  A pooled project counts as in flight while its phase is any that calls an agent — PLANNING,
  REVIEWING and SECURITY_CHECK write nothing so `turnInFlight` never covers them, and a building
  phase counts before the flag is written. Draining also stops each orchestrator between steps,
  so no new turn starts in the gap the shutdown is waiting for.
- **A submission is saved before anything can refuse it**, and features alone never accept.

## Tests

`JobRunnerTest` (82 tests through the facade, stub agents, temporary roots; its projects start
with a plan already on disk, so they test job control rather than the planning turn),
`EligibilityTest`, `AssignmentTest`, `PruneTest`, `JobRunnerLockTest`.
