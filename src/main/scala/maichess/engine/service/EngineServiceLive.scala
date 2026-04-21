package maichess.engine.service

import zio.{IO, UIO, ULayer, ZIO, ZLayer}
import maichess.engine.chess.{Move, Position, Search}
import maichess.engine.domain.{BotConfig, BotRegistry}

final class EngineServiceLive extends EngineService:

  def bestMove(fen: String, botId: String): IO[String, (String, Int)] =
    for
      config <- ZIO.fromOption(BotRegistry.find(botId))
                   .orElseFail(s"Unknown bot: $botId")
      pos    <- ZIO.fromEither(Position.fromFen(fen))
      result <- ZIO.attempt(new Search().bestMove(pos, config.timeLimitMs))
                   .mapError(e => s"Search failed: ${e.getMessage}")
      (move, score) = result
      _      <- ZIO.fail(s"No legal moves in position: $fen")
                   .when(move == Move.None)
    yield (Move.toUci(move), score)

  def listBots: UIO[List[BotConfig]] =
    ZIO.succeed(BotRegistry.all)

object EngineServiceLive:
  val layer: ULayer[EngineService] = ZLayer.succeed(new EngineServiceLive)
