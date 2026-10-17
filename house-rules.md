# House rules

These apply to every project built here. They are copied verbatim into each workspace as both
`CLAUDE.md` and `AGENTS.md`, so whichever agent is assigned reads identical constraints.

How to do some things is kept as skills rather than here, beside this file in `.claude/skills/` and
`.agents/skills/`: `record-feature` (filming an approved feature), `run-bat` (writing `run.bat`),
`check-viewports` (checking a page at phone and desktop sizes) and `performance-tests`. Load the one
you need when you get to it. Each rule below still holds whether you load a skill or not; a skill
only explains how to meet it.

## Definition of done

The `acceptance` commands in `SPEC.md` are the contract. They are run by the orchestrator, in a
fresh process, against the files you leave on disk — not by you, and your report of success is not
consulted. A project passes only when every one of those commands exits zero.

Two consequences:

- **Do not weaken the acceptance commands, the tests they run, or the assertions inside them** to
  make them pass. Changing the contract to fit the code is the one failure mode this pipeline
  cannot detect automatically, and it wastes the whole run.
- If an acceptance command is genuinely wrong or impossible, say so plainly in your final message
  and stop. A parked project with a clear explanation is a good outcome. A green tick over a
  hollowed-out test is not.

## A way to run it

**Every project has a `run.bat` in the repository root, and the gate does not pass the project
without one.** It is how the person this was built for tries it: they double-click it. They have
not read the README and will not type a command.

It has to work from a fresh checkout on Windows with only the stack's own toolchain installed,
build whatever is missing, and start the real program, not a demo of it. It never asks a question,
and never closes on an error: a failed step says which one and pauses. The `run-bat` skill has the
steps for each kind of program, and templates.

Keep it out of the acceptance commands: it does not exit, so a gate that ran it would wait for ever.
The gate checks that the file exists and starts something, and nothing more. Whether it works is
found out by the person, so try each step of it before you finish, stop anything it started, and
say in your handoff if there is a step you could not try.

## Other processes on this machine

You share this machine with the orchestrator running you, which is itself a Java process, and with
other projects being built at the same time. **Never stop processes by name** —
`Get-Process java | Stop-Process`, `taskkill /im java.exe`, `taskkill /im node.exe`, `pkill java`,
`killall node`. Each of those also kills the orchestrator, and your turn with it: everything you
did since the last commit is recovered without your handoff, and costs the project a session.
It has happened here.

Stop only what you started: keep the process id when you launch something, or match on a command
line containing this repository's path or your own artefact's name, and check what matched before
stopping it. If a file is locked and you cannot tell by whom, say so in your handoff rather than
killing things until it frees.

The same goes for servers. Stop any dev or preview server you start by hand before you finish, and
never make a test suite reuse a server it did not start (`reuseExistingServer: true`, or tests
pointed at a port nobody launched). Forge runs acceptance with `CI=true`, so a suite written the
usual way starts its own; one that reuses whatever is already listening may be testing another
project, or your own build from an hour ago.

## Security

After the acceptance commands pass, Forge checks the project for security problems: credentials
committed anywhere in the history, dependencies with published vulnerabilities, risky code patterns,
the running app (when the spec has a `serve` command), and another model reads the code for ways it
could be abused. A serious finding comes back to you as one repair turn.

- **Never commit a credential**, not even briefly. Read keys, tokens and passwords from environment
  variables or an untracked file listed in `.gitignore`, and commit a `.env.example` with placeholder
  values instead of a `.env`. A key deleted in the next commit is still in the history; the only fix
  is revoking it, which you cannot do — so if one was committed, say which in your handoff.
- Escape anything that came from a user, a URL or a file before it goes into HTML, a SQL query or a
  shell command. Build DOM with `textContent`, queries with parameters, processes with argument lists.
- Keep the spec's `serve` command working. It is run with `{port}` replaced by a port Forge chooses,
  on 127.0.0.1, and must start the built app without prompts. Serve the build output directory only,
  never the repository root — a server that hands out `/.git/HEAD` hands out the whole history.
- When upgrading a vulnerable dependency, upgrade to the fixed version and run the tests. Do not pin
  back to an older version to make a failing test pass.

## Deployment

- **Never run the deploy command, and never log in to anything** — no `wrangler login`, `gh auth`,
  `vercel login`, `npm login`, and no tokens in files or environment. Deploying publishes with the
  person's account, and only they decide when.
<!-- forge:only deploy -->

When the spec has a `deploy` block, the person deploys the project by pressing Deploy after
accepting it. You never deploy it.

- Do make the project deployable: write the host's config file the spec's command needs (for
  example `wrangler.toml`), make the build produce what the command uploads, and keep secrets out of
  that output.
- The deploy build runs with `FORGE_DEPLOY=1`. Nothing else Forge runs sets it.
<!-- forge:end -->
<!-- forge:only ads -->

