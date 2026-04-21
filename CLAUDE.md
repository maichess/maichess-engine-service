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
│   └── Search.scala                 ← Iterative-deepening negamax alpha-beta
├── domain/
│   ├── BotConfig.scala              ← Bot descriptor (id, name, elo, time limit)
│   └── BotRegistry.scala            ← Authoritative list of available bots
├── service/
│   ├── EngineService.scala          ← ZIO service trait
│   └── EngineServiceLive.scala      ← Implementation + ZLayer
└── grpc/
    └── BotsServiceImpl.scala        ← gRPC handler + companion ZLayer
```

## Chess engine architecture

The `chess/` package is a self-contained bitboard engine ported from `maichess-mono/modules/bots/engine/`. It operates exclusively on FEN strings — `Position.fromFen` is the only entry point, and `Search.bestMove` returns a packed UCI move integer decoded by `Move.toUci`.

**`Search` is a `final class`, not an `object`** — each `GetBestMove` RPC call instantiates a fresh `Search` to avoid shared transposition-table corruption between concurrent requests.

Performance-critical code (`BB`, inline methods, `Position` hot path) uses `@SuppressWarnings` annotations for WartRemover's `Wart.Var` and `Wart.Return` where mutable state or early returns are required for correctness and performance.

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

Three bots are registered in `BotRegistry`:

| id | name | elo | time limit |
|---|---|---|---|
| `bullet` | Bullet | 1400 | 100 ms |
| `blitz` | Blitz | 1700 | 1 000 ms |
| `classical` | Classical | 2000 | 5 000 ms |

Bot IDs are the canonical identifiers used by Match Manager in `GetBestMoveRequest.bot_id`.
