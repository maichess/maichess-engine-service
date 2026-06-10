package maichess.engine.kafka

import maichess.engine.service.EngineService
import maichess.events.v1.match_events.{BotMoveCalculated, BotMoveRequested, MatchEvent}
import zio.{Ref, UIO, ZIO}

// Pure stream-processor decision logic for engine bot moves: maps a consumed
// match.events.v1 MatchEvent to the BotMoveCalculated the engine should emit.
// Only BotMoveRequested is acted on; every other payload (including the engine's
// own BotMoveCalculated output, which rides the same topic) yields None so the
// consumer advances past it without re-emitting.
//
// Dedupe is on request_id (see BoundedSet): GetBestMove is nondeterministic
// (time-limited search), so reprocessing a redelivered request would submit a
// different move. A request id already answered yields None; a fresh one is
// recorded only once its move is produced. A compute failure (unknown bot,
// unparseable FEN, no legal moves) is logged and yields None — there is no
// bot-move rejection event, and Match Manager produces only valid requests.
final class BotMoveProcessor(engine: EngineService, seen: Ref[BoundedSet]):

  def handle(event: MatchEvent, eventId: String, occurredAt: Long): UIO[Option[MatchEvent]] =
    event.payload match
      case MatchEvent.Payload.BotMoveRequested(request) =>
        onRequest(event, request, eventId, occurredAt)
      case _ =>
        ZIO.none

  private def onRequest(
      source: MatchEvent,
      request: BotMoveRequested,
      eventId: String,
      occurredAt: Long,
  ): UIO[Option[MatchEvent]] =
    seen.get.flatMap { answered =>
      if answered.contains(request.requestId) then ZIO.none
      else compute(source, request, eventId, occurredAt)
    }

  private def compute(
      source: MatchEvent,
      request: BotMoveRequested,
      eventId: String,
      occurredAt: Long,
  ): UIO[Option[MatchEvent]] =
    engine
      .bestMove(request.fen, request.botId, request.timeLimitMs.map(_.toLong))
      .foldZIO(
        reason =>
          ZIO.logWarning(s"bot move ${request.requestId} failed: $reason").as(Option.empty[MatchEvent]),
        { case (moveUci, evaluationCp) =>
          seen
            .update(_.add(request.requestId))
            .as(Some(calculated(source, eventId, occurredAt, moveUci, evaluationCp, request.requestId)))
        },
      )

  private def calculated(
      source: MatchEvent,
      eventId: String,
      occurredAt: Long,
      moveUci: String,
      evaluationCp: Int,
      requestId: String,
  ): MatchEvent =
    MatchEvent(
      eventId = eventId,
      eventType = "match.BotMoveCalculated",
      aggregateId = source.aggregateId,
      sequence = source.sequence + 1,
      occurredAt = occurredAt,
      correlationId = source.correlationId,
      causationId = source.eventId,
      producer = BotMoveProcessor.Producer,
      payload = MatchEvent.Payload.BotMoveCalculated(
        BotMoveCalculated(moveUci = moveUci, evaluationCp = evaluationCp, requestId = requestId),
      ),
    )

object BotMoveProcessor:
  val Producer = "engine-service"
