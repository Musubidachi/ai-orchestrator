# forge-deploy

Deploying an accepted project with the command its spec names, when a person presses Deploy, and nothing else.

**Packages:** `deploy`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** forge-review and below. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Explicit click only; never a login, a credential or an install. See `deploy/CLAUDE.md`.

**Tests:** Deploy commands in tests only echo what a real CLI prints. Run them alone with `mvn test -pl forge-deploy -am`.

Package notes: `deploy/CLAUDE.md`.
