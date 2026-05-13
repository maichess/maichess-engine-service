package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.{Move, Position, Search, SearchV2, SearchV3, Square}

object SearchV3Spec extends ZIOSpecDefault:

  private val startFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  // White: Kh1, Ra1; Black: Kg8, pawns f7 g7 h7 → only Ra1-a8 is mate in 1.
  private val mateIn1Fen = "6k1/5ppp/8/8/8/8/8/R6K w - - 0 1"
  // White Qb8, Kd3; Black Ka1. 1.Kc3 (only winning move) Kb1/Ka2 2.Qb2#.
  private val mateIn2Fen = "1Q6/8/8/8/8/3K4/8/k7 w - - 0 1"
  // Black is mated (Qxf7#) — no legal moves for the side to move.
  private val matedFen   = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
  // White Ra1, Kh1; Black Qa8, Kh8 — a1xa8 wins an undefended queen.
  private val rookTakesQueenFen = "q6k/8/8/8/8/8/8/R6K w - - 0 1"
  // White Qd1, Kh1; Black Kh8, pawn d5 (defended by pawn e6) — Qd1xd5 loses the queen.
  private val queenTakesPawnFen = "7k/8/4p3/3p4/8/8/8/3Q3K w - - 0 1"

  private val regressionFens = List(
    startFen,
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
    "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R w KQkq - 0 6",
    "rnbqkb1r/pp2pppp/3p1n2/2pP4/4P3/8/PPP2PPP/RNBQKBNR w KQkq - 0 4",
  )

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))
  private def iabs(x: Int): Int = if x < 0 then -x else x

  def spec = suite("SearchV3")(

    test("finds mate in 1") {
      for pos <- fen(mateIn1Fen)
      yield
        val (mv, score) = new SearchV3().bestMove(pos, 1000L)
        assertTrue(mv != Move.None) && assertTrue(Move.toUci(mv) == "a1a8") && assertTrue(score > 90000)
    },

    test("finds a forced mate in 2") {
      for pos <- fen(mateIn2Fen)
      yield
        val (mv, score) = new SearchV3().bestMove(pos, 2000L)
        assertTrue(mv != Move.None) && assertTrue(score > 90000)
    },

    test("does not return Move.None from a position with legal moves") {
      for pos <- fen(startFen)
      yield
        val (mv, _) = new SearchV3().bestMove(pos, 500L)
        assertTrue(mv != Move.None)
    },

    test("returns Move.None from a position with no legal moves") {
      for pos <- fen(matedFen)
      yield
        val (mv, score) = new SearchV3().bestMove(pos, 500L)
        assertTrue(mv == Move.None) && assertTrue(score == 0)
    },

    test("is deterministic: two fixed-depth searches on the same position agree") {
      for
        p1 <- fen(startFen)
        p2 <- fen(startFen)
      yield
        val (m1, s1) = new SearchV3().bestMoveAtDepth(p1, 5, Array.empty[Int])
        val (m2, s2) = new SearchV3().bestMoveAtDepth(p2, 5, Array.empty[Int])
        assertTrue(m1 == m2) && assertTrue(s1 == s2)
    },

    test("move-ordering changes speed, not the verdict, versus SearchV2") {
      ZIO.foreach(regressionFens) { f =>
        for
          p1 <- fen(f)
          p2 <- fen(f)
        yield
          val (m1, s1) = new SearchV2().bestMoveAtDepth(p1, 4, Array.empty[Int])
          val (m2, s2) = new SearchV3().bestMoveAtDepth(p2, 4, Array.empty[Int])
          assertTrue(m1 != Move.None) && assertTrue(m2 != Move.None) &&
          assertTrue(m1 == m2 || iabs(s1 - s2) <= 100)
      }.map(_.reduce(_ && _))
    },

    suite("static exchange evaluation")(
      test("rook capturing an undefended queen scores the queen's value") {
        for pos <- fen(rookTakesQueenFen)
        yield
          val mv  = Move.encode(Square(0), Square(56), Move.FlagCapture)  // a1xa8
          val see = new SearchV3().seeMove(pos, mv)
          assertTrue(see == 900)
      },
      test("queen capturing a defended pawn scores negative") {
        for pos <- fen(queenTakesPawnFen)
        yield
          val mv  = Move.encode(Square(3), Square(35), Move.FlagCapture)  // d1xd5
          val see = new SearchV3().seeMove(pos, mv)
          assertTrue(see < 0)
      },
    ),

    suite("bestMoveAtDepth / extractPv")(
      test("bestMoveAtDepth returns a legal root move and extractPv starts with it") {
        for pos <- fen(startFen)
        yield
          val search    = new SearchV3()
          val (move, _) = search.bestMoveAtDepth(pos, 4, Array.empty[Int])
          val pv        = search.extractPv(pos, 8)
          assertTrue(move != Move.None) && assertTrue(pv.headOption.contains(move))
      },
      test("bestMoveAtDepth skips excluded root moves") {
        for pos <- fen(startFen)
        yield
          val (best, _)  = new SearchV3().bestMoveAtDepth(pos, 4, Array.empty[Int])
          val (other, _) = new SearchV3().bestMoveAtDepth(pos, 4, Array(best))
          assertTrue(best != Move.None) && assertTrue(other != Move.None) && assertTrue(other != best)
      },
    ),
  )
