# deploy

Runs the deploy command a spec names, for an accepted project, when a person presses Deploy.
`Deployer.blockedReason` says why not, and `Deployer.deploy` runs it and records a `Deployment` in
`runs/NNN/deployments.json`.

## Must not be traded away

- **Only on an explicit click.** Nothing schedules, retries or chains a deploy.
- **Preconditions:** accepted; last gate passed; a security report exists, every item for ruling is
  ruled on, and none was sent back; every `requires` tool is on PATH.
- **Forge never logs in, installs or holds credentials.** A CLI that is not logged in is reported
  as `NOT_LOGGED_IN`, with a note that the person logs in themselves.
- **Only safe ids reach the environment.** `FORGE_DEPLOY=1`, `FORGE_ADS_MODE`, and
  `FORGE_AD_<NAME>` for ids that pass the safe-character check. Ad ids go out only when the spec
  says `ads: live`.
- A failed health check is `UNHEALTHY`, never `DEPLOYED`.

## Tests

`DeployerTest` uses deploy commands that only `echo` what a real CLI prints: no network, no
account.
