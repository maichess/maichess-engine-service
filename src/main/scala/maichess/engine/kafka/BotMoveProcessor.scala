package maichess.engine.kafka

import maichess.engine.service.EngineService
import maichess.events.v1.match_events.{BotMoveCalculated, BotMoveRequested, MatchEvent}
import zio.{Ref, UIO, ZIO}

// Pure stream-processor decision + dedupe logic for the engine. Maps a consumed
// match.events.v1 MatchEvent to the BotMoveCalculated the engine should emit. Only
// BotMoveRequested is acted on; every other payload (including the engine's own
// BotMoveCalculated output, which rides the same topic) yields None so the consumer
// advances past it without re-emitting.
//
// Bot-move calculation is time-limited and therefore NONDETERMINISTIC: reprocessing a
// redelivered request could pick a different move, so it is not safe to reprocess.
// The request_id seen-set is the engine's idempotency guard (the validator/projector
// use Kafka transactions; the engine's guard is request_id): a redelivered request_id
// yields None. The set is bounded with FIFO eviction so an unbounded match stream
// cannot grow it without limit.
final class BotMoveProcessor(engine: EngineService, seen: Ref[BotMoveProcessor.SeenRequests]):

  def handle(event: MatchEvent, eventId: String, occurredAt: Long): UIO[Option[MatchEvent]] =
    event.payload match
      case MatchEvent.Payload.BotMoveRequested(requested) =>
        seen.modify(_.claim(requested.requestId)).flatMap { isNew =>
          if isNew then calculate(event, requested, eventId, occurredAt)
          else ZIO.none
        }
      case _ =>
        ZIO.none

  private def calculate(
      source: MatchEvent,
      requested: BotMoveRequested,
      eventId: String,
      occurredAt: Long,
  ): UIO[Option[MatchEvent]] =
    engine
      .bestMove(requested.fen, requested.botId, requested.timeLimitMs.map(_.toLong))
      .map { case (moveUci, evaluationCp) =>
        Some(calculated(source, eventId, occurredAt, moveUci, evaluationCp, requested.requestId))
      }
      .catchAll(reason =>
        ZIO
          .logWarning(s"bot-move calculation failed for request ${requested.requestId}: $reason")
          .as(Option.empty[MatchEvent]),
      )

  private def calculated(
      source: MatchEvent,
      eventId: String,
      occurredAt: Long,
      moveUci: String,
      evaluationCp: Int,
      requestId: String,
  ): MatchEvent =
    envelope(
      source,
      eventId,
      occurredAt,
      MatchEvent.Payload.BotMoveCalculated(
        BotMoveCalculated(moveUci = moveUci, evaluationCp = evaluationCp, requestId = requestId),
      ),
    )

  private def envelope(
      source: MatchEvent,
      eventId: String,
      occurredAt: Long,
      payload: MatchEvent.Payload,
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
      payload = payload,
    )

object BotMoveProcessor:
  val Producer        = "engine-service"
  val DefaultCapacity = 8192

  def make(engine: EngineService): UIO[BotMoveProcessor] =
    make(engine, DefaultCapacity)

  def make(engine: EngineService, capacity: Int): UIO[BotMoveProcessor] =
    Ref.make(SeenRequests.empty(capacity)).map(new BotMoveProcessor(engine, _))

  // Bounded FIFO set of seen request_ids. `claim` returns whether the id is new
  // (and the updated set); a repeat id returns (false, this). At capacity the oldest
  // id is evicted to make room — a request that old has long since been answered.
  final case class SeenRequests private (ids: Set[String], order: Vector[String], capacity: Int):
    def claim(id: String): (Boolean, SeenRequests) =
      if ids.contains(id) then (false, this)
      else (true, add(id))

    private def add(id: String): SeenRequests =
      order match
        case oldest +: rest if order.size >= capacity =>
          SeenRequests(ids - oldest + id, rest :+ id, capacity)
        case _ =>
          SeenRequests(ids + id, order :+ id, capacity)

  object SeenRequests:
    def empty(capacity: Int): SeenRequests = SeenRequests(Set.empty, Vector.empty, capacity)
