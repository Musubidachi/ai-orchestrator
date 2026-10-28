# agent

Drives the vendors' own CLIs (Claude Code, Codex, Antigravity) headlessly, and tracks what each
has left to spend. `Agents` resolves an `AgentKind` to a configured `Agent`, so the pipeline never
constructs one.

## Must not be traded away

- **Subscription CLIs only.** Never an API key, never a pasted token or OAuth code, never a login
  run on anyone's behalf. Agents are started with the vendors' API-key variables withheld
  (`vendor.SubscriptionOnly`), so a key exported for something else cannot switch a CLI to metered
  billing.
- **An agent turn gets none of the person's own tools.** Claude runs with `--strict-mcp-config`, so
  the MCP servers configured for personal use (mail, chat, source hosting) are not loaded. Codex is
  started with `--disable` for each feature `vendors.json` lists — by default computer use, browser
  use, apps and plugins — because it was using them: 2,223 plugin calls in Forge's logs, 1,250 of
  them driving the desktop. That removes the servers plugins provide; one named directly in
  `~/.codex/config.toml` stays until `vendors.json` lists it under `disabledMcpServers`
  (`codex mcp list` shows what is configured, and with these flags what is left).
- **A repository never configures the agent working in it.** Claude runs with
  `--setting-sources user`: a project's `.claude/settings.json` is a file any agent can write, and
  its hooks run outside every sandbox when the next Claude session starts there (confirmed on
  2.1.293). `CLAUDE.md` and `.claude/skills` still load. Codex reads a project's own config only
  for folders trusted in `~/.codex/config.toml`, which `runs/` is not; keep it that way.
- **Codex is told its sandbox on every call.** `-s` on a fresh turn, `-c sandbox_mode=…` on a
  resumed one — a resumed session does not keep it (0.159.3) — and `-c windows.sandbox=…` when
  `vendors.json` names one. Which Windows sandbox works is a fact about the machine: check with
  `codex sandbox -c windows.sandbox=unelevated cmd /c echo ok`, which calls no model, and
  `codex doctor`.
- **After a CLI update, run the live tests** before trusting a build to it:
  `mvn test -pl forge-core "-Dexcluded.groups=" "-Dtest=LiveAgentTest" "-Dforge.codex.sandbox=unelevated" "-Dforge.codex.mcpOff=node_repl"`.
  They spend a little quota and are the only thing that can say what the installed CLI does.
- **Classify from the CLI's own words** (`stream.CliWords`): stderr, non-event lines and failure
  events — never the model's prose or the files it read, on a failed call as on a clean one.
- **Command lines live in `*Args` classes**, apart from the code that runs them, so
  `AgentArgsTest` and `AntigravityArgsTest` can pin every flag.
- **`vendors.json` decides how many calls reach a vendor at once** (`ThrottledAgent`), one by
  default. The limit sits on the call, not the project.
- **Usage comes from the vendor's own closing event** (`Usage`), not from an estimate.

## Adding a vendor

An `AgentKind` value, an `Agent` implementation and its `*Args`, a case in `Agents`, and `Signals`
patterns for its quota and error messages. Pin the command line in a test.

## Tests

All run against stub processes except `LiveAgentTest`, which needs the real CLIs and skips
without them.
