# forge-core

The ground floor everything else stands on: JSON, running external programs, the agent CLIs and their ledgers, specs and the wishlist, git, and a run's paths and phases.

**Packages:** `agent`, `agent.catalog`, `agent.ledger`, `agent.ledger.model`, `agent.model`, `agent.policy`, `agent.policy.model`, `agent.stream`, `agent.vendor`, `agent.vendor.model`, `git`, `json`, `plan`, `process`, `process.local`, `process.output`, `project`, `spec`, `spec.model`, `spec.rank`, `spec.tier`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** nothing but Jackson. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** `agent.catalog` reads the caches the vendor CLIs keep for their own model pickers, so the ladder is built from what a subscription can actually reach rather than from a list someone wrote down. Never read a vendor's `auth.json` or anything else holding a credential; availability is public, the account is not.

Keep it free of any capability. If a type here starts needing the gate, a review or a report, it belongs higher up, or the thing it needs belongs down here.

**Tests:** `LockHolder` (a second JVM holding a repository's lock) is published in this module's test-jar. Run them alone with `mvn test -pl forge-core -am`.

Package notes: `agent/CLAUDE.md`.