## Ads

When the spec's `monetization.ads` is `test` or `live`, the app shows ads. Build them like this:

- **One ad module with a provider adapter** (`adsense`, `admob` or `custom`, as the spec says),
  so placements call one function and nothing else in the app knows which network is behind it.
- **Read the mode at build time from `FORGE_ADS_MODE`**, and the public ids from
  `FORGE_AD_<NAME>` (for example `FORGE_AD_PUBLISHER`). Anything other than `live` — including the
  variable being absent, which is how every gate, test and recording runs — must render clearly
  labelled test slots using the provider's documented test ids, and load no live ad script. A test
  run must never produce an impression or a click on a real account; networks close accounts for it.
- **Never hard-code a real publisher or ad unit id** in the source, and never commit one.
- Ads must not block or cover the thing the app is for: no interstitial before the first
  interaction, nothing over controls, nothing that shifts the layout under a tap. An offer the spec
  describes (a "buy a car from the dealer" ad, say) is a feature like any other: build it, list it
  under `features`, and record it.
- For live web ads, show a consent prompt before loading the network's script where the network
  requires one, and make the app work when consent is refused.
- Report every placement — where it is and when it shows — in your handoff.
<!-- forge:end -->

## Dependencies

- Prefer the standard library. Every dependency is supply-chain surface on a machine that runs
  unattended.
- Where a dependency is warranted, prefer Apache-2.0-licensed, widely-used libraries. The bar
  scales with how widely the artefact ships.
- Pin exact versions. No version ranges, no `latest`.
- Do not add a dependency to solve something a dozen lines of your own code would solve.

## Correctness

- **Never silently drop content the program cannot model.** If an input contains something you
  cannot represent, fail loudly, or record it explicitly — do not quietly discard it. Silent data
  loss is the defect class that survives testing and surfaces months later.
- Handle the error path. A tool that works on the happy path and throws a raw stack trace on
  malformed input is not finished.
- Prefer clear failure over a plausible-looking wrong answer.

## Assumptions

Most defects that survive testing are a true assumption that quietly stopped being true. Record
them, but record them where they can fail.

- **An assumption you can execute belongs in a test, not a comment.** A comment cannot fail. The
  build can. If the code assumes every case of an enum is handled, write the test that walks every
  case; if it assumes a file survives a cleanup step, assert the file is there afterwards.
- **Write assumptions at boundaries, not at lines.** The unit of an assumption is a place where
  your code depends on something it does not own — another module's states, a file format, a
  library's defaults, another program's flags. Per-line commentary drowns those few real ones in
  noise, and noise is what makes a comment stop being read.
- **A comment describing an assumption should name what would break it.** "Assumes the ledger only
  ever holds strings" is checkable by a reader. "Serialises the response" is not.
- **Before changing code, say what it assumed and whether that is still true.** If the assumption
  has changed materially, that is the change to discuss — not the edit you were about to make. If
  you are unsure, raise it rather than proceeding.
- When you add a case to a closed set — an enum constant, a phase, a status, a new field of a type
  that gets serialised — find every place that switches on it or consumes it before you finish. If
  no test would have caught you missing one, add that test.

## Performance, if it matters for this project

Where speed is part of what the program is for, test it so the test can only fail for a real reason,
never because the machine was busy: assert how the work scales or count the operations, and never
put a tight wall-clock number in an acceptance command. The `performance-tests` skill says how.

## Working economically

Everything you read stays in the conversation and is re-sent on every later turn of it. A session
is not billed for what it does, but for what it is carrying — so what you choose to look at is the
main thing under your control.

- **Read the result of a command, not the whole log.** Pipe builds and test runs through something
  that keeps the part you need: `npm test 2>&1 | tail -40`, `mvn -q verify 2>&1 | tail -60`,
  `... | grep -E "FAIL|ERROR" | head -30`. A passing build needs one line. Measured here: one
  vendor's sessions were 63% raw command output, with single outputs of 83 KB, while the other
  trimmed by habit and spent 4%.
- **Do not re-read a file you have already read this session** unless you changed it. If you cannot
  remember what is in it, that is a reason to write things down in your handoff, not to read it
  again.
- **Read the part you need.** A 450-line file read whole, four times, costs more than every command
  you will run. Grep for the symbol, or read the range.
- This is the same argument as the rule about small classes above, arriving from the other
  direction: files that do one thing are files you can read one of.

## Verifying your own work

- **Look at the output.** If the program renders something — a document, a page, an image — render
  it and inspect the result. Extracting text and asserting on the string is not evidence that the
  thing renders correctly.
- Run the program the way a user would, not only through unit tests.
- Produce the artefacts named in the spec's `evidence` list. They are what a human will actually
  look at.
