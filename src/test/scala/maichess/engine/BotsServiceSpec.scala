package maichess.engine

import io.grpc.Status
import zio.test.*
import maichess.engine.grpc.BotsServiceImpl
import maichess.engine.service.EngineServiceLive
import maichess.engine.v1.bots.bots.{GetBestMoveRequest, ListBotsRequest}

object BotsServiceSpec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def svc: BotsServiceImpl =
    new BotsServiceImpl(new EngineServiceLive())

  def spec = suite("BotsServiceImpl")(

    suite("listBots")(
      test("returns all bots as proto messages") {
        for resp <- svc.listBots(ListBotsRequest())
        yield
          assertTrue(resp.bots.length == 3) &&
          assertTrue(resp.bots.map(_.id).toList    == List("bullet", "blitz", "classical")) &&
          assertTrue(resp.bots.map(_.name).toList  == List("Bullet", "Blitz", "Classical")) &&
          assertTrue(resp.bots.map(_.elo).toList   == List(1400, 1700, 2000))
      },
    ),

    suite("getBestMove")(
      test("returns a move and centipawn score for a valid request") {
        val req = GetBestMoveRequest(fen = startFen, botId = "bullet")
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
  )
