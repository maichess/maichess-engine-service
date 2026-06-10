package maichess.engine.kafka

import maichess.engine.service.EngineServiceLive
import maichess.engine.service.clients.TablebaseClient
import maichess.events.v1.match_events.{
  BotMoveCalculated,
  BotMoveRequested,
  MatchEvent,
  MoveApplied,
}
import zio.ZIO
import zio.test.*

// Drives the pure stream-processor transform: a consumed match.events MatchEvent
// carrying BotMoveRequested is mapped to a BotMoveCalculated with a legal move and
// the same request_id, threading a real EngineServiceLive. Asserts the envelope
// plumbing (causation, sequence, producer, ids), the request_id dedupe guard, and
// the bounded seen-set eviction.
object BotMoveProcessorSpec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val UciMove   = "^[a-h][1-8][a-h][1-8][qrbn]?$".r

  private val NewEventId  = "generated-event-id"
  private val NewOccurred = 1_700_000_999_000L

  private val engine = new EngineServiceLive(TablebaseClient.noop)

  private def processor = BotMoveProcessor.make(engine)

  private def requested(
      botId: String,
      fen: String,
      requestId: String,
      timeLimitMs: Option[Int] = None,
  ): MatchEvent =
    MatchEvent(
      eventId = "source-event",
      eventType = "match.BotMoveRequested",
      aggregateId = "match-1",
      sequence = 7L,
      occurredAt = 222L,
      correlationId = "corr-1",
      causationId = "cause-0",
      producer = "match-manager-service",
      payload = MatchEvent.Payload.BotMoveRequested(
        BotMoveRequested(fen = fen, botId = botId, timeLimitMs = timeLimitMs, requestId = requestId),
      ),
    )

  def spec = suite("BotMoveProcessorSpec")(
    test("a BotMoveRequested yields a BotMoveCalculated with a legal move and the same request_id") {
      for
        p     <- processor
        event <- p.handle(requested("basic_bullet", startFen, "r1"), NewEventId, NewOccurred)
      yield event match
        case Some(out) =>
          out.payload match
            case MatchEvent.Payload.BotMoveCalculated(calculated) =>
              assertTrue(
                out.eventType == "match.BotMoveCalculated",
                out.aggregateId == "match-1",
                out.causationId == "source-event",
                out.correlationId == "corr-1",
                out.sequence == 8L,
                out.producer == "engine-service",
                out.eventId == NewEventId,
                out.occurredAt == NewOccurred,
                calculated.requestId == "r1",
                UciMove.matches(calculated.moveUci),
              )
            case other => assertTrue(false) ?? s"expected BotMoveCalculated, got $other"
        case None => assertTrue(false) ?? "expected an emitted event"
    },
    test("a redelivered request_id does not produce a second BotMoveCalculated (dedupe)") {
      for
        p      <- processor
        first  <- p.handle(requested("basic_bullet", startFen, "dup"), NewEventId, NewOccurred)
        second <- p.handle(requested("basic_bullet", startFen, "dup"), NewEventId, NewOccurred)
      yield assertTrue(first.isDefined, second.isEmpty)
    },
    test("distinct request_ids each produce a BotMoveCalculated") {
      for
        p   <- processor
        one <- p.handle(requested("basic_bullet", startFen, "a"), NewEventId, NewOccurred)
        two <- p.handle(requested("basic_bullet", startFen, "b"), NewEventId, NewOccurred)
      yield assertTrue(one.isDefined, two.isDefined)
    },
    test("a time limit on the request is honoured (still yields a legal move)") {
      for
        p     <- processor
        event <- p.handle(requested("bullet", startFen, "timed", Some(50)), NewEventId, NewOccurred)
      yield event match
        case Some(out) =>
          out.payload match
            case MatchEvent.Payload.BotMoveCalculated(calculated) =>
              assertTrue(UciMove.matches(calculated.moveUci))
            case other => assertTrue(false) ?? s"expected BotMoveCalculated, got $other"
        case None => assertTrue(false) ?? "expected an emitted event"
    },
    test("an unknown bot is dropped (no event) rather than failing the stream") {
      for
        p     <- processor
        event <- p.handle(requested("bogus", startFen, "r2"), NewEventId, NewOccurred)
      yield assertTrue(event.isEmpty)
    },
    test("an unparseable FEN is dropped (no event) rather than failing the stream") {
      for
        p     <- processor
        event <- p.handle(requested("basic_bullet", "not a fen", "r3"), NewEventId, NewOccurred)
      yield assertTrue(event.isEmpty)
    },
    test("the engine's own BotMoveCalculated output on the topic is ignored") {
      val ownOutput = requested("basic_bullet", startFen, "r4").copy(
        payload = MatchEvent.Payload.BotMoveCalculated(
          BotMoveCalculated(moveUci = "e2e4", evaluationCp = 10, requestId = "r4"),
        ),
      )
      for
        p     <- processor
        event <- p.handle(ownOutput, NewEventId, NewOccurred)
      yield assertTrue(event.isEmpty)
    },
    test("an unrelated match payload (MoveApplied) is ignored") {
      val applied = requested("basic_bullet", startFen, "r5").copy(
        payload = MatchEvent.Payload.MoveApplied(MoveApplied(moveUci = "e2e4")),
      )
      for
        p     <- processor
        event <- p.handle(applied, NewEventId, NewOccurred)
      yield assertTrue(event.isEmpty)
    },
    test("an event with no payload is ignored") {
      val empty = requested("basic_bullet", startFen, "r6").copy(payload = MatchEvent.Payload.Empty)
      for
        p     <- processor
        event <- p.handle(empty, NewEventId, NewOccurred)
      yield assertTrue(event.isEmpty)
    },
    test("the bounded seen-set evicts the oldest request_id beyond capacity") {
      val s0          = BotMoveProcessor.SeenRequests.empty(2)
      val (n1, s1)    = s0.claim("a")
      val (n2, s2)    = s1.claim("b")
      val (n3, s3)    = s2.claim("c") // at capacity 2 → evicts "a"
      val (again, _)  = s3.claim("a") // "a" was evicted → counts as new again
      val (repeat, _) = s3.claim("b") // "b" still present → not new
      assertTrue(n1, n2, n3, again, !repeat)
    },
  )
