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
  may have been asked for, or change nothing. Except while an extra is being added
  (`RunState.extraKind`): its brief is to leave the default look as it is, so a changed one goes
  back to the agent like any other failing check.
- **Every project owes a `run.bat`** (`RunFile`). The gate checks it exists, starts something, and
  does not start a server through npm, npx, yarn or pnpm: every batch file in between answers
  Ctrl+C for itself, so the server outlived its window and kept its port (003). Installing and
  building through npm are fine. It never runs the file: it opens a window and does not exit.
  Whether it works is for the person.
- **A version that owns folders changes nothing outside them** (`OwnedFiles`, from `GateStep` on a
  gate that passed). The spec's `owns:` globs; every file changed since the version's baseline
  must match one, apart from what Forge writes itself (`IMPROVEMENT.md`, `SPEC.md`, `artifacts/`).
  A range that cannot be read fails rather than passes. A spec without `owns` is not checked. For a
  family built one feature per version, where no feature may change another's code.

## Tests

`GateTest`, `EvidenceCollectorTest`, `OwnedFilesTest`.
