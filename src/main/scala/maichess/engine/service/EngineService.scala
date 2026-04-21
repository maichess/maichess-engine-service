package maichess.engine.service

import zio.{IO, UIO}
import maichess.engine.domain.BotConfig

trait EngineService:
  def bestMove(fen: String, botId: String): IO[String, (String, Int)]
  def listBots: UIO[List[BotConfig]]
