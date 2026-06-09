package maichess.engine.kafka

import maichess.events.v1.analysis_commands.*
import maichess.events.v1.analysis_events.*
import maichess.events.v1.match_events.*
import org.apache.kafka.common.header.internals.RecordHeaders
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import zio.test.*

// Round-trips the ScalaPB-generated maichess.events.v1 types through the
// zio-kafka Protobuf Serde (serialize -> deserialize) for every payload variant
// on the topics the engine handles: match.events (bot moves), analysis.commands,
// analysis.events.
object ProtobufEventSerdesSpec extends ZIOSpecDefault:

  private val topic = "test"
  private def headers = new RecordHeaders()

  private def roundTrips[A <: GeneratedMessage](
      companion: GeneratedMessageCompanion[A],
      msg: A,
  ) =
    val serde = ProtobufEventSerdes.serde(companion)
    for
      bytes <- serde.serialize(topic, headers, msg)
      back  <- serde.deserialize(topic, headers, bytes)
    yield assertTrue(back == msg)

  private def env(payload: MatchEvent.Payload): MatchEvent =
    MatchEvent(
      eventId = "ev1",
      eventType = "match.event",
      aggregateId = "m1",
      sequence = 1L,
      occurredAt = 1_700_000_000_000L,
      producer = "engine-service",
      payload = payload,
    )

  def spec = suite("ProtobufEventSerdes")(
    test("MatchEvent BotMoveRequested with a time limit round-trips") {
      roundTrips(
        MatchEvent,
        env(MatchEvent.Payload.BotMoveRequested(
          BotMoveRequested(fen = "fen", botId = "bot-1", timeLimitMs = Some(1000), requestId = "r1"),
        )),
      )
    },
    test("MatchEvent BotMoveRequested without a time limit round-trips (optional absent)") {
      val msg = BotMoveRequested(fen = "fen", botId = "bot-1", timeLimitMs = None, requestId = "r2")
      for result <- roundTrips(MatchEvent, env(MatchEvent.Payload.BotMoveRequested(msg)))
      yield result && assertTrue(BotMoveRequested.parseFrom(msg.toByteArray).timeLimitMs.isEmpty)
    },
    test("MatchEvent BotMoveCalculated round-trips") {
      roundTrips(
        MatchEvent,
        env(MatchEvent.Payload.BotMoveCalculated(
          BotMoveCalculated(moveUci = "e7e5", evaluationCp = -15, requestId = "r1"),
        )),
      )
    },
    test("AnalysisCommand StartAnalysis round-trips") {
      roundTrips(
        AnalysisCommand,
        AnalysisCommand(
          eventId = "c1",
          aggregateId = "s1",
          producer = "analysis-service",
          payload = AnalysisCommand.Payload.StartAnalysis(
            StartAnalysisCommand(sessionId = "s1", fen = "fen", botId = "bot-1", lineCount = 3),
          ),
        ),
      )
    },
    test("AnalysisCommand StopAnalysis round-trips") {
      roundTrips(
        AnalysisCommand,
        AnalysisCommand(
          aggregateId = "s1",
          payload = AnalysisCommand.Payload.StopAnalysis(StopAnalysisCommand(sessionId = "s1")),
        ),
      )
    },
    test("AnalysisEvent AnalysisDepthCompleted with principal variations round-trips") {
      roundTrips(
        AnalysisEvent,
        AnalysisEvent(
          eventId = "e1",
          aggregateId = "s1",
          producer = "engine-service",
          payload = AnalysisEvent.Payload.AnalysisDepthCompleted(
            AnalysisDepthCompleted(
              sessionId = "s1",
              fen = "fen",
              botId = "bot-1",
              depth = 12,
              lines = Seq(
                PrincipalVariation(rank = 1, evaluationCp = 30, moves = Seq("e2e4", "e7e5")),
                PrincipalVariation(rank = 2, evaluationCp = 10, moves = Seq("d2d4")),
              ),
            ),
          ),
        ),
      )
    },
    test("AnalysisEvent AnalysisCompleted round-trips") {
      roundTrips(
        AnalysisEvent,
        AnalysisEvent(
          aggregateId = "s1",
          payload = AnalysisEvent.Payload.AnalysisCompleted(
            AnalysisCompleted(sessionId = "s1", finalDepth = 20),
          ),
        ),
      )
    },
    test("AnalysisEvent AnalysisFailed round-trips") {
      roundTrips(
        AnalysisEvent,
        AnalysisEvent(
          aggregateId = "s1",
          payload = AnalysisEvent.Payload.AnalysisFailed(
            AnalysisFailed(sessionId = "s1", message = "engine crashed"),
          ),
        ),
      )
    },
  )
