# CLAUDE.md — maichess-engine-service

## Service responsibility

Accepts a full game state (FEN) and a bot identifier, runs iterative-deepening alpha-beta search, and returns the best move in UCI notation plus a centipawn evaluation. Also enumerates available bots with metadata. Called exclusively by **Match Manager** over gRPC.

See `maichess-knowledge-base/maichess-structure.md` for architecture context.

## API contract

The sole source of truth is:

```
maichess-api-contracts/protos/engine-service/v1/bots.proto
```

Service: `maichess.engine.v1.Bots`
RPCs: `GetBestMove`, `ListBots`

Read the proto before touching any service logic. Do not infer the contract from implementation code.

If the contract cannot be implemented as specified, document the blocker in `CONTRACT_NOTES.md` at the service root. Do not implement an adjusted version until explicitly told to proceed.

## Stack

- **Scala 3** with **ZIO 2** — all effects must be modelled as `ZIO[R, E, A]`; no `Future`, no `Try` as effect boundaries
- **ZIO gRPC** (`scalapb-zio-grpc`) for the gRPC server layer; the actual server uses a Netty `ServerBuilder` with a ZIO→Future bridge (same pattern as `maichess-move-validator-service`)
- **ScalaPB** for protobuf code generation (generated stubs consumed, not owned here)
- **zio-test** for tests
- **sbt** as build tool

## Commands

```bash
sbt compile          # Compile
sbt test             # Run all tests
sbt "testOnly *Foo"  # Run a single suite
sbt scalafix         # Linter / rewriter
sbt run              # Start the gRPC server (entry point via ZIOAppDefault)
```

## Module / layer structure

```
Chess engine (pure, mutable-but-encapsulated)
  └─ Domain (BotConfig, BotRegistry — pure)
       └─ Engine service (ZIO layer: FEN parsing, bot dispatch, search)
            └─ gRPC handler (proto ↔ domain translation only)
                 └─ Server (ZIOAppDefault, wires layers, starts Netty)
```

No business logic in the gRPC handler — it translates proto types to/from domain types only.

## Package layout

```
maichess.engine
├── Main.scala                       ← ZIOAppDefault entry point + Netty bridge
├── chess/                           ← Self-contained bitboard chess engine
│   ├── Types.scala                  ← Opaque types: Bitboard, Square, Piece, Move encoding
│   ├── BB.scala                     ← Bitboard utilities (inline, zero-dispatch)
│   ├── Attacks.scala                ← Static attack tables (pawn, knight, king)
│   ├── Magics.scala                 ← Magic bitboard tables for slider attacks
│   ├── Zobrist.scala                ← Incremental Zobrist hashing
│   ├── Position.scala               ← Mutable board state + FEN parser
│   ├── MoveGen.scala                ← Pseudo-legal move generator
│   ├── LegalCheck.scala             ← Post-move legality filter
│   ├── Eval.scala                   ← Static evaluation (material + PST + mobility)
│   ├── Search.scala                 ← Tier 1: iterative-deepening negamax alpha-beta
│   ├── SearchV2.scala               ← Tier 2: Search + LMR + null-move pruning + PVS
│   └── basic/                       ← Tier 0: mailbox minimax engine (no bitboards)
├── domain/
│   ├── BotConfig.scala              ← Bot descriptor (id, name, elo, strategy, description, variant)
│   ├── EngineVariant.scala          ← Engine tier enum (Basic, Base, EnhancedSearch, …)
│   ├── TimingStrategy.scala         ← Fixed / Proportional / Aggressive move-time policies
│   └── BotRegistry.scala            ← Authoritative list of available bots
├── service/
│   ├── EngineService.scala          ← ZIO service trait
│   └── EngineServiceLive.scala      ← Implementation + ZLayer
└── grpc/
    └── BotsServiceImpl.scala        ← gRPC handler + companion ZLayer
```

## Chess engine architecture

The `chess/` package is a self-contained bitboard engine ported from `maichess-mono/modules/bots/engine/`. It operates exclusively on FEN strings — `Position.fromFen` is the only entry point, and `Search.bestMove` / `SearchV2.bestMove` return a packed UCI move integer decoded by `Move.toUci`.

