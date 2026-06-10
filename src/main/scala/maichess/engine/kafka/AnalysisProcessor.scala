package maichess.engine.kafka

import maichess.engine.domain.AnalysisUpdate
import maichess.engine.service.EngineService
import maichess.events.v1.analysis_commands.{AnalysisCommand, StartAnalysisCommand}
import maichess.events.v1.analysis_events.AnalysisEvent
import zio.*

// Drives analysis sessions on Kafka: a consumed AnalysisCommand starts or stops
// an iterative-deepening analysis whose per-depth AnalysisDepthCompleted (and the
// terminal AnalysisCompleted / AnalysisFailed) are pushed to the injected `emit`
// sink (the analysis.events.v1 producer in AnalysisCommandStream; a capture queue
// in tests). All I/O lives in the shell — this is the tested decision/lifecycle
// core, threading the real EngineService.
//
// Cancellation is by sessionId: a StopAnalysis, or a newer StartAnalysis for the
// same session, interrupts the running search. An interrupted run emits nothing
// (cancel is silent, matching the gRPC stream it replaces); only a natural end or
// an engine error produces a terminal event. Each run carries a unique generation
// so its own cleanup never evicts a successor that already replaced it.
final class AnalysisProcessor(
    engine: EngineService,
    state: Ref.Synchronized[Map[String, AnalysisProcessor.Running]],
    sequence: Ref[Map[String, Long]],
    generation: Ref[Long],
    emit: AnalysisEvent => UIO[Unit],
):

  def handle(command: AnalysisCommand): UIO[Unit] =
    command.payload match
      case AnalysisCommand.Payload.StartAnalysis(start) => onStart(command, start)
      case AnalysisCommand.Payload.StopAnalysis(stop)   => onStop(stop.sessionId)
      case _                                            => ZIO.unit

  // Install a fresh run under the session key and interrupt whatever it replaced.
  // forkDaemon + interruptFork are non-blocking, so holding the synchronized lock
  // here cannot deadlock against the old run's cleanup (which also touches state).
  private def onStart(command: AnalysisCommand, start: StartAnalysisCommand): UIO[Unit] =
    state.modifyZIO { running =>
      for
        gen   <- generation.updateAndGet(_ + 1L)
        fiber <- runAnalysis(command, start, gen).forkDaemon
        _     <- ZIO.foreachDiscard(running.get(start.sessionId))(_.fiber.interruptFork)
      yield ((), running.updated(start.sessionId, AnalysisProcessor.Running(gen, fiber)))
    }

  private def onStop(sessionId: String): UIO[Unit] =
    state.modifyZIO { running =>
      running.get(sessionId) match
        case Some(run) => run.fiber.interruptFork.as(((), running - sessionId))
        case None      => ZIO.succeed(((), running))
    }

  // Cancel is silent: an external interruption (StopAnalysis / supersede) unwinds
  // straight to the `ensuring` finalizer without running foldCauseZIO's handler,
  // so only a real engine failure (a String error) or an unexpected defect emits a
  // terminal AnalysisFailed; a natural end emits AnalysisCompleted.
  private def runAnalysis(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      gen: Long,
  ): UIO[Unit] =
    Ref.make(0).flatMap { lastDepth =>
      engine
        .analyzePosition(start.fen, start.botId, start.lineCount)
        .runForeach(update => lastDepth.set(update.depth) *> emitDepth(command, start, update))
        .foldCauseZIO(
          cause => emitFailed(command, start, cause.failureOption.getOrElse("analysis failed")),
          _ => lastDepth.get.flatMap(emitCompleted(command, start, _)),
        )
    }.ensuring(removeIfCurrent(start.sessionId, gen))

  private def removeIfCurrent(sessionId: String, gen: Long): UIO[Unit] =
    state.update(running =>
      if running.get(sessionId).exists(_.generation == gen) then running - sessionId
      else running,
    )

  private def emitDepth(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      update: AnalysisUpdate,
  ): UIO[Unit] =
    emitWith(start.sessionId)(AnalysisEnvelopes.depthCompleted(command, start, update, _, _, _))

  private def emitCompleted(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      finalDepth: Int,
  ): UIO[Unit] =
    emitWith(start.sessionId)(AnalysisEnvelopes.completed(command, start, finalDepth, _, _, _))

  private def emitFailed(
      command: AnalysisCommand,
      start: StartAnalysisCommand,
      message: String,
  ): UIO[Unit] =
    emitWith(start.sessionId)(AnalysisEnvelopes.failed(command, start, message, _, _, _))

  private def emitWith(sessionId: String)(make: (String, Long, Long) => AnalysisEvent): UIO[Unit] =
    (for
      seq        <- nextSequence(sessionId)
      eventId    <- Random.nextUUID.map(_.toString)
      occurredAt <- Clock.instant.map(_.toEpochMilli)
    yield make(eventId, seq, occurredAt)).flatMap(emit)

  private def nextSequence(sessionId: String): UIO[Long] =
    sequence.modify { seqs =>
      val next = seqs.getOrElse(sessionId, 0L)
      (next, seqs.updated(sessionId, next + 1L))
    }

object AnalysisProcessor:
  final case class Running(generation: Long, fiber: Fiber.Runtime[Nothing, Unit])
