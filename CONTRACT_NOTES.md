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

Bot-move calculation and analysis both now run as Kafka stream processors (see "Bot-move stream
processor" and "Analysis stream processor" below). The event schemas are now Protobuf, not Avro
(see "Protobuf event serde" below).

## Protobuf event serde — implemented (Kafka task `01`)

The event schemas are now **Protobuf**, not Avro: `maichess-api-contracts/protos/events/v1/`
(`match_events.proto`, `analysis_commands.proto`, `analysis_events.proto`, all package
`maichess.events.v1`). They mirror the `events/v1/*.avsc` field-for-field; the `.avsc` files stay
in place until each topic cuts over (task `02`).

Contracts **v0.6.0** is published; `io.github.maichess:platform-protos` is pinned at `0.6.0` in
`build.sbt`. Done:

1. `src/main/scala/maichess/engine/kafka/ProtobufEventSerdes.scala` — a generic zio-kafka `Serde`
   over any ScalaPB companion (raw Protobuf bytes; the end-state encoding once the registry is
   removed in task `09`). The analysis stream path (task `07`) is still to be wired on top of it.
2. `src/test/scala/maichess/engine/kafka/ProtobufEventSerdesSpec.scala` — round-trips every payload
   variant the engine handles across `MatchEvent` (bot moves), `AnalysisCommand`, `AnalysisEvent`.

## Bot-move stream processor — implemented (Kafka task `04`)

The engine is now a stream processor for bot moves. No contract change beyond `01`
(`match_events.proto` already defines `BotMoveRequested` / `BotMoveCalculated`); `platform-protos`
stays pinned at `0.6.0`. Added:

1. `kafka/BotMoveProcessor.scala` — pure transform: a consumed `match.events.v1` `BotMoveRequested`
   → `BotMoveCalculated` via the existing `EngineService.bestMove` (the `GetBestMove` logic, reused
   unchanged). Copies `aggregate_id`, sets `causation_id` = the request's `event_id`, `sequence` =
   source + 1, `producer` = `engine-service`. Non-requests (including the engine's own output on the
   same topic) and compute failures (unknown bot / bad FEN / no legal moves — no rejection event
   exists) yield nothing.
2. `kafka/BoundedSet.scala` — bounded FIFO dedupe on `request_id`. Bot search is nondeterministic,
   so a redelivered request must not be recomputed. This is the engine's idempotency guard (the
   validator/projector use Kafka transactions instead).
3. `kafka/EngineMoveStream.scala` — the live-Kafka I/O shell. **Non-transactional**: consume →
   produce `BotMoveCalculated` → commit the offset batch (at-least-once; a crash mid-way redelivers
   and the dedupe drops it). Opt-in via `KAFKA_ENABLED` in `Main`, retried forever so a broker
   outage never takes down the `ListBots` / `GetBestMove` gRPC query path. Excluded from coverage
   and Stryker like the other Kafka shells; `BotMoveProcessor` + `BoundedSet` are covered.

**`ListBots` gRPC is unchanged.** `Bots.GetBestMove` / `AnalyzePosition` gRPC are still served and
are retired later in task `09`.

## Analysis stream processor — implemented (Kafka task `07`)

The engine is now a stream processor for analysis sessions too. No contract change beyond `01`
(`analysis_commands.proto` / `analysis_events.proto` already define the messages); `platform-protos`
stays pinned at `0.6.0`. Added:

1. `kafka/AnalysisEnvelopes.scala` — pure builders mapping a depth update / terminal outcome to an
   `analysis.events.v1` `AnalysisEvent` (aggregate = `session_id`, `causation_id` = the
   `StartAnalysis` command's `event_id`, `producer` = `engine-service`).
2. `kafka/AnalysisProcessor.scala` — the tested session/cancel core. A consumed `AnalysisCommand`
   starts (or supersedes) / stops an `EngineService.analyzePosition` run keyed by `session_id`;
   each depth streams `AnalysisDepthCompleted`, a natural end emits `AnalysisCompleted{final_depth}`,
   an engine error/defect emits `AnalysisFailed`. **Cancellation is silent:** a `StopAnalysis` or a
   newer `StartAnalysis` for the same session interrupts the run, which unwinds to the `ensuring`
   finalizer without emitting a terminal event (matching the gRPC stream cancel it replaces; native
   backpressure is the accepted loss). Each run carries a unique generation so its own cleanup never
   evicts a successor. `analyzePosition` (the `AnalyzePosition` logic) is reused unchanged.
3. `kafka/AnalysisCommandStream.scala` — the live-Kafka I/O shell. Consumes `analysis.commands.v1`,
   hands each command to `AnalysisProcessor`, whose background runs produce to `analysis.events.v1`
   via the injected sink; command offsets commit once handed off (redelivered Start/Stop is
   harmless). Runs in parallel with the bot-move stream in `Main`, opt-in via `KAFKA_ENABLED`,
   retried forever. Excluded from coverage and Stryker like the other Kafka shells; `AnalysisProcessor`
   + `AnalysisEnvelopes` are covered at 100%.

Coverage note: added a time-limit case to `BotMoveProcessorSpec` so the `time_limit_ms.map(_.toLong)`
path is exercised (it was the one statement the prior bot-move tests left uncovered).

The build uses `sbt-dotenv`, which loads `GITHUB_TOKEN` from `.env`, so `sbt test` resolves
`platform-protos@0.6.0` from GitHub Packages locally.
