# gate

Forge runs the spec's acceptance commands itself and decides from their exit codes whether a
project is done. It also demands the recordings owed to approved features, and collects the
evidence the spec names.

## Must not be traded away

- **The gate decides done, not the agent.** An agent's summary, plan or claim of success never
  counts here.
- **Commands run as written in the spec**, from a script (`process.ShellScript`) so no quoting
  changes them, inside the run's repo, with the spec's timeout.
- **Checks stay deliberately shallow where judgement is needed.** The flow-class check verifies the
  file exists and is not a stub. Whether it is a good flow is for the review and the person.
- **A failed recordings check lists every owed clip**, filmed or missing, each under the exact name
  it is owed by. Listing only the missing ones let an agent fix one name by renaming another's clip.
- **A project with a pack slot keeps its default look** (`LookCheck`, called from `GateStep` on a
  gate that passed). Its suite saves `artifacts/look-default.png`; the first passing gate keeps it
  beside the repository (`RunPaths.lookReference`), and later gates fail when more than 2% of the
  pixels changed, the size changed, or the screenshot stopped being saved. Only a polish that
  replaces the look clears the reference (`LookCheck.forget`), or a person who saw both looks and
  pressed Approve New Look, which sets the old one aside as `look-reference.before-<id>.png`
  (`LookCheck.retire`). A gate stopped only by this check is held for the person at once
  (`GateStep`): no agent can reach the reference, so another session could only undo work that
  may have been asked for, or change nothing.
- **Every project owes a `run.bat`** (`RunFile`). The gate checks it exists and starts something. It
  never runs it: it opens a window and does not exit. Whether it works is for the person.

## Tests

`GateTest`, `EvidenceCollectorTest`.
