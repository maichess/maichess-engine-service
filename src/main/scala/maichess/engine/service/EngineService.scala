package maichess.engine.service

import zio.{IO, UIO}
import zio.stream.ZStream
import maichess.engine.domain.{AnalysisUpdate, BotConfig}

trait EngineService:
  def bestMove(fen: String, botId: String, remainingTimeMs: Option[Long]): IO[String, (String, Int)]
  def listBots: UIO[List[BotConfig]]
  def analyzePosition(fen: String, botId: String, lineCount: Int): ZStream[Any, String, AnalysisUpdate]
