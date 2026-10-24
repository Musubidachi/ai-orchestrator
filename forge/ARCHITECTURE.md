# Forge architecture

One page to decide where a change belongs. The details of each package are in its
`package-info.java`; the rules each area must not trade away are in the `CLAUDE.md` beside it.

## Modules

Each layer group is a Maven module, and a module can only use the modules below it, so Maven
refuses an upward import before any test runs. Each module has a `CLAUDE.md` saying what it is for.

| Module | Packages | Uses |
|---|---|---|
| `forge-app` | ui, cli, the page | everything; builds `forge/target/forge.jar` |
| `forge-pipeline` | brief, pipeline, status, jobs | forge-report |
| `forge-report` | report | forge-marketing |
| `forge-marketing` | marketing | forge-deploy |
| `forge-deploy` | deploy, deploy.github | forge-review |
| `forge-review` | review, feature, gate, state | forge-security |
| `forge-security` | security | forge-core |
| `forge-core` | json, process, agent, plan, project, git, spec | Jackson |

## Layers

Inside and across modules, a package may import only packages below it. Nothing imports upward, and no two packages import
each other, directly or around a loop. `LayersTest` enforces this on every build.

```
 13  cli                      command line: one class per command
 12  ui                       local editor and dashboard (HTTP on loopback), one class per route group
 11  jobs                     which projects run, when: JobRunner facade, queue, buttons, Polish, shutdown
 10  report · status          the HTML a person reads, the quick review's cards · what the dashboard
                             shows per project, and the vendor league
  9  marketing · design       marketing kit: drafts and checks, never publishes · design options:
                             each vendor draws looks in a folder of its own, a person picks one
  8  deploy · pipeline        Deploy button, explicit click only · one project through its phases
  7  brief   state            pipeline prompts · a run's persisted state.json
  6  gate                     Forge runs the acceptance commands itself: the judge of done
  5  feature                  approved features and their recordings
  4  review                   the cross-review and a person's rulings
  3  security                 secrets, advisories, static analysis, AI review, loopback probe
  2  git     spec             git operations · wishlist entries
  1  agent   plan   project   vendors and ledgers · an agent's handoff · paths and phases
  0  json    process          the Jackson mapper · running external programs
```

The number is the longest chain of imports beneath a package, not a rank of importance. Each name
above is a subject, and most are a small tree: 79 packages in all, none holding more than eight
classes.

## Inside a subject

A subject is split the same way everywhere, and the rule that decides where something goes is what
points at what:

| | |
|---|---|
| `x` | the entry point and nothing else — `Orchestrator`, `JobRunner`, `SecurityCheck`, `UiServer` |
| `x.model` | the records and enums that subject passes around, which call nothing |
| `x.<subject>` | a job of its own, with its own records beside it (`agent.vendor`, `jobs.button`, `report.section`) |
| below both | whatever several of them share — `jobs.work`, `pipeline.run`, `ui.http`, `report.shell`, `status.read` |

That last row is the one worth remembering. Shared machinery goes **below** the packages that use
it, never beside the entry point: `Orchestrator` and its steps both need `RunContext`, so
`RunContext` is in `pipeline.run` rather than next to `Orchestrator`. Putting it beside the entry
point makes the entry point and its parts point at each other, which is a cycle, and `LayersTest`
refuses it. Seven of those turned up while this was being split, each one a shared type sitting one
level too high.

A record must not call back into the code that fills it in. `AgentResult` classified itself through
`Signals`, `SecurityReport` read a layer's name off the scanner, `Complexity` mapped itself onto the
model ladder, and `Wishlist` ordered itself through `Ranking` — all four looked reasonable and all
four were the model reaching up. The behaviour moved to the thing that owns it.

## How a project moves through them

