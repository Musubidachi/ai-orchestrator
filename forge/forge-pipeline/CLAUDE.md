# forge-pipeline

Running projects: the pipeline (one project through its phases), the jobs around it (what runs, when, on whose say-so), the pipeline's prompts, and the dashboard status.

**Packages:** `brief`, `brief.draft`, `brief.repair`, `jobs`, `jobs.button`, `jobs.polish`, `jobs.queue`, `jobs.stop`, `jobs.submit`, `jobs.unattended`, `jobs.work`, `pipeline`, `pipeline.run`, `pipeline.step`, `pipeline.turn`, `status`, `status.league`, `status.model`, `status.read`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** forge-report and below. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Don't grow `Orchestrator` or `JobRunner`; add a step or a job class. See `pipeline/CLAUDE.md` and `jobs/CLAUDE.md`.

**Tests:** `OrchestratorTest` and `JobRunnerTest` drive whole runs with stub agents and real git. Run them alone with `mvn test -pl forge-pipeline -am`.

Package notes: `jobs/CLAUDE.md`, `pipeline/CLAUDE.md`.
