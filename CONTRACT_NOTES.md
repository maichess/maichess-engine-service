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

## Protobuf event serde — pending v0.6.0 publish (Kafka task `01`)

The event schemas are now **Protobuf**, not Avro: `maichess-api-contracts/protos/events/v1/`
(`match_events.proto`, `analysis_commands.proto`, `analysis_events.proto`, all package
`maichess.events.v1`). They mirror the `events/v1/*.avsc` field-for-field; the `.avsc` files stay
in place until each topic cuts over (task `02`).

**Blocked on the contracts publish** (publish-first — see
[serialization-protobuf-migration.md](../../maichess-knowledge-base/knowledge/architecture/serialization-protobuf-migration.md)):

1. The user tags/pushes contracts **v0.6.0** so the generated `maichess.events.v1` types ship in
   `platform-protos`. A fresh agent shell cannot restore the just-published version.
2. Bump `io.github.maichess:platform-protos` in `build.sbt` from `0.4.0` → `0.6.0`.
3. Add a zio-kafka **Protobuf serde** over the ScalaPB-generated `maichess.events.v1` types
   (`MatchEvent`, `AnalysisCommand`, `AnalysisEvent`) — Confluent Protobuf serde + Schema Registry
   during the transition. Serde plumbing only; **no producer/consumer is switched in task `01`.**

Cannot compile or test until step 1–2 land.
