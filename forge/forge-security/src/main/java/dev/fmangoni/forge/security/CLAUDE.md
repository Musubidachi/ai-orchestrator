# security

Runs every layer over the project at HEAD and keeps one `SecurityReport` per commit in
`runs/NNN/security/security.json`. `SecurityCheck` is the entry point; each layer returns a
`LayerResult` (ran, skipped, not installed, failed) with its `SecurityFinding`s.

## Must not be traded away

- **Loopback only.** `LiveProbe` refuses any non-loopback address before sending anything, and
  `process.LocalServer` binds only 127.0.0.1 / ::1. Never add a remote target.
- **Secrets are redacted.** `SecretScan.redact` keeps a short prefix and the length. No report,
  log, prompt or test assertion may contain a secret's value. Build test keys at runtime (see
  `SecretScanTest`) so no credential-shaped literal sits in a source file.
- **OSV receives package names and versions only.** Never code, paths or project names. Note that
  `npm audit`, which the npm layer runs, is a different matter: it sends the lockfile's dependency
  tree to the npm registry — names and versions again, but the whole tree, and not to OSV.
- **Serious means a person must rule.** `SecurityReport.forRuling()` feeds `review.ReviewItem`.
  Dev-only dependency advisories and secrets that survive only in history are not serious, but
  history secrets still need a ruling. They cannot be repaired, only revoked.
- **One repair turn, then a person decides.** The pipeline runs `brief.SecurityRepairBrief` once.
  Never loop it.
- A missing scanner is `not installed`, never a pass and never an install.
- **The session audit judges what agents did, not the code** (`session.SessionAudit`). It reads
  every command run and file written from the vendors' logs and flags what the house rules forbid:
  pushing, publishing, installing, system settings, agent settings and credentials, writing or
  deleting outside the project, and stopping processes by name or by port. Stopping a process by
  number is high only when the number came from an earlier port lookup's output (the kill that
  closed a person's game on 4317); stopping the server it just started is not flagged. Its findings
  are never serious, since nothing can repair a session, but a high one needs a ruling, named by
  its command or folder so the name is the same on every check. Calibrate any new rule against
  every run's logs before adding it: `git stash push` once read as a push.

## Adding a layer

Write a class that returns a `LayerResult`, add it to `SecurityCheck.run` inside `guarded(...)` so
one failing layer cannot sink the others, and give it a `LAYER` name that the report shows.

## Tests

`SecretScanTest`, `DependencyAuditTest` (OSV faked with an in-memory `OsvClient`),
`StaticAnalysisTest`, `LiveProbeTest` (a local server with planted files), `SecurityReviewTest`
(stub agent). None of them touches the network.
