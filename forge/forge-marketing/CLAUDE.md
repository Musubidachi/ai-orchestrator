# forge-marketing

The marketing kit: one agent turn, then deterministic checks of the launch copy, landing page and ad campaign drafts. And design options: each vendor draws a few looks for a project in a folder of its own, and a person picks one to build.

**Packages:** `design`, `marketing`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** forge-deploy and below. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Drafts only: Forge never publishes, posts or spends. See `marketing/CLAUDE.md`. Both capabilities work the same way and for the same reason: an agent turn in a folder beside the repository, never in it, followed by a check of what it left. A designer's pages are shown only if they are plain option files that load nothing from the network, and the only thing that reaches a repository is the one page a person chose.

**Tests:** Stub agents; hand-written kits, one per rule; `DesignStudioTest` for the design options. Run them alone with `mvn test -pl forge-marketing -am`.

Package notes: `marketing/CLAUDE.md`.
