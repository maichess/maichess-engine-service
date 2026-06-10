package maichess.engine.kafka

import maichess.engine.domain.{AnalysisUpdate, BotConfig, PrincipalVariation => DomainPv}
import maichess.engine.service.EngineService
import maichess.events.v1.analysis_commands.{AnalysisCommand, StartAnalysisCommand, StopAnalysisCommand}
import maichess.events.v1.analysis_events.AnalysisEvent
import zio.*
import zio.stream.ZStream
import zio.test.*

// Drives the analysis session lifecycle through AnalysisProcessor with a fake
// EngineService whose analyzePosition stream is chosen per-fen, and a capture
// queue standing in for the analysis.events.v1 producer. Covers the depth
// sequence + terminal events, engine error / defect handling, and the cancel
// paths (StopAnalysis and a superseding StartAnalysis, both keyed by sessionId).
object AnalysisProcessorSpec extends ZIOSpecDefault:

  private final class FakeEngine(streams: Map[String, ZStream[Any, String, AnalysisUpdate]])
      extends EngineService:
    def bestMove(fen: String, botId: String, remainingTimeMs: Option[Long]): IO[String, (String, Int)] =
      ZIO.dieMessage("bestMove not used in analysis tests")
    def listBots: UIO[List[BotConfig]] = ZIO.succeed(List.empty[BotConfig])
    def analyzePosition(fen: String, botId: String, lineCount: Int): ZStream[Any, String, AnalysisUpdate] =
      streams.getOrElse(fen, ZStream.empty)

  private def update(depth: Int, lines: List[DomainPv]): AnalysisUpdate = AnalysisUpdate(depth, lines)
  private def pv(rank: Int, cp: Int, moves: String*): DomainPv         = DomainPv(rank, cp, moves.toList)

  private def startCommand(sessionId: String, fen: String, lineCount: Int): AnalysisCommand =
    AnalysisCommand(
      eventId = s"cmd-$fen",
      eventType = "analysis.StartAnalysis",
      aggregateId = sessionId,
      sequence = 1L,
      occurredAt = 100L,
      correlationId = "corr-1",
      causationId = "cause-0",
      producer = "analysis-service",
      payload = AnalysisCommand.Payload.StartAnalysis(
        StartAnalysisCommand(sessionId = sessionId, fen = fen, botId = "bot-1", lineCount = lineCount),
      ),
    )

  private def stopCommand(sessionId: String): AnalysisCommand =
    AnalysisCommand(
      aggregateId = sessionId,
      payload = AnalysisCommand.Payload.StopAnalysis(StopAnalysisCommand(sessionId = sessionId)),
    )

  private def emptyCommand(sessionId: String): AnalysisCommand =
    AnalysisCommand(aggregateId = sessionId, payload = AnalysisCommand.Payload.Empty)

  private def makeProcessor(
      engine: EngineService,
  ): UIO[(AnalysisProcessor, Queue[AnalysisEvent])] =
    for
      queue      <- Queue.unbounded[AnalysisEvent]
      state      <- Ref.Synchronized.make(Map.empty[String, AnalysisProcessor.Running])
      sequence   <- Ref.make(Map.empty[String, Long])
      generation <- Ref.make(0L)
    yield (AnalysisProcessor(engine, state, sequence, generation, e => queue.offer(e).unit), queue)

  private def takeN(queue: Queue[AnalysisEvent], n: Int): UIO[List[AnalysisEvent]] =
    ZIO.foreach((1 to n).toList)(_ => queue.take)

  private def depthOf(event: AnalysisEvent): Option[Int] =
    event.payload match
      case AnalysisEvent.Payload.AnalysisDepthCompleted(d) => Some(d.depth)
      case _                                               => None

  def spec = suite("AnalysisProcessorSpec")(
    test("StartAnalysis streams increasing-depth events then AnalysisCompleted, with envelope copied") {
      val engine = new FakeEngine(
        Map(
          "finite" -> ZStream.fromIterable(
            List(
              update(1, List(pv(1, 10, "e2e4"))),
              update(2, List(pv(1, 20, "e2e4", "e7e5"), pv(2, 5, "d2d4"))),
              update(3, List(pv(1, 30, "e2e4"))),
            ),
          ),
        ),
      )
      for
        pq           <- makeProcessor(engine)
        (proc, queue) = pq
        _            <- proc.handle(startCommand("s1", "finite", lineCount = 2))
        events       <- takeN(queue, 4)
      yield
        val depths    = events.map(depthOf)
        val sequences = events.map(_.sequence)
        val last      = events(3)
        val first     = events(0)
        assertTrue(
          depths == List(Some(1), Some(2), Some(3), None),
          sequences == List(0L, 1L, 2L, 3L),
          first.aggregateId == "s1",
          first.causationId == "cmd-finite",
          first.correlationId == "corr-1",
          first.producer == "engine-service",
          first.eventType == "analysis.AnalysisDepthCompleted",
          first.eventId.nonEmpty,
        ) && (last.payload match
          case AnalysisEvent.Payload.AnalysisCompleted(c) =>
            assertTrue(c.sessionId == "s1", c.finalDepth == 3, last.eventType == "analysis.AnalysisCompleted")
          case other => assertTrue(false) ?? s"expected AnalysisCompleted, got $other"
        ) && (events(1).payload match
          case AnalysisEvent.Payload.AnalysisDepthCompleted(d) =>
            assertTrue(
              d.fen == "finite",
              d.botId == "bot-1",
              d.lines.map(_.rank) == Seq(1, 2),
              d.lines.map(_.evaluationCp) == Seq(20, 5),
              d.lines.head.moves == Seq("e2e4", "e7e5"),
            )
          case other => assertTrue(false) ?? s"expected depth update, got $other"
        )
    },
    test("StopAnalysis cancels the running search and emits nothing further (silent cancel)") {
      val engine = new FakeEngine(
        Map("parks" -> (ZStream.fromIterable(List(update(1, List(pv(1, 10, "e2e4"))))) ++ ZStream.never)),
      )
      for
        pq           <- makeProcessor(engine)
        (proc, queue) = pq
        _            <- proc.handle(startCommand("s2", "parks", lineCount = 1))
        firstBatch   <- takeN(queue, 1)
        _            <- proc.handle(stopCommand("s2"))
        rest         <- queue.takeAll
      yield assertTrue(
        firstBatch.map(depthOf) == List(Some(1)),
        rest.isEmpty,
      )
    },
    test("a superseding StartAnalysis cancels the first run and streams the new position") {
      val engine = new FakeEngine(
        Map(
          "A" -> (ZStream.fromIterable(List(update(1, List(pv(1, 10, "e2e4"))))) ++ ZStream.never),
          "B" -> ZStream.fromIterable(
            List(update(2, List(pv(1, 22, "d2d4"))), update(3, List(pv(1, 33, "d2d4")))),
          ),
        ),
      )
      for
        pq           <- makeProcessor(engine)
        (proc, queue) = pq
        _            <- proc.handle(startCommand("s3", "A", lineCount = 1))
        fromA        <- takeN(queue, 1)
        _            <- proc.handle(startCommand("s3", "B", lineCount = 1))
        fromB        <- takeN(queue, 3)
      yield
        val bDepths    = fromB.map(depthOf)
        val bSequences = fromB.map(_.sequence)
        val bFens = fromB.collect {
          case e if depthOf(e).isDefined =>
            e.payload match
              case AnalysisEvent.Payload.AnalysisDepthCompleted(d) => d.fen
              case _                                               => ""
        }
        assertTrue(
          fromA.map(depthOf) == List(Some(1)),
          bDepths == List(Some(2), Some(3), None),
          bSequences == List(1L, 2L, 3L),
          bFens == List("B", "B"),
        ) && (fromB(2).payload match
          case AnalysisEvent.Payload.AnalysisCompleted(c) => assertTrue(c.finalDepth == 3)
          case other                                      => assertTrue(false) ?? s"expected completed, got $other"
        )
    },
    test("an engine stream error emits AnalysisFailed with the engine's message") {
      val engine = new FakeEngine(
        Map(
          "boom" -> (ZStream.fromIterable(List(update(1, List(pv(1, 10, "e2e4"))))) ++
            ZStream.fail("engine exploded")),
        ),
      )
      for
        pq           <- makeProcessor(engine)
        (proc, queue) = pq
        _            <- proc.handle(startCommand("s4", "boom", lineCount = 1))
        events       <- takeN(queue, 2)
      yield assertTrue(events(0).sequence == 0L, events(1).sequence == 1L) && (events(1).payload match
        case AnalysisEvent.Payload.AnalysisFailed(f) =>
          assertTrue(
            f.sessionId == "s4",
            f.message == "engine exploded",
            events(1).eventType == "analysis.AnalysisFailed",
          )
        case other => assertTrue(false) ?? s"expected AnalysisFailed, got $other"
      )
    },
    test("an unexpected defect in the engine stream emits a generic AnalysisFailed") {
      val engine = new FakeEngine(
        Map(
          "die" -> (ZStream.fromIterable(List(update(1, List(pv(1, 10, "e2e4"))))) ++
            ZStream.fromZIO(ZIO.die(new RuntimeException("kaboom")))),
        ),
      )
      for
        pq           <- makeProcessor(engine)
        (proc, queue) = pq
        _            <- proc.handle(startCommand("s5", "die", lineCount = 1))
        events       <- takeN(queue, 2)
      yield events(1).payload match
        case AnalysisEvent.Payload.AnalysisFailed(f) => assertTrue(f.message == "analysis failed")
        case other                                   => assertTrue(false) ?? s"expected AnalysisFailed, got $other"
    },
    test("StopAnalysis for an unknown session is a no-op") {
      for
        pq           <- makeProcessor(new FakeEngine(Map.empty))
        (proc, queue) = pq
        _            <- proc.handle(stopCommand("ghost"))
        rest         <- queue.takeAll
      yield assertTrue(rest.isEmpty)
    },
    test("a command with no payload is ignored") {
      for
        pq           <- makeProcessor(new FakeEngine(Map.empty))
        (proc, queue) = pq
        _            <- proc.handle(emptyCommand("s6"))
        rest         <- queue.takeAll
      yield assertTrue(rest.isEmpty)
    },
  )
