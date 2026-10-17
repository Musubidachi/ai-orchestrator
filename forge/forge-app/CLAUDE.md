# forge-app

What a person touches: the local editor and dashboard (`ui`), the command line (`cli`), and the page itself (`src/main/resources/ui/index.html`). Builds `forge/target/forge.jar` and the coverage report.

**Packages:** `cli`, `cli.command`, `cli.support`, `ui`, `ui.editor`, `ui.http`, `ui.request`, `ui.route`, `ui.suggest`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** everything. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Handlers stay thin; every endpoint is a read or a write. See `ui/CLAUDE.md`. The architecture rules (`architecture` tests) live here because only this module sees every other.

**Tests:** UI tests here; the Playwright suite in `ui-tests/` runs against the built jar. Run them alone with `mvn test -pl forge-app -am`.

Package notes: `ui/CLAUDE.md`.
