# Failing test analysis — `EngineServiceLive / bestMove — bitboard engine (Base bots) / finds the mate-in-one`

## Symptom

```
EngineServiceLive / bestMove — bitboard engine (Base bots) / finds the mate-in-one
  ✗ "e5e4" was not equal to "d8h4"
  move == "d8h4"
  move = "e5e4"
```

Test (`src/test/scala/maichess/engine/EngineServiceSpec.scala:42-47`):

```scala
private val mateIn1Fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"
test("finds the mate-in-one") {
  for result <- svc.bestMove(mateIn1Fen, "bullet", None)
  yield
    val (move, _) = result
    assertTrue(move == "d8h4")
}
```

`mateIn1Fen` is the Fool's-mate position (1.f3 e5 2.g4); Black to move; `Qd8-h4#`.
`"bullet"` → `BotRegistry`: `Fixed(100L)` → `Search.bestMove(pos, 100)`.

## Working theory of the root cause (primary)

**The test is timing-dependent, and the dominant suspect is that `Search.bestMove` starts its 100 ms clock *before* warm-up-sensitive work — in particular the lazy `Magics.init()` magic-bitboard table generation.**

`Search.bestMove` (`chess/Search.scala:37-51`):

```scala
def bestMove(pos: Position, timeLimitMs: Long): (Int, Int) =
  deadline  = System.currentTimeMillis() + timeLimitMs   // <-- clock starts HERE
  ...
  val initCnt = MoveGen.generate(pos, moveBuf(0))        // <-- first reference to Magics; triggers Magics.<clinit>
  rootBest = Move.None
  var i = 0
  while i < initCnt && rootBest == Move.None do
    if LegalCheck.isLegal(pos, moveBuf(0)(i)) then rootBest = moveBuf(0)(i)   // fallback = first legal pseudo-move
    i += 1
  var depth = 1
  while depth < 64 && !timeUp() do                       // <-- if already past deadline, body never runs
    negamax(pos, depth, -INF, INF, 0)
    depth += 1
  (rootBest, rootScore)
```

