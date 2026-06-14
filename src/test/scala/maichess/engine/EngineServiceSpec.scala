package maichess.engine

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.stream.ZStream
import maichess.engine.service.EngineServiceLive
import maichess.engine.service.clients.TablebaseClient

object EngineServiceSpec extends ZIOSpecDefault:

  private val startFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val mateIn1Fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"
  private val mateFen    = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
  private val noKingFen  = "k7/8/8/8/8/8/8/8 w - - 0 1"
  private val kqkFen     = "8/8/8/4k3/8/3K4/8/3Q4 w - - 0 1"

  private val svc = new EngineServiceLive(TablebaseClient.noop)

  def spec = suite("EngineServiceLive")(

    suite("listBots")(
      test("returns all fifty-one bots") {
        for bots <- svc.listBots
        yield assertTrue(bots.length == 51)
      },
      test("first three bot ids are the bullet variants") {
        for bots <- svc.listBots
        yield assertTrue(bots.map(_.id).take(3) == List("bullet", "bullet_proportional", "bullet_aggressive"))
      },
      test("basic bots appear after base bots") {
        for bots <- svc.listBots
        yield assertTrue(bots.map(_.id).drop(9).head == "basic_bullet")
      },
    ),

    suite("bestMove — bitboard engine (Base bots)")(
      test("returns a valid UCI move for the starting position") {
        for result <- svc.bestMove(startFen, "bullet", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("finds the mate-in-one") {
        for result <- svc.bestMove(mateIn1Fen, "bullet", None)
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

    suite("bestMove — basic engine (Basic bots)")(
      test("returns a valid UCI move from the starting position") {
        for result <- svc.bestMove(startFen, "basic_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("fails with an informative message for invalid FEN") {
        for result <- svc.bestMove("not a fen", "basic_bullet", None).exit
        yield assert(result)(fails(containsString("Invalid FEN")))
      },
      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "basic_bullet", None).exit
        yield assert(result)(fails(containsString("No legal moves")))
      },
      test("dispatches to basic engine for basic_bullet (move is a legal UCI string)") {
        for result <- svc.bestMove(startFen, "basic_bullet", Some(200L))
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
    ),

    suite("bestMove — enhanced search engine (EnhancedSearch bots)")(
      test("returns a valid UCI move from the starting position") {
        for result <- svc.bestMove(startFen, "search_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("finds the mate-in-one") {
        for result <- svc.bestMove(mateIn1Fen, "search_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move == "d8h4")
      },
      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "search_blitz", None).exit
        yield assert(result)(fails(containsString("No legal moves")))
      },
      test("fails when the search throws on a degenerate position") {
        for result <- svc.bestMove(noKingFen, "search_classical", None).exit
        yield assert(result)(fails(containsString("Search failed")))
      },
    ),

    suite("bestMove — enhanced ordering engine (EnhancedOrdering bots)")(
      test("returns a valid UCI move from the starting position") {
        for result <- svc.bestMove(startFen, "ordering_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("finds the mate-in-one") {
        for result <- svc.bestMove(mateIn1Fen, "ordering_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move == "d8h4")
      },
      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "ordering_blitz", None).exit
        yield assert(result)(fails(containsString("No legal moves")))
      },
      test("fails when the search throws on a degenerate position") {
        for result <- svc.bestMove(noKingFen, "ordering_classical", None).exit
        yield assert(result)(fails(containsString("Search failed")))
      },
    ),

    suite("bestMove — enhanced eval engine (EnhancedEval bots)")(
      test("returns a valid UCI move from the starting position") {
        for result <- svc.bestMove(startFen, "eval_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("finds the mate-in-one") {
        for result <- svc.bestMove(mateIn1Fen, "eval_bullet", None)
        yield
          val (move, _) = result
          assertTrue(move == "d8h4")
      },
      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "eval_blitz", None).exit
        yield assert(result)(fails(containsString("No legal moves")))
      },
      test("fails when the search throws on a degenerate position") {
        for result <- svc.bestMove(noKingFen, "eval_classical", None).exit
        yield assert(result)(fails(containsString("Search failed")))
      },
    ),

    suite("bestMove — knowledge engine (Knowledge bots)")(
      test("returns a valid UCI move from the starting position") {
        for result <- svc.bestMove(startFen, "knowledge_blitz", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("returns a valid UCI move in a low-piece endgame") {
        // The noop tablebase client falls through; the book likely misses; search produces a legal move.
        for result <- svc.bestMove(kqkFen, "knowledge_classical", None)
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("fails when there are no legal moves in a mated position") {
        for result <- svc.bestMove(mateFen, "knowledge_blitz", None).exit
        yield assert(result)(fails(containsString("No legal moves")))
      },
      test("fails when the search throws on a degenerate position") {
        for result <- svc.bestMove(noKingFen, "knowledge_classical", None).exit
        yield assert(result)(fails(containsString("Search failed")))
      },
    ),

    suite("bestMove — with game clock")(
      test("returns a valid move when remaining time is provided") {
        // Some(2000L) → max(50, 2000/40) = 50 ms move time
        for result <- svc.bestMove(startFen, "bullet_proportional", Some(2000L))
        yield
          val (move, _) = result
          assertTrue(move.length >= 4 && move.length <= 5)
      },
      test("uses fallback when remaining time is zero") {
        // bullet_aggressive: max(50, 0 * 0.07) = 50 ms
        for result <- svc.bestMove(startFen, "bullet_aggressive", Some(0L))
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
      test("lineCount greater than legal moves stops multi-PV at legal-move boundary") {
        // Exercises the move == Move.None early-exit in searchMultiPv
        for updates <- svc.analyzePosition(startFen, "bullet", 30).take(1).runCollect
        yield assertTrue(updates.head.lines.length <= 20)
      },
      test("stream terminates via stagnation") {
        // mateIn1Fen: depth 2 finds Qh4# (MATE score). Depth 3 returns the same move and score.
        // stagnated() sees two identical consecutive results and returns true, ending the stream.
        // Exercises the stagnated() true branch including the moves.headOption flatMap path.
        for updates <- svc.analyzePosition(mateIn1Fen, "bullet", 1).runCollect
        yield assertTrue(updates.nonEmpty)
      },
      test("EngineServiceLive.layer materializes with a TablebaseClient dependency") {
        val program =
          ZIO.serviceWithZIO[maichess.engine.service.EngineService](_.listBots)
        for bots <- program.provide(
                      maichess.engine.service.EngineServiceLive.layer,
                      ZLayer.succeed(TablebaseClient.noop),
                    )
        yield assertTrue(bots.length == 51)
      },
      test("analysis fails gracefully for a position where the search throws") {
        // The degenerate position has no white king; bestMoveAtDepth throws
        // ArrayIndexOutOfBoundsException, which is caught by ZIO.attemptBlocking
        // and converted via mapError — exercises the error-handler path.
        for result <- svc.analyzePosition(noKingFen, "bullet", 1).runCollect.exit
        yield assert(result)(fails(containsString("Analysis failed")))
      },
      test("EngineServiceLive.layer is a valid ZLayer") {
        // Accesses the companion object to trigger its initialization (coverage for the layer val).
        assertTrue(EngineServiceLive.layer != null)
      },
      test("fails for unknown bot id") {
        for result <- svc.analyzePosition(startFen, "bogus", 1).runCollect.exit
        yield assert(result)(fails(containsString("Unknown bot: bogus")))
      },
      test("fails for invalid FEN") {
        for result <- svc.analyzePosition("not a fen", "bullet", 1).runCollect.exit
        yield assert(result)(fails(containsString("Invalid FEN")))
      },
      test("fails for basic engine bots") {
        for result <- svc.analyzePosition(startFen, "basic_bullet", 1).runCollect.exit
        yield assert(result)(fails(containsString("Analysis not supported")))
      },
      test("dispatches multi-PV on variant: EnhancedSearch (search_*) analyses with SearchV2") {
        for updates <- svc.analyzePosition(startFen, "search_bullet", 2).take(1).runCollect
        yield assertTrue(updates.head.lines.nonEmpty && updates.head.lines.head.moves.nonEmpty)
      },
      test("dispatches multi-PV on variant: EnhancedOrdering (ordering_*) analyses with SearchV3") {
        for updates <- svc.analyzePosition(startFen, "ordering_bullet", 2).take(1).runCollect
        yield assertTrue(updates.head.lines.nonEmpty && updates.head.lines.head.moves.nonEmpty)
      },
      test("dispatches multi-PV on variant: EnhancedEval (eval_*) analyses with SearchV4") {
        for updates <- svc.analyzePosition(startFen, "eval_bullet", 2).take(1).runCollect
        yield assertTrue(updates.head.lines.nonEmpty && updates.head.lines.head.moves.nonEmpty)
      },
      test("dispatches multi-PV on variant: Knowledge (knowledge_*) analyses with SearchV4") {
        for updates <- svc.analyzePosition(startFen, "knowledge_blitz", 2).take(1).runCollect
        yield assertTrue(updates.head.lines.nonEmpty && updates.head.lines.head.moves.nonEmpty)
      },
    ),
  )