**Engine tiers.** Each `BotConfig` carries an `EngineVariant`; `EngineServiceLive.runSearch` dispatches on it:
- `Basic` (tier 0) → `chess/basic/BasicSearch` — plain mailbox minimax, material-only eval, no TT.
- `Base` (tier 1) → `chess/Search` — iterative-deepening negamax alpha-beta with a transposition table, quiescence search, and MVV-LVA move ordering.
- `EnhancedSearch` (tier 2) → `chess/SearchV2` — `Search` plus Late Move Reductions, Null Move Pruning, and Principal Variation Search (all gated to the main search, never quiescence). NMP threads a `wasNullMove` flag as a parameter (never a field) and skips when the side to move has only king + pawns (zugzwang guard); it relies on `Position.makeNullMove`/`unmakeNullMove`.
- Later tiers (`EnhancedOrdering`, `EnhancedEval`, `Knowledge`) are reserved but not yet implemented.

**`Search`/`SearchV2` are `final class`es, not `object`s** — each `GetBestMove` RPC call instantiates a fresh search to avoid shared transposition-table corruption between concurrent requests.

When adding a new tier: create the new search class (copy the previous tier, do not modify it), add the `EngineVariant` case if missing, add the dispatch branch in `EngineServiceLive.runSearch`, register the bots in `BotRegistry`, and update `BotRegistrySpec`/`EngineServiceSpec`/`BotsServiceSpec` bot counts.

Performance-critical code (`BB`, inline methods, `Position` hot path, `Search`/`SearchV2`) uses `@SuppressWarnings` annotations for WartRemover's `Wart.Var`, `Wart.Return`, and (in `SearchV2`) `Wart.DefaultArguments` where mutable state, early returns, or default parameters are required for correctness and performance.

## Code style

- Functional programming at the ZIO layer — immutable data, pure functions, ZIO effects.
- The `chess/` layer deliberately uses mutable, JVM-optimised code for performance. Keep that layer clean and self-contained; do not leak mutability upward.
- Max ~15–20 lines per function. If you need "and" to describe it, split it.
- No comments unless explaining a non-obvious algorithm or performance constraint.
- ZIO-idiomatic: `ZLayer.succeed` / `ZLayer.fromFunction` for wiring; `ZIO.fromEither` to lift `Either` errors.

## Linting & coverage

- **WartRemover** (`Warts.unsafe` as errors) applies to all logic. The `chess/` package uses `@SuppressWarnings` locally where `Var` or `Return` is unavoidable.
- **100% statement coverage** is mandatory for all logic-containing code. Excluded from coverage enforcement: the server entry-point (`Main`) and the entire `maichess.engine.chess.*` package. The chess package exclusion exists because timing-dependent branches in `Search` (the time-check guard `(nodes & 4095) == 0 && timeUp()` and the quiescence depth clamp `ply >= 127`) cannot be triggered deterministically in unit tests without mocking wall-clock time.
- Every change to logic code must be accompanied by tests.

## Tests

- Test files live in `src/test/scala/`.
- Use **zio-test** (`ZIOSpecDefault`) for ZIO-native specs.
- Unit-test chess engine logic (FEN parsing, move generation, search results) without a running server.
- Integration-test the gRPC handler via the ZIO gRPC test harness, not a live socket.
- Do not change tests to make them pass — only change tests when the requirement they cover changes.

## Bot definitions

`BotRegistry.all` is the authoritative list (currently **27 bots** — keep the count assertions in the specs in sync when this changes). Bots come in three "speed" tiers (bullet ≈ 100 ms, blitz ≈ 1 s, classical ≈ 5 s) crossed with three timing strategies (`Fixed`, `Proportional` → `…_proportional`/`…_prop`, `Aggressive` → `…_aggressive`/`…_aggr`), per engine variant:

| variant | id prefix | elo (bullet / blitz / classical) | search class |
|---|---|---|---|
| `Basic` (tier 0) | `basic_*` | 700 / 800 / 900 | `BasicSearch` |
| `Base` (tier 1) | `bullet`, `blitz`, `classical` (+ suffixes) | 1400 / 1700 / 2000 | `Search` |
| `EnhancedSearch` (tier 2) | `search_*` | 1600 / 1900 / 2150 | `SearchV2` |

Each `BotConfig` has `id`, `name`, `elo`, `strategy: TimingStrategy`, `description`, and `variant: EngineVariant`. Bot IDs are the canonical identifiers used by Match Manager in `GetBestMoveRequest.bot_id`; only `Basic` bots are rejected by the `analyzePosition` (multi-PV) stream.
