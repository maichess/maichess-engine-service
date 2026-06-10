package maichess.engine.kafka

import maichess.engine.domain.{AnalysisUpdate, PrincipalVariation => DomainPv}
import maichess.events.v1.analysis_commands.{AnalysisCommand, StartAnalysisCommand}
import maichess.events.v1.analysis_events.{
  AnalysisCompleted,
  AnalysisDepthCompleted,
  AnalysisEvent,
  AnalysisFailed,
  PrincipalVariation => ProtoPv,
}

// Pure builders for the analysis.events.v1 envelopes the engine emits while
// processing an analysis session. The session/stream lifecycle lives in
// AnalysisProcessor; this object only maps a depth update / terminal outcome to
// a fully-formed AnalysisEvent so the field plumbing (aggregate = sessionId,
// causation = the StartAnalysis command, producer) is in one tested place.
//
// `event_id`, `sequence`, and `occurred_at` are supplied by the processor (they
// come from effects — UUID/clock/per-session counter) so these stay pure.
object AnalysisEnvelopes:

  val Producer = "engine-service"

  def depthCompleted(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      update: AnalysisUpdate,
      eventId: String,
      sequence: Long,
      occurredAt: Long,
  ): AnalysisEvent =
    event(
      command,
      start.sessionId,
      eventId,
      sequence,
      occurredAt,
      "analysis.AnalysisDepthCompleted",
      AnalysisEvent.Payload.AnalysisDepthCompleted(
        AnalysisDepthCompleted(
          sessionId = start.sessionId,
          fen = start.fen,
          botId = start.botId,
          depth = update.depth,
          lines = update.lines.map(toProtoPv),
        ),
      ),
    )

  def completed(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      finalDepth: Int,
      eventId: String,
      sequence: Long,
      occurredAt: Long,
  ): AnalysisEvent =
    event(
      command,
      start.sessionId,
      eventId,
      sequence,
      occurredAt,
      "analysis.AnalysisCompleted",
      AnalysisEvent.Payload.AnalysisCompleted(
        AnalysisCompleted(sessionId = start.sessionId, finalDepth = finalDepth),
      ),
    )

  def failed(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      message: String,
      eventId: String,
      sequence: Long,
      occurredAt: Long,
  ): AnalysisEvent =
    event(
      command,
      start.sessionId,
      eventId,
      sequence,
      occurredAt,
      "analysis.AnalysisFailed",
      AnalysisEvent.Payload.AnalysisFailed(
        AnalysisFailed(sessionId = start.sessionId, message = message),
      ),
    )

  private def event(
      command: AnalysisCommand,
      sessionId: String,
      eventId: String,
      sequence: Long,
      occurredAt: Long,
      eventType: String,
      payload: AnalysisEvent.Payload,
  ): AnalysisEvent =
    AnalysisEvent(
      eventId = eventId,
      eventType = eventType,
      aggregateId = sessionId,
      sequence = sequence,
      occurredAt = occurredAt,
      correlationId = command.correlationId,
      causationId = command.eventId,
      producer = Producer,
      payload = payload,
    )

  private def toProtoPv(pv: DomainPv): ProtoPv =
    ProtoPv(rank = pv.rank, evaluationCp = pv.evaluationCp, moves = pv.moves)