```
jobs.JobRunner ──► pipeline.Orchestrator ──► brief (prompt) ──► agent (turn) ──► plan (handoff)
      │                  │
      │                  └─► PlanStep: best model drafts plan.md, cheaper turns follow it
      │                  │
      │                  ├─► gate ──── passes? ──► security ──► review ──► state (saved after every step)
      │                  │
      │                  └─► progress callback ──► report (rewritten as the run proceeds)
      │
      ├─► status ──► ui / dashboard ◄── a person rules ──► jobs.Submissions ──► ACCEPTED
      │
      └─► deploy / marketing / security rescan / design options / Polish / Play ◄── only on a click
```

## Where things go

| You are adding… | Put it in | Not in |
|---|---|---|
| a prompt for a pipeline turn | `brief` | `pipeline` |
| a prompt a capability sends itself | beside it (`review.ReviewBrief`, `security.SecurityBrief`) | `brief` |
| a new vendor | `agent` (an `Agent` plus its `*Args`) | `pipeline` |
| a new security layer | `security`, registered in `SecurityCheck` | `pipeline` |
| something every run has on disk | `project.RunPaths` | a string built at the call site |
| something `state.json` records | `state.RunState` | `project` |
| a page section | `report`, one class per section | `ProjectReport` itself |
| something said aloud about a project | `walkthrough` | a section class |
| a wording rule for code said out loud | `tour.say` | inside the page or the video |
| another way to play a tour back | `tour.show` | `tour`, which decides what it says |
| an HTTP endpoint | a `*Routes` class in `ui`, calling into `jobs` | logic inside the handler |
| a pipeline phase | a step class in `pipeline`, dispatched from `Orchestrator.step` | inside `Orchestrator` |
| a rule about which model runs something | `spec.ModelChoice` | a model name at the call site |
| finding out what a vendor can actually run | `agent.catalog` | a list written into the code |
| a dashboard button | a small job class in `jobs`, delegated from `JobRunner` | inside `JobRunner` |
| a command | a `*Command` class in `cli`, dispatched from `Forge.main` | inside `Forge` |
| a dashboard field | the status group it belongs to, built in that group's `of` | `JobStatus` itself |
| a record several packages pass around | that subject's `model` package | beside the logic that happens to build it |
| something two sub-packages both need | a package below both of them | beside the entry point |

## Rules the build enforces

The rule tests live in `forge-app` (package `architecture`), the one module that can see every
other, and sweep every module's sources.


| Test | Forbids |
|---|---|
| `LayersTest.everyPackageDocumented` | a package without a `package-info.java` and its `Depends on:` line |
| `LayersTest.importsMatchDocumentation` | importing a package the `Depends on:` line does not name, or naming one no longer imported |
| `LayersTest.layersPointDown` | any dependency cycle between packages |
| `ArchitectureTest.packagesStaySmall` | a package holding more than eight classes |
| `ArchitectureTest.noBuilderOnRecords` | `@Builder` on a record, which Eclipse's Lombok cannot generate |
| `ArchitectureTest.noNestedTypes` | a type declared inside another type (main code) |
| `ArchitectureTest.noInlineQualifiedNames` | `dev.fmangoni.forge.x.Y` written out in code instead of imported |
| `JsonRulesTest.noPrivateMappers` | a second Jackson mapper outside the listed exceptions |
| `JsonRulesTest.noInPlaceJsonWrites` | persisting JSON with a plain write a kill could truncate |
| Maven itself | a module importing from a module above it |

Letting a package use another package is a deliberate one-line edit to its `Depends on:` line,
made in the same change and visible in review. If that edit would create a cycle, move the shared
type down a layer. That is how `state` was split out of `project`.

## Conventions

- Every type in its own file. Records that group fields get their own file too (`DiffSection`).
- Records for plain data; a `@Value` class when it needs a Lombok builder. Lombok builds a
  record under javac and not under Eclipse, so `@Builder` on a record compiles here and
  breaks in the IDE — the six `status.model` groups were found that way.
- Small classes, each with one responsibility; split by responsibility, not by line count.
- Small packages, each one subject: eight classes is the cap, and it is enforced.
- Import, don't qualify inline.
- Persisted JSON keeps its field names. Moving or renaming a class never changes what is on disk.
- Every fix gets a test that fails without it. `verify.bat` (Java and Playwright) is the answer to
  "did I break something".
