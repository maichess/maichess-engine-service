# maichess-engine-service

See `CLAUDE.md` for architecture, contracts, and design notes.

## Mutation Testing (Stryker4s)

Stryker4s is wired up as an sbt plugin (`project/plugins.sbt`). Configuration
lives in `stryker4s.conf`. Mutation exclusions mirror the scoverage
exclusions: `Main.scala`, `TablebaseClientLive.scala`, and the top-level
`maichess.engine.chess.*` files (kept because their timing-dependent branches
can't be exercised deterministically). `chess/basic/` is still mutated.

```bash
# Run mutation tests from the service root
sbt stryker
```

After the run, open `target/stryker4s-report-<timestamp>/index.html` in a
browser to inspect surviving mutants.

To bump the Stryker4s version: edit `project/plugins.sbt`.
