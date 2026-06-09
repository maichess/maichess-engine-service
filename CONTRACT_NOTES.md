# Contract Notes

## Event-driven migration (Kafka) — planned

Per [event-driven-architecture.md](../../maichess-knowledge-base/event-driven-architecture.md),
this service becomes a **stream processor** for both move calculation and analysis. Event
schemas are Avro in `maichess-api-contracts/events/v1/`.

**Becomes:**
- Consumes `match.events.v1` `BotMoveRequested{fen, bot_id, time_limit_ms, request_id}` →
  produces `match.events.v1` `BotMoveCalculated{move_uci, evaluation_cp, request_id}`.
  `GetBestMove` is nondeterministic (time-limited), so it **dedupes on `request_id`** to stay
  idempotent under at-least-once redelivery.
- Consumes `analysis.commands.v1` `StartAnalysis`/`StopAnalysis` → streams
  `analysis.events.v1` `AnalysisDepthCompleted` per depth, then `AnalysisCompleted`.
  Cancellation arrives as `StopAnalysis` keyed by `session_id` (replaces gRPC stream cancel;
  loses native backpressure — accepted trade).

**Keeps (synchronous gRPC):** `ListBots` (a read).

**Eventually retired:** the `Bots.GetBestMove` and `Bots.AnalyzePosition` gRPC RPCs, once
match-loop and analysis are fully on Kafka.

Not yet implemented in code — Phase 0 lands the ADR, Avro schemas, and Kafka infra only.

## Protobuf event serde — implemented (Kafka task `01`)

The event schemas are now **Protobuf**, not Avro: `maichess-api-contracts/protos/events/v1/`
(`match_events.proto`, `analysis_commands.proto`, `analysis_events.proto`, all package
`maichess.events.v1`). They mirror the `events/v1/*.avsc` field-for-field; the `.avsc` files stay
in place until each topic cuts over (task `02`).

Contracts **v0.6.0** is published; `io.github.maichess:platform-protos` is pinned at `0.6.0` in
`build.sbt`. Done:

1. `src/main/scala/maichess/engine/kafka/ProtobufEventSerdes.scala` — a generic zio-kafka `Serde`
   over any ScalaPB companion (raw Protobuf bytes; the end-state encoding once the registry is
   removed in task `09`). Serde plumbing only; **no producer/consumer is wired in task `01`** — the
   stream processors land in tasks `04` (bot moves) / `07` (analysis).
2. `src/test/scala/maichess/engine/kafka/ProtobufEventSerdesSpec.scala` — round-trips every payload
   variant the engine handles across `MatchEvent` (bot moves), `AnalysisCommand`, `AnalysisEvent`.

**Local verify pending (auth handoff):** a fresh agent shell has no `GITHUB_TOKEN`, so `sbt` cannot
resolve `platform-protos@0.6.0` from GitHub Packages (401). Run `sbt test` where the token is
available to confirm.
