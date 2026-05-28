# Task: `sbt stryker` OOMs before it can run (mutant explosion)

**Status:** blocked / needs fix. **Filed:** 2026-05-28.

## Symptom

`sbt stryker` "takes ages to start" and then dies. It never reaches the
test-running phase — it crashes during stryker4s's **mutant generation /
instrumentation** with `java.lang.OutOfMemoryError: Java heap space`.

The aggressive per-mutant timeout currently in `stryker4s.conf`
(`timeout = 1000`, `timeout-factor = 1.0`) is **irrelevant to this blocker** —
no mutant ever gets to run, so the timeout never applies. Leave it or remove it;
it does not affect this crash.

## Root cause

Stryker is generating a wildly excessive number of mutants. From the 4 GB run:

```
[info] Found 69 file(s) to be mutated.
[info] 230291 mutant(s) generated. Of which 34 mutant(s) are excluded.
```

- **230,291 mutants** is the actual disease. Generating + pretty-printing that
  many mutated syntax trees is what exhausts the heap.
- Only **34** mutants were excluded, so the negation globs in `mutate` are
  **not effectively excluding** the chess engine. The whole bitboard engine
  (`Magics`, `Attacks`, `MoveGen`, `Position`, `Search*`, `Eval*`, `Zobrist`,
  `OpeningBook`, `PolyglotZobrist`) — including the giant magic/attack/PST
  tables — is being mutated. That is where the 230 K comes from.
- **69 files** are being mutated, but `src/main` only contains **33** `.scala`
  files (49 incl. tests). So the include glob `**/main/scala/**/*.scala` is also
  matching more files than intended (generated/managed sources or a wrong
  `base-dir` are the likely culprits — confirm).

In short: the `mutate` globs in `stryker4s.conf` are both **over-including**
(69 > 33 files) and **failing to exclude** (34 of 230 K). The fix is to cut the
mutant set down, not to add memory.

## What was ruled out

- **Not a per-mutant timeout problem.** Crash is pre-test.
- **Not just an under-provisioned heap.** Two runs:
  - Default sbt heap (**1 GB**): 95–99 % time in GC, then OOM in
    `stryker4s.mutants.findmutants.MutantMatcherImpl` (mutant *discovery*).
  - `sbt -mem 4096` (**4 GB**): got further — printed the 230 K-mutant line
    above — then OOM in `scala.meta.internal.prettyprinters.TreeSyntax`
    (pretty-printing mutated source). 4× the heap only moved the OOM
    downstream. The lever is mutant count, not memory.

## Environment

- sbt 1.12.9, Oracle JDK **Java 25.0.1**, Scala **3.8.3**
- `sbt-stryker4s` **0.20.4** (`project/plugins.sbt`)
- Service root `.jvmopts` sets only `--add-opens=...`, no `-Xmx` → sbt default
  heap was 1 GB.
- Stryker runs inside the sbt JVM (it's an sbt task, not forked), so the **sbt
  JVM heap** is what matters (`sbt -mem N` or `.jvmopts -Xmx`).

## Recommended fix (in priority order)

1. **Make the `mutate` exclusions actually match.** The current negations use
   the full package path, e.g. `!**/maichess/engine/chess/*.scala`. Only 34
   mutants were excluded, so these are not matching. Verify the glob form
   stryker4s 0.20.4 expects (paths are matched relative to `base-dir`; try
   `!**/chess/*.scala` or `!src/main/scala/maichess/engine/chess/*.scala`).
   Iterate until the "excluded" count reflects the whole `chess/` tree and the
   total mutant count drops by orders of magnitude. Quick inner loop:
   `sbt -mem 4096 stryker` and read the "Found N file(s)… / M mutant(s)
   generated" line — you do not need a full run to see whether excludes work.
2. **Find out why 69 files are matched** when `src/main` has 33. Inspect what
   `**/main/scala/**/*.scala` resolves to (generated/managed sources, or a
   `base-dir` pointing above the service). Constrain `base-dir` and/or `mutate`,
   or add an explicit `files` allow-list.
3. **Keep `chess/` excluded** (mirrors the scoverage exclusion in `build.sbt`:
   `maichess\.engine\.chess\.(?!basic\.).*`). Intended mutation targets are
   `domain/`, `service/`, `grpc/`, and `chess/basic/` only (~1,276 lines, 14
   files). That set alone should produce a tractable mutant count.
4. Only after the count is sane, restore a sensible timeout
   (`timeout-factor = 1.5`, default `timeout`) and run the full suite. If memory
   is still tight, bump the sbt heap via `.jvmopts` (`-Xmx4g -XX:+UseG1GC`).
5. If a tractable mutant set still OOMs in `TreeSyntax` pretty-printing, suspect
   a stryker4s 0.20.4 / scalameta pathology on Scala 3.8.3 and try bumping
   `sbt-stryker4s`.

## Repro

```bash
cd services/maichess-engine-service
sbt -mem 4096 stryker     # watch the "Found N file(s)… / M mutant(s)" line
```

Full logs from the two diagnostic runs were captured at `stryker-run.log`
(1 GB, OOM in MutantMatcher) and `stryker-run2.log` (4 GB, OOM in TreeSyntax).
