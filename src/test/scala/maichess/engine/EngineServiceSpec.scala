package maichess.engine

import zio.test.*
import zio.test.Assertion.*
import maichess.engine.service.EngineServiceLive

object EngineServiceSpec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  // After 1.f3 e5 2.g4 — Black plays Qd8-h4# (Fool's mate)
  private val mateIn1Fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"

  // Scholar's mate: Black king is checkmated, no legal moves
  private val mateFen = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"

  // Position without a White king — causes the search to throw during legality check
  private val noKingFen = "k7/8/8/8/8/8/8/8 w - - 0 1"

  private val svc = new EngineServiceLive()

  def spec = suite("EngineServiceLive")(

    suite("listBots")(
      test("returns all three bots") {
        for bots <- svc.listBots
        yield assertTrue(bots.length == 3)
      },
      test("bot ids are correct") {
        for bots <- svc.listBots
        yield assertTrue(bots.map(_.id) == List("bullet", "blitz", "classical"))
      },
    ),

    suite("bestMove")(
      test("returns a valid UCI move for the starting position") {
        for result <- svc.bestMove(startFen, "bullet")
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },

      test("finds the mate-in-one") {
        for result <- svc.bestMove(mateIn1Fen, "bullet")
        yield
          val (move, _) = result
          assertTrue(move == "d8h4")
      },

      test("fails with an informative message for unknown bot id") {
        for result <- svc.bestMove(startFen, "bogus").exit
        yield assert(result)(fails(containsString("Unknown bot: bogus")))
      },

      test("fails with an informative message for invalid FEN") {
        for result <- svc.bestMove("not a fen", "bullet").exit
        yield assert(result)(fails(containsString("Invalid FEN")))
      },

      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "bullet").exit
        yield assert(result)(fails(containsString("No legal moves")))
      },

      test("fails when the search throws on a degenerate position") {
        for result <- svc.bestMove(noKingFen, "bullet").exit
        yield assert(result)(fails(containsString("Search failed")))
      },
    ),
  )