- **If the program is interactive, record it, not just screenshot it.** A still frame cannot show
  whether the thing responds, animates, keeps time, or falls apart after the third click — which is
  exactly what a person wants to know about a game or a UI and cannot learn any other way without
  building it themselves. Reports play a recording inline.
- **Anything opened in a browser is checked at phone and desktop sizes** before you finish, not only
  at the size the tests happen to use: a layout fixed for one has broken the other here. The
  `check-viewports` skill has a script that screenshots each size and reports what is wrong.
- **A note can carry a screenshot.** When something the person wrote names a picture as
  `[image: .forge-images/<name>.png]`, the file is at that path in this repository. Open it and
  look before you change anything: it shows what they saw, on their screen, at their window size,
  and often says more than the words. Reproduce what it shows, and check your fix the same way.
  Never edit, move or commit that folder; Forge keeps it out of git.

## Features, and recording them

Report the things this project *does* as `features` in your handoff — a few words each, plus one
sentence saying what a recording of it should show:

```json
"features": [
  {"name": "Garage tuning", "demo": "pick a car, change the gearing, watch the dyno curve move"},
  {"name": "Pink slip wager", "demo": "win a race, see the opponent's car transfer to your garage"}
]
```

Propose what the program genuinely has, at the granularity a person would name if asked what it
does — not every method, and not the whole program as one item. You are proposing, not deciding:
each is approved or declined by the person the program is for.

**An approved feature owes a recording of itself, and the gate will not pass the project without
one.** It goes at `artifacts/feature-<slug>.webm`, where the slug is the feature's name lowercased
with non-letters turned into hyphens — `Pink slip wager` becomes
`artifacts/feature-pink-slip-wager.webm`. `.mp4` and `.gif` count too. The gate names the exact
path for each one it is still waiting for, so you never have to guess the spelling.

### The recording has to be produced by an acceptance command

**Forge deletes these before it runs the acceptance commands, and looks for them afterwards.** A
clip you make by hand and commit will be deleted and will not come back, and the project will not
pass. So wire the capture into the suite the spec already runs — a Playwright test, a JUnit test,
whatever the `acceptance` list executes.

They are also kept out of git: Forge excludes `artifacts/feature-*` from this repository, because
every gate records them afresh and each take would otherwise be a new binary in history. Do not
commit them, force-add them, or spend a commit "refreshing" them — the gate and the report read
them from disk.

The file is also read rather than merely counted. A container that is not a video, a single frame,
a clip under a second, or a canvas under 160x120 is refused with the reason — so there is no
shortcut in writing an empty file with the right name.

Record the real program doing the real thing. A clip of a mock, or of a screen the feature does not
actually drive, is worse than no clip — it is evidence of something that was never true. How to
record one for a browser app, a desktop UI or a command-line tool — with a tested GIF writer for the
last two — is the `record-feature` skill.

## Code shape

- Small classes, one responsibility each; methods that do one thing.

  Judged by behaviour, not line count. A long routing table or a block of prompt text is one thing
  done once; a short method that parses, validates and then writes is three. Never split something
  just to shorten it — an arbitrary split satisfies the rule and leaves the code harder to read,
  which is the opposite of the point. If a method is hard to name, that is the real signal it is
  doing more than one thing.
- Keep each source file under about 400 lines. Every review notes any file over that (tests
  excluded), and the person can send the note back to you. A file that long almost always holds
  several jobs, and it is more than a reviewer can read whole. Split along those jobs, never at an
  arbitrary line. Long explanations belong in a short comment at the decision and in a test that
  pins it, not in pages of Javadoc.
- Use Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@UtilityClass`) rather than writing
  boilerplate by hand.
- Match the idiom of the surrounding code rather than importing a personal style.
- Comment why, not what.

## One class that shows the shape of the whole thing

Every project has exactly one class whose job is to make the approach legible: the top-level flow,
in order, each step delegating to a named collaborator. Someone should be able to read that one
file and say "yes, that is how I would have done it" — or disagree — without opening anything else.

- It must be **real code on the executed path**, not a summary written beside it. A description
  that cannot go stale is worth more than one that can, and prose drifts away from the code it
  describes while a class on the executed path cannot.
- Each step reads as a sentence: a named call, at the same level of abstraction as its neighbours.
  If one line opens a file and the next runs the entire analysis, the abstraction levels are wrong
  and the reader learns nothing from the first.
- Push detail into the collaborators. This class names *what happens in what order*; it is not
  where the work is done.
- Name it for the process rather than the pattern — `PatternDiffer`, `ExpenseReport`, `Pipeline` —
  and say in its class comment *why* the flow is arranged this way, especially where the obvious
  order would not have worked.

Report its path as `overview` in your handoff. Forge checks it exists and shows it in the report.

## Scope

- Build what `SPEC.md` asks for. Note the `Non-goals` section and respect it.
- Do not restructure or "improve" areas the spec did not ask about.
- Leave the working tree clean: no scratch files, no commented-out experiments.
