package maichess.engine

import io.grpc.Status
import zio.test.*
import maichess.engine.grpc.BotsServiceImpl
import maichess.engine.service.EngineServiceLive
import maichess.engine.v1.bots.bots.{AnalyzePositionRequest, GetBestMoveRequest, ListBotsRequest}

object BotsServiceSpec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def svc: BotsServiceImpl =
    new BotsServiceImpl(new EngineServiceLive())

  def spec = suite("BotsServiceImpl")(

    suite("listBots")(
      test("returns all nine bots as proto messages") {
        for resp <- svc.listBots(ListBotsRequest())
        yield
          assertTrue(resp.bots.length == 9) &&
          assertTrue(resp.bots.head.id   == "bullet") &&
          assertTrue(resp.bots.head.name == "Bullet") &&
          assertTrue(resp.bots.head.elo  == 1400)
      },
    ),

    suite("getBestMove")(
      test("returns a move and centipawn score without a time limit") {
        val req = GetBestMoveRequest(fen = startFen, botId = "bullet")
        for resp <- svc.getBestMove(req)
        yield assertTrue(resp.move.length >= 4 && resp.move.length <= 5)
      },
      test("returns a move when a time limit is provided") {
        val req = GetBestMoveRequest(fen = startFen, botId = "bullet_proportional", timeLimitMs = Some(30000))
        for resp <- svc.getBestMove(req)
        yield assertTrue(resp.move.length >= 4 && resp.move.length <= 5)
      },
      test("fails with INVALID_ARGUMENT for an unknown bot id") {
        val req = GetBestMoveRequest(fen = startFen, botId = "unknown")
        for status <- svc.getBestMove(req).flip
        yield
          assertTrue(status.getCode == Status.Code.INVALID_ARGUMENT) &&
          assertTrue(status.getDescription.contains("Unknown bot: unknown"))
      },
      test("fails with INVALID_ARGUMENT for an invalid FEN") {
        val req = GetBestMoveRequest(fen = "garbage", botId = "bullet")
        for status <- svc.getBestMove(req).flip
        yield assertTrue(status.getCode == Status.Code.INVALID_ARGUMENT)
      },
    ),

    suite("analyzePosition")(
      test("streams at least one update for a valid request") {
        val req = AnalyzePositionRequest(fen = startFen, botId = "bullet", lineCount = 1)
        for updates <- svc.analyzePosition(req).take(1).runCollect
        yield assertTrue(updates.length == 1)
      },
      test("each update contains lines with moves in UCI notation") {
        val req = AnalyzePositionRequest(fen = startFen, botId = "bullet", lineCount = 1)
        for updates <- svc.analyzePosition(req).take(1).runCollect
        yield
          val line = updates.head.lines.head
          assertTrue(line.moves.nonEmpty) &&
          assertTrue(line.moves.head.length >= 4 && line.moves.head.length <= 5)
      },
      test("fails with INVALID_ARGUMENT for an unknown bot") {
        val req = AnalyzePositionRequest(fen = startFen, botId = "bogus", lineCount = 1)
        for status <- svc.analyzePosition(req).runCollect.flip
        yield assertTrue(status.getCode == Status.Code.INVALID_ARGUMENT)
      },
      test("fails with INVALID_ARGUMENT for an invalid FEN") {
        val req = AnalyzePositionRequest(fen = "not-a-fen", botId = "bullet", lineCount = 1)
        for status <- svc.analyzePosition(req).runCollect.flip
        yield assertTrue(status.getCode == Status.Code.INVALID_ARGUMENT)
      },
    ),
  )
