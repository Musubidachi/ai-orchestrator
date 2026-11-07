# forge-security

The security check: secret scanning, dependency advisories through OSV, static analysis, the AI security review, the loopback probe of the running build, and the audit of what the agents did in their sessions.

**May use:** forge-core. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** Loopback only, secrets redacted, OSV gets package names and versions only. See `security/CLAUDE.md`.

**Tests:** No network in any test: OSV is faked, the probe runs a local server. Run them alone with `mvn test -pl forge-security -am`.

Package notes: `security/CLAUDE.md`.
