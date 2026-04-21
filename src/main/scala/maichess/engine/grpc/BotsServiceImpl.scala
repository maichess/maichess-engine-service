package maichess.engine.grpc

import io.grpc.Status
import zio.{IO, UIO, URLayer, ZIO, ZLayer}
import maichess.engine.domain.BotConfig
import maichess.engine.service.EngineService
import maichess.engine.v1.bots.bots.{
  Bot => ProtoBot,
  GetBestMoveRequest,
  GetBestMoveResponse,
  ListBotsRequest,
  ListBotsResponse,
}

final class BotsServiceImpl(engine: EngineService):

  def getBestMove(req: GetBestMoveRequest): IO[Status, GetBestMoveResponse] =
    engine.bestMove(req.fen, req.botId)
      .map { case (move, score) => GetBestMoveResponse(move = move, evaluationCp = score) }
      .mapError(reason => Status.INVALID_ARGUMENT.withDescription(reason))

  def listBots(req: ListBotsRequest): UIO[ListBotsResponse] =
    engine.listBots.map(bots => ListBotsResponse(bots = bots.map(toProtoBot)))

  private def toProtoBot(config: BotConfig): ProtoBot =
    ProtoBot(id = config.id, name = config.name, elo = config.elo)

object BotsServiceImpl:
  val layer: URLayer[EngineService, BotsServiceImpl] =
    ZLayer.fromFunction(new BotsServiceImpl(_))
