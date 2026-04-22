package maichess.engine.service

import zio.{IO, UIO, ULayer, ZIO, ZLayer}
import zio.stream.ZStream
import maichess.engine.chess.{Move, Position, Search}
import maichess.engine.domain.{AnalysisUpdate, BotConfig, BotRegistry, PrincipalVariation}

final class EngineServiceLive extends EngineService:

  private val MaxAnalysisDepth = 20
  private val MaxPvLength      = 15

  def bestMove(fen: String, botId: String, remainingTimeMs: Option[Long]): IO[String, (String, Int)] =
    for
      config        <- ZIO.fromOption(BotRegistry.find(botId)).orElseFail(s"Unknown bot: $botId")
      pos           <- ZIO.fromEither(Position.fromFen(fen))
      moveTimeMs     = config.computeMoveTime(remainingTimeMs)
      result        <- ZIO.attempt(new Search().bestMove(pos, moveTimeMs))
                          .mapError(e => s"Search failed: ${e.getMessage}")
      (move, score)  = result
      _             <- ZIO.fail(s"No legal moves in position: $fen").when(move == Move.None)
    yield (Move.toUci(move), score)

  def listBots: UIO[List[BotConfig]] =
    ZIO.succeed(BotRegistry.all)

  def analyzePosition(fen: String, botId: String, lineCount: Int): ZStream[Any, String, AnalysisUpdate] =
    val init =
      for
        _ <- ZIO.fromOption(BotRegistry.find(botId)).orElseFail(s"Unknown bot: $botId")
        pos <- ZIO.fromEither(Position.fromFen(fen))
      yield pos

    ZStream.fromZIO(init).flatMap { pos =>
      ZStream.unfoldZIO((1, Option.empty[AnalysisUpdate])) { case (depth, prev) =>
        if depth > MaxAnalysisDepth then ZIO.succeed(None)
        else
          ZIO.attemptBlocking(searchMultiPv(pos, depth, lineCount))
            .mapError(e => s"Analysis failed at depth $depth: ${e.getMessage}")
            .map { lines =>
              val update = AnalysisUpdate(depth, lines)
              if stagnated(update, prev) then None
              else Some((update, (depth + 1, Some(update))))
            }
      }
    }

  private def searchMultiPv(pos: Position, depth: Int, lineCount: Int): List[PrincipalVariation] =
    @annotation.tailrec
    def go(rank: Int, excl: List[Int], acc: List[PrincipalVariation]): List[PrincipalVariation] =
      if rank > lineCount then acc.reverse
      else
        val search        = new Search()
        val (move, score) = search.bestMoveAtDepth(pos, depth, excl.toArray)
        if move == Move.None then acc.reverse
        else
          val pv = search.extractPv(pos, MaxPvLength)
          go(rank + 1, move :: excl, PrincipalVariation(rank, score, pv.map(Move.toUci)) :: acc)
    go(1, Nil, Nil)

  private def stagnated(current: AnalysisUpdate, prev: Option[AnalysisUpdate]): Boolean =
    prev.exists { p =>
      p.lines.headOption.map(_.evaluationCp) == current.lines.headOption.map(_.evaluationCp) &&
      p.lines.headOption.flatMap(_.moves.headOption) == current.lines.headOption.flatMap(_.moves.headOption)
    }

object EngineServiceLive:
  val layer: ULayer[EngineService] = ZLayer.succeed(new EngineServiceLive)
