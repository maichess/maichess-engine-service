package maichess.engine.kafka

import maichess.engine.service.{EngineService, EngineServiceLive}
import maichess.engine.service.clients.TablebaseClient
import maichess.events.v1.match_events.{BotMoveCalculated, BotMoveRequested, MatchEvent}
import zio.{Ref, UIO}
import zio.test.*

// Drives the pure engine stream-processor transform: a consumed match.events
// MatchEvent carrying BotMoveRequested is mapped to BotMoveCalculated, threading
// the real EngineService. Asserts the envelope plumbing (causation, sequence,
// producer, ids), the request_id dedupe (a redelivery emits nothing), and that
// non-requests / compute failures are dropped.
object BotMoveProcessorSpec extends ZIOSpecDefault:

  private val startFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  // After 1. f3 e5 2. g4 — Black plays Qd8h4# next (fool's mate); "bullet" finds it.
  private val mateIn1Fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"

  private val NewEventId  = "generated-event-id"
  private val NewOccurred = 1_700_000_999_000L

  private val engine: EngineService = new EngineServiceLive(TablebaseClient.noop)

  private def processor: UIO[BotMoveProcessor] =
    Ref.make(BoundedSet.empty(16)).map(BotMoveProcessor(engine, _))

  private def requested(fen: String, botId: String, requestId: String): MatchEvent =
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
        BotMoveRequested(fen = fen, botId = botId, timeLimitMs = None, requestId = requestId),
      ),
    )

  def spec = suite("BotMoveProcessorSpec")(
    test("a BotMoveRequested yields a BotMoveCalculated with the move, request_id, and envelope copied") {
      for
        p   <- processor
        out <- p.handle(requested(mateIn1Fen, "bullet", "r1"), NewEventId, NewOccurred)
      yield out match
        case Some(event) =>
          event.payload match
            case MatchEvent.Payload.BotMoveCalculated(calc) =>
              assertTrue(
                event.eventType == "match.BotMoveCalculated",
                event.aggregateId == "match-1",
                event.causationId == "source-event",
                event.correlationId == "corr-1",
                event.sequence == 8L,
                event.producer == "engine-service",
                event.eventId == NewEventId,
                event.occurredAt == NewOccurred,
                calc.requestId == "r1",
                calc.moveUci == "d8h4",
              )
            case other => assertTrue(false) ?? s"expected BotMoveCalculated, got $other"
        case None => assertTrue(false) ?? "expected an emitted event"
    },
    test("a BotMoveRequested carrying a time limit is honoured and yields a move") {
      val withLimit = MatchEvent(
        eventId = "source-event",
        eventType = "match.BotMoveRequested",
        aggregateId = "match-1",
        sequence = 7L,
        occurredAt = 222L,
        correlationId = "corr-1",
        causationId = "cause-0",
        producer = "match-manager-service",
        payload = MatchEvent.Payload.BotMoveRequested(
          BotMoveRequested(fen = mateIn1Fen, botId = "bullet", timeLimitMs = Some(200), requestId = "rt"),
        ),
      )
      for
        p   <- processor
        out <- p.handle(withLimit, NewEventId, NewOccurred)
      yield out match
        case Some(event) =>
          event.payload match
            case MatchEvent.Payload.BotMoveCalculated(calc) =>
              assertTrue(calc.requestId == "rt", calc.moveUci == "d8h4")
            case other => assertTrue(false) ?? s"expected BotMoveCalculated, got $other"
        case None => assertTrue(false) ?? "expected an emitted event"
    },
    test("a redelivered request_id does not produce a second BotMoveCalculated") {
      for
        p      <- processor
        first  <- p.handle(requested(mateIn1Fen, "bullet", "dup"), NewEventId, NewOccurred)
        second <- p.handle(requested(mateIn1Fen, "bullet", "dup"), NewEventId, NewOccurred)
      yield assertTrue(first.isDefined, second.isEmpty)
    },
    test("a compute failure (unknown bot) is dropped without emitting") {
      for
        p   <- processor
        out <- p.handle(requested(startFen, "bogus", "r2"), NewEventId, NewOccurred)
      yield assertTrue(out.isEmpty)
    },
    test("a non-BotMoveRequested event is ignored (no re-processing of own output)") {
      val ownOutput = requested(startFen, "bullet", "r3").copy(
        payload = MatchEvent.Payload.BotMoveCalculated(
          BotMoveCalculated(moveUci = "e2e4", evaluationCp = 0, requestId = "r3"),
        ),
      )
      for
        p   <- processor
        out <- p.handle(ownOutput, NewEventId, NewOccurred)
      yield assertTrue(out.isEmpty)
    },
  )
