package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.{Move, Position, Search, SearchV2}

object SearchV2Spec extends ZIOSpecDefault:

  private val startFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  // White: Kh1, Ra1; Black: Kg8, pawns f7 g7 h7 → only Ra1-a8 is mate in 1.
  private val mateIn1Fen = "6k1/5ppp/8/8/8/8/8/R6K w - - 0 1"
  // Black is mated (Qxf7#) — no legal moves for the side to move.
  private val matedFen   = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
  // K+P vs K, black has the opposition in front of the pawn — a theoretical draw.
  // Used to confirm NMP's non-pawn-material guard doesn't break zugzwang-prone endings.
  private val kpkDrawFen = "8/8/8/4k3/8/8/4P3/4K3 w - - 0 1"
  // A few tactical/positional middlegame positions for the Search-vs-SearchV2 regression.
  private val regressionFens = List(
    startFen,
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
    "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R w KQkq - 0 6",
    "rnbqkb1r/pp2pppp/3p1n2/2pP4/4P3/8/PPP2PPP/RNBQKBNR w KQkq - 0 4",
  )

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))

  private def iabs(x: Int): Int = if x < 0 then -x else x

  def spec = suite("SearchV2")(

    test("finds mate in 1") {
      for pos <- fen(mateIn1Fen)
      yield
        val (mv, score) = new SearchV2().bestMove(pos, 1000L)
        assertTrue(mv != Move.None) &&
        assertTrue(Move.toUci(mv) == "a1a8") &&
        assertTrue(score > 90000)
    },

    test("does not return Move.None from a position with legal moves") {
      for pos <- fen(startFen)
      yield
        val (mv, _) = new SearchV2().bestMove(pos, 500L)
        assertTrue(mv != Move.None)
    },

    test("returns Move.None from a position with no legal moves") {
      for pos <- fen(matedFen)
      yield
        val (mv, score) = new SearchV2().bestMove(pos, 500L)
        assertTrue(mv == Move.None) && assertTrue(score == 0)
    },

    test("does not change the search verdict versus Search (move or score agrees)") {
      // LMR/NMP can legitimately pick a different move among near-equal options,
      // so the regression check is: same move, or the evaluation is within a
      // pawn of the base engine's at the same fixed depth.
      ZIO.foreach(regressionFens) { f =>
        for
          p1 <- fen(f)
          p2 <- fen(f)
        yield
          val (m1, s1) = new Search().bestMoveAtDepth(p1, 4, Array.empty[Int])
          val (m2, s2) = new SearchV2().bestMoveAtDepth(p2, 4, Array.empty[Int])
          assertTrue(m1 != Move.None) && assertTrue(m2 != Move.None) &&
          assertTrue(m1 == m2 || iabs(s1 - s2) <= 100)
      }.map(_.reduce(_ && _))
    },

    test("handles a zugzwang-prone K+P vs K endgame without a mate-score blunder") {
      for pos <- fen(kpkDrawFen)
      yield
        val (mv, score) = new SearchV2().bestMove(pos, 1000L)
        assertTrue(mv != Move.None) && assertTrue(iabs(score) < 50000)
    },

    suite("bestMoveAtDepth / extractPv")(
      test("bestMoveAtDepth returns a legal root move and extractPv starts with it") {
        for pos <- fen(startFen)
        yield
          val search        = new SearchV2()
          val (move, _)     = search.bestMoveAtDepth(pos, 4, Array.empty[Int])
          val pv            = search.extractPv(pos, 8)
          assertTrue(move != Move.None) && assertTrue(pv.headOption.contains(move))
      },
      test("bestMoveAtDepth skips excluded root moves") {
        for pos <- fen(startFen)
        yield
          val s1            = new SearchV2()
          val (best, _)     = s1.bestMoveAtDepth(pos, 4, Array.empty[Int])
          val s2            = new SearchV2()
          val (other, _)    = s2.bestMoveAtDepth(pos, 4, Array(best))
          assertTrue(best != Move.None) && assertTrue(other != Move.None) && assertTrue(other != best)
      },
    ),
  )