- `MoveGen.generate` → `genSliders` → `Magics.bishopAttacks` is the first thing that references the `Magics` object, so the `locally { init() }` static initializer (`chess/Magics.scala:20,91-103`) runs *after* `deadline` has been set.
- `Magics.init()` does PRNG trial-and-error magic-finding for all 64 squares × 2 piece types (rook tables up to 4096 entries, bishop up to 512). On a cold JVM (interpreted bytecode, no JIT yet, plus class-loading of `BB`, `Attacks`, `Zobrist`, `MoveGen`, `LegalCheck`, `Eval`, …) this can easily consume more than 100 ms.
- If that happens, by the time the `while depth < 64 && !timeUp()` loop is reached, `timeUp()` is already `true`, so **not even depth 1 runs**. `bestMove` returns `(rootBest, rootScore)` where `rootBest` is the *fallback* — the first pseudo-legal move that passes `LegalCheck.isLegal`.
- In `mateIn1Fen`, move generation order is pawns first; the first pawn push enumerated is `e5→e4` (target square 28 is the lowest index among Black's push targets). So the fallback is exactly **`e5e4`**, and `rootScore` is **0** — matching the observed failure precisely.

Why it can be intermittent / order-dependent:
- zio-test runs the tests inside a spec in parallel by default. Whichever `bestMove`/`Position`/`MoveGen` call first touches `Magics` pays the `init()` cost; the JVM's class-init lock parks every other thread inside `Magics.<clinit>` until it finishes — so a *different* test's `bestMove` can have its clock running while it is blocked there.
- The earlier test `"returns a valid UCI move for the starting position"` also calls `svc.bestMove(startFen, "bullet", None)`, so in a purely sequential run `Magics` would already be warm by the time `"finds the mate-in-one"` runs — which is why the other Base-bot tests don't notice the problem (they only assert `move.length` is 4–5, or assert a *failure*, both of which the fallback path still satisfies).

The mate-in-one test is the only Base-bot test that asserts a *specific* best move, i.e. the only one that actually requires the iterative-deepening loop to complete ≥ depth 2.

### Why "find mate-in-one" needs depth ≥ 2 here

At depth 1 the root just compares static evals (via `quiesce` at depth 0; no captures available for either side in this position):
- after `e5e4`: Black pawn e5→e4 gains PST → eval ≈ +75 cp for Black
- after `Qd8h4`: queen leaves home, no PST change, no other gain → eval ≈ +70 cp for Black

So depth-1 prefers `e5e4`. The mate is only seen at depth ≥ 2, where `negamax` on the post-`Qh4` node hits `legal == 0` with `isInCheck` true and returns `-(MATE - ply)` (`Search.scala:97-98`). I traced this path (FEN parse, generation of `Qd8-h4` by `genSliders` for the queen, `LegalCheck.isLegal(Qd8h4)` = true, white has zero legal replies, `isAttacked(e1, Black)` via `Magics.bishopAttacks(4, occ)` correctly includes h4 because d2 is the only blocker on the e1 mask) and **found no logic bug that would make the engine prefer `e5e4` once depth ≥ 2 completes.** Conclusion: the engine is *correct for this position* but the **100 ms budget is sometimes not honoured / not enough on a cold JVM**, so the iterative-deepening loop produces nothing and the fallback move is returned.

## Is it a good test?

Partially.
- The *intent* — "the engine finds a mate in one" — is a legitimate, valuable regression test.
- The *implementation* is fragile: it binds the assertion to a wall-clock-limited bot (`bullet`, 100 ms fixed) and asserts an exact UCI move. That makes correctness depend on JVM warm-up, host speed, GC pauses, and zio-test's parallel scheduling — none of which the engine controls. A non-flaky version would either:
  - call `new Search().bestMoveAtDepth(pos, 2 /* or 3 */, Array.empty)` directly and assert the move (deterministic, no clock), or
  - use a bot / overridden time budget large enough that depth 2 is guaranteed even cold (e.g. pass `Some(...)` to push `computeMoveTime` up, or test against `classical`), or
  - assert on the *evaluation* being a mate score rather than the exact move.
- Per `CLAUDE.md` ("Do not change tests to make them pass — only change tests when the requirement they cover changes"), the intended fix direction is probably to make the **engine** reliably return within (and use) its time budget, or to make `bestMove` robust to a clock that has already expired (always run at least depth 1, and don't start the deadline until after one-time table init). Worth confirming with the maintainer whether the test itself may be made deterministic.

## Candidate fixes (not yet applied — for discussion)

1. **Start the deadline after one-time setup** in `Search.bestMove`: trigger `Magics`/table init (or just move `deadline = …` to right before the `while` loop, after `MoveGen.generate` and the fallback loop). Cheap, removes the cold-`Magics` interaction.
2. **Always complete at least one (ideally two) iterations** regardless of the clock: change the loop to `while depth < 64 && (depth <= 2 || !timeUp())` (or run depth 1–2 unconditionally, then start checking `timeUp()`). Guarantees a mate-in-one is found even under a starved budget.
3. **Force `Magics` initialization eagerly** (e.g. a `Magics` reference in `Main`/`EngineServiceLive` construction, or a warm-up call) so the cost is never inside a timed search.
4. (Test-only, if the maintainer allows) make the test deterministic via `bestMoveAtDepth`.

## Secondary suspects considered and (tentatively) ruled out

- **`Search.scala:90` — `if ply == 0 then rootBest = mv; rootScore = sc`**: in Scala 3 this parses as two statements, so `rootScore = sc` runs unconditionally inside the `sc > best` block (even at ply > 0), which can leave `rootScore` holding a deep-node score when a later root move doesn't improve `best`. **This corrupts the returned *score* but not the returned *move*** (`rootBest` is only assigned under `ply == 0`), so it cannot by itself explain a wrong `move`. Still a real bug worth fixing separately.
- **Zobrist / transposition-table collisions** producing a bad `EXACT` cutoff at the root: the XOR-key check (`ttKeys(idx) == pos.hash ^ tdat`) requires a full 64-bit Zobrist collision — astronomically unlikely; not a plausible cause of a reproducible failure.
- **Magic-bitboard correctness**: traced `bishopMask(e1)` = {d2,c3,b4,f2,g3}, `slideAttacks(e1, {d2}, bishopDirs)` = {f2,g3,h4,d2} → `bishopAttacks(4, occ)` includes h4 → `isAttacked(e1, Black)` true → mate detected. Looks correct.
- **Move generation of `Qd8-h4`**: `genSliders` for the queen uses `Magics.bishopAttacks | Magics.rookAttacks`; the d8 NE diagonal (e7/f6/g5 empty) reaches h4, emitted as a `FlagQuiet` move. Generated fine.
- **Dispatch to the wrong engine**: `"bullet"` has `variant = Base`, so `runSearch` takes the `case _` (bitboard) branch — correct.

## Open questions for the maintainer

1. Is the failure **consistent** on every run, or **intermittent**? Does it still fail when run in isolation (`sbt "testOnly maichess.engine.EngineServiceSpec"`, or just this one test)?
2. Does the test run on a slow / shared CI box? Roughly how long does `Magics.init()` take on a cold JVM on the failing machine? (A quick `System.nanoTime()` around the `locally { init() }` would settle the timing theory.)
3. Are we permitted to change the test to a deterministic form (`bestMoveAtDepth`), or must the fix be entirely on the engine side?

## Status / progress log

- 2026-05-12: Traced the FEN, move generation, mate detection, and negamax mate-scoring for `mateIn1Fen` — engine logic appears correct for this position. Identified the timed-search/cold-`Magics` interaction as the most likely root cause of the `e5e4` fallback. Wrote up theory + candidate fixes. No code changed yet; awaiting maintainer input on the open questions and on whether the test may be made deterministic.
