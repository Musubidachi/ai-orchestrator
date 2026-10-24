# forge-deploy

Deploying an accepted project with the command its spec names, when a person presses Deploy, and nothing else. And publishing a family's versions to GitHub, when one is approved or a person presses Publish.

**Packages:** `deploy`, `deploy.github`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** forge-review and below. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Explicit click only; never a login, a credential or an install. See `deploy/CLAUDE.md`. Both kinds of going out share one set of preconditions, `Readiness`.

GitHub publishing is off unless `publishing.json` at the Forge root says `{"github": true}`, so a test suite on a machine where `gh` is logged in can never reach the account. One private repository per family: the initial version on `main`, which never moves; each enhancement on its own branch, made as it begins (at its first commit) and moved to its tip when approved. Never force-pushed. A tip is `RunState.finishedCommit`, or, for versions that finished before that was recorded, what the history and the version's own git log agree on (`Tips`); a disagreement is refused, not guessed. A record of what is on GitHub is kept beside the repository, `runs/NNN/github.json`, and is only ever updated from its own copy on disk, never an older one.

**Tests:** Deploy commands in tests only echo what a real CLI prints. GitHub tests push for real into bare repositories on disk (`FakeGitHub`); only the account is pretend. Run them alone with `mvn test -pl forge-deploy -am`.

Package notes: `deploy/CLAUDE.md`.
