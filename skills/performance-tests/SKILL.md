---
name: performance-tests
description: How to test speed without flaky acceptance commands - assert on how work scales, count operations, wide wall-clock margins, record numbers as evidence. Use when the program processes large inputs, serves requests or renders frames, or when writing any test that measures time.
---

# Performance, if it matters for this project

Where speed is part of what the program is for — it processes large inputs, serves requests, or
renders frames — test it. But test it in a way that can fail for a real reason and not for the
weather, because the acceptance commands decide whether the project is accepted at all, and a
check that fails when a virus scanner wakes up will park working code.

- **Assert on how the work scales, not on how long it takes.** Run the same operation at two sizes
  and assert the ratio: ten times the input should not be a hundred times the work. That catches
  the accidental quadratic loop, which is the performance bug that actually ships, and it gives the
  same answer on a fast machine and a loaded one.
- **Count the operations you control.** Queries issued, files opened, requests made, allocations in
  a hot path. "One query, not one per row" is exact, deterministic, and fails for a reason you can
  act on. A wall-clock number is none of those things.
- **A wall-clock budget needs a wide margin and a reason.** If the requirement really is "under
  200ms", assert under a second and say in a comment what the real target is. Treat any threshold
  you would not bet on while a build runs in the background as too tight.
- **Never assert a lower bound on speed** — that a thing is not *too fast* — to make a flaky test
  pass in both directions.
- Record the actual numbers as evidence: a benchmark's output written to a file is something a
  person can read and compare, without any of it being able to fail the build.

Forge already times every acceptance command and reports how each one compares to its own history,
so a general slowdown will surface without you asserting anything.
