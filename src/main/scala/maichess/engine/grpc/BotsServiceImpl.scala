package maichess.engine.grpc

import io.grpc.Status
import zio.{IO, UIO, URLayer, ZIO, ZLayer}
import zio.stream.ZStream
import maichess.engine.domain.BotConfig
import maichess.engine.service.EngineService
import maichess.engine.v1.bots.bots.{
  AnalysisUpdate    => ProtoAnalysisUpdate,
  PrincipalVariation => ProtoPv,
  AnalyzePositionRequest,
  Bot               => ProtoBot,
  GetBestMoveRequest,
  GetBestMoveResponse,
  ListBotsRequest,
  ListBotsResponse,
}

final class BotsServiceImpl(engine: EngineService):

  def getBestMove(req: GetBestMoveRequest): IO[Status, GetBestMoveResponse] =
    engine.bestMove(req.fen, req.botId, req.timeLimitMs.map(_.toLong))
      .map { case (move, score) => GetBestMoveResponse(move = move, evaluationCp = score) }
      .mapError(reason => Status.INVALID_ARGUMENT.withDescription(reason))

  def listBots(req: ListBotsRequest): UIO[ListBotsResponse] =
    engine.listBots.map(bots => ListBotsResponse(bots = bots.map(toProtoBot)))

  def analyzePosition(req: AnalyzePositionRequest): ZStream[Any, Status, ProtoAnalysisUpdate] =
    engine.analyzePosition(req.fen, req.botId, req.lineCount)
      .map(update =>
        ProtoAnalysisUpdate(
          depth = update.depth,
          lines = update.lines.map(pv => ProtoPv(rank = pv.rank, evaluationCp = pv.evaluationCp, moves = pv.moves)),
        )
      )
      .mapError(reason => Status.INVALID_ARGUMENT.withDescription(reason))

  private def toProtoBot(config: BotConfig): ProtoBot =
    ProtoBot(id = config.id, name = config.name, elo = config.elo)

object BotsServiceImpl:
  val layer: URLayer[EngineService, BotsServiceImpl] =
    ZLayer.fromFunction(new BotsServiceImpl(_))
