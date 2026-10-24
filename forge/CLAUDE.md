# Working on Forge itself

Forge is the orchestrator in this folder. The projects it builds live in `../runs/NNN/repo`. Their
agents read `../house-rules.md`, never this file. Keep it that way: do not put a `CLAUDE.md` in the
repository root, because every project's build session sits under that root and would load it.

Read `ARCHITECTURE.md` first: the modules, the layers, where a change goes, and the rules the build
enforces. Each module folder (`forge-core` … `forge-app`) has a `CLAUDE.md`; each package's
`package-info.java` says what belongs in it and what it may import; areas with invariants of their
own have a `CLAUDE.md` beside their code.

## Build and verify

- `verify.bat` from the repository root runs the Java tests and the Playwright suite, then
  redeploys the editor. Run it from PowerShell: `& cmd /c "C:\repos\ai-orchestrator\verify.bat"`.
  On a failed build it restores `forge/last-good/forge.jar`, so the editor keeps running.
- Quick loop, from `forge/`: `mvn -q test-compile`, then `mvn -q test -Dtest=SomeTest -Djacoco.skip=true`
  (finds the test in whichever module has it), or `mvn test -pl forge-review -am` for one module.
- The fat jar is still written to `forge/target/forge.jar`, which every script uses.
- Test against temporary directories or fixture roots, never the live `runs/`.

Work cheapest first. Compiling the module you touched catches most of it in seconds, its own tests
in about a minute, the guards in another minute, and `verify.bat` in six. Going straight to
`verify.bat` turns a typo into a six-minute round trip.

Read what it generated. For anything that produces prose, a page or a recording — a report, a
walkthrough, a tour — a passing test only says the shape is right. The worst bug in the tour
(narrating a ternary as though the method returned its own test) was green under every test that
existed and obvious in the first paragraph of real output. Generate a sample and read it.

## Traps that have each cost a session

Every one of these has happened, and each failed *quietly* — the command reported success, or said
nothing at all, while doing nothing or the wrong thing.

- **`-Dtest=A+B` runs no tests and passes.** Surefire wants commas: `-Dtest=A,B`. With `-q` a green
  run prints nothing, so silence is not proof. Confirm the `Tests run:` line — drop `-q` and
  `grep "Tests run:.*in dev"` — before believing a suite passed.
- **Never `package` (not only `clean`) while the editor is running.** It runs from
  `forge/target/forge.jar`; overwriting it leaves a process that cannot even answer `/api/quit`, so
  the usual recovery is itself blocked. Use `verify.bat`, or `quiesce.ps1` → build → `restart.ps1`.
  `quiesce.ps1` is also the check that no agent turn is in flight — never kill one that is.
- **Never run two Maven builds at once.** A `mvn package` started while a background `verify.bat`
  is still going writes the same `target/` directories from two processes, and the result is a
  module that cannot find its own classes: `NoClassDefFoundError: dev/fmangoni/forge/state/RunState`
  while running that very module's tests. It reads like a broken test and is a broken directory —
  `mvn clean` and one build at a time. Check for a running build before starting one.
- **PowerShell splits a bare `-D` argument at the dot.** `mvn -q package -Djacoco.skip=true` runs
  the lifecycle phase `.skip=true` and fails. Quote every one: `mvn -q package "-DskipTests"
  "-Djacoco.skip=true"`.
- **Heredocs mangle backslashes and quotes.** A `\n` inside a python heredoc arrives as a newline
  and has shipped two real bugs. Write Java, JavaScript and anything with escapes using the editor's
  own file-writing tool, never a shell heredoc, `printf` or `sed`.
- **A new class has four rules the compiler does not know.** No nested types; at most eight classes
  per package; every package needs a `package-info.java` ending in a `Depends on:` line; and adding
  an import means updating *the importing package's* line, which is the one that gets forgotten.
  Check with `mvn test -pl forge-app -am -Dtest=LayersTest,ArchitectureTest "-Djacoco.skip=true"`
  before the full suite.
- **`@Builder` on a record compiles under javac and breaks in Eclipse.** Lombok generates nothing
  there, so the build is green here and the Problems view is full of undefined constructors. Use a
  `@Value` class with `@Builder`. `noBuilderOnRecords` guards it.

## Never

- Use an API key, or a credential or OAuth code someone pastes. Forge uses the vendors'
  subscription CLIs as they are logged in.
- Log in, install a tool, spend money, or run a remote installer on anyone's behalf.
- Deploy, publish or post anything except when a person clicks the button for it. The one
  exception is GitHub publishing, and only once a person has switched it on in `publishing.json`:
  an enhancement's branch is pushed to its family's private repository as the enhancement begins,
  and approving a version pushes it. The person asked for both, and the queue may begin an
  enhancement with nobody at the screen. Never more than that: no new repository, no shelf, and
  no deploy without a click.
- Let an agent work outside its `runs/NNN/repo`.
- Treat an agent's own claim of success as done. The gate decides.
