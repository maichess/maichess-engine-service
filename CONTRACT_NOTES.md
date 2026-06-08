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
