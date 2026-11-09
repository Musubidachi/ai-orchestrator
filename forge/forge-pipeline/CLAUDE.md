# forge-pipeline

Running projects: the pipeline (one project through its phases), the jobs around it (what runs, when, on whose say-so), the pipeline's prompts, and the dashboard status. Also picture batches (`pictures`): pictures an agent outside Forge draws, which Forge checks, deals out for review and places.

**May use:** forge-report and below. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Don't grow `Orchestrator` or `JobRunner`; add a step or a job class. See `pipeline/CLAUDE.md` and `jobs/CLAUDE.md`.

**Tests:** `OrchestratorTest` and `JobRunnerTest` drive whole runs with stub agents and real git. Run them alone with `mvn test -pl forge-pipeline -am`.

Package notes: `jobs/CLAUDE.md`, `pipeline/CLAUDE.md`.
