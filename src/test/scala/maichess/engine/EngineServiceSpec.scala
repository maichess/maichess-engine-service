package maichess.engine

import zio.test.*
import zio.test.Assertion.*
import zio.stream.ZStream
import maichess.engine.service.EngineServiceLive

object EngineServiceSpec extends ZIOSpecDefault:

  private val startFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val mateIn1Fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"
  private val mateFen    = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
  private val noKingFen  = "k7/8/8/8/8/8/8/8 w - - 0 1"

  private val svc = new EngineServiceLive()

  def spec = suite("EngineServiceLive")(

    suite("listBots")(
      test("returns all nine bots") {
        for bots <- svc.listBots
        yield assertTrue(bots.length == 9)
      },
      test("first three bot ids are the bullet variants") {
        for bots <- svc.listBots
        yield assertTrue(bots.map(_.id).take(3) == List("bullet", "bullet_proportional", "bullet_aggressive"))
      },
    ),

    suite("bestMove — without game clock")(
      test("returns a valid UCI move for the starting position") {
        for result <- svc.bestMove(startFen, "bullet", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("finds the mate-in-one") {
        for result <- svc.bestMove(mateIn1Fen, "classical", None)
        yield
          val (move, _) = result
          assertTrue(move == "d8h4")
      },
      test("fails with an informative message for unknown bot id") {
        for result <- svc.bestMove(startFen, "bogus", None).exit
        yield assert(result)(fails(containsString("Unknown bot: bogus")))
      },
      test("fails with an informative message for invalid FEN") {
        for result <- svc.bestMove("not a fen", "bullet", None).exit
        yield assert(result)(fails(containsString("Invalid FEN")))
      },
      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "bullet", None).exit
        yield assert(result)(fails(containsString("No legal moves")))
      },
      test("fails when the search throws on a degenerate position") {
        for result <- svc.bestMove(noKingFen, "bullet", None).exit
        yield assert(result)(fails(containsString("Search failed")))
      },
    ),

    suite("bestMove — with game clock")(
      test("returns a valid move when remaining time is provided") {
        for result <- svc.bestMove(startFen, "bullet_proportional", Some(60000L))
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("uses fallback when remaining time is zero") {
        for result <- svc.bestMove(startFen, "blitz_aggressive", Some(0L))
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
    ),

    suite("analyzePosition")(
      test("emits at least one update for a valid position") {
        for updates <- svc.analyzePosition(startFen, "bullet", 1).take(1).runCollect
        yield assertTrue(updates.length == 1)
      },
      test("each update has the correct depth and one line") {
        for updates <- svc.analyzePosition(startFen, "bullet", 1).take(3).runCollect
        yield
          assertTrue(updates.head.depth == 1) &&
          assertTrue(updates.forall(_.lines.length == 1)) &&
          assertTrue(updates.forall(_.lines.head.rank == 1))
      },
      test("multi-line analysis returns up to lineCount lines") {
        for updates <- svc.analyzePosition(startFen, "bullet", 3).take(1).runCollect
        yield
          val lines = updates.head.lines
          assertTrue(lines.length <= 3) &&
          assertTrue(lines.map(_.rank) == (1 to lines.length).toList)
      },
      test("each line contains at least one move") {
        for updates <- svc.analyzePosition(startFen, "bullet", 1).take(1).runCollect
        yield assertTrue(updates.head.lines.head.moves.nonEmpty)
      },
      test("fails for unknown bot id") {
        for result <- svc.analyzePosition(startFen, "bogus", 1).runCollect.exit
        yield assert(result)(fails(containsString("Unknown bot: bogus")))
      },
      test("fails for invalid FEN") {
        for result <- svc.analyzePosition("not a fen", "bullet", 1).runCollect.exit
        yield assert(result)(fails(containsString("Invalid FEN")))
      },
    ),
  )
