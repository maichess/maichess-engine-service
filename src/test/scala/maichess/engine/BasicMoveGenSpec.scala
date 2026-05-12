package maichess.engine

import zio.*
import zio.test.*
import zio.test.Assertion.*
import maichess.engine.chess.basic.{BasicMoveGen, BasicPosition}

object BasicMoveGenSpec extends ZIOSpecDefault:

  private val startFen  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  // Position after 1.e4 e5 2.Nf3 Nc6 3.Bc4 — white to move, Qf7# possible
  private val mateFen   = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
  // Only one legal move — white king must move to avoid check
  private val onlyOneFen = "k7/8/8/8/8/8/6q1/7K w - - 0 1"
  // En-passant capture available
  private val epFen     = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"

  def spec = suite("BasicMoveGen")(

    suite("generateAll / legalMoves")(
      test("starting position has exactly 20 legal moves") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(startFen))
        yield assertTrue(BasicMoveGen.legalMoves(pos).length == 20)
      },
      test("starting position has 20 pseudo-legal moves") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(startFen))
        yield assertTrue(BasicMoveGen.generateAll(pos).length == 20)
      },
      test("checkmated position has no legal moves") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(mateFen))
        yield assertTrue(BasicMoveGen.legalMoves(pos).isEmpty)
      },
      test("one-move position has exactly one legal move") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(onlyOneFen))
        yield assertTrue(BasicMoveGen.legalMoves(pos).length == 1)
      },
      test("en-passant capture is generated") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(epFen))
        yield
          val moves = BasicMoveGen.legalMoves(pos)
          // e5 pawn (sq 36) can capture d6 (sq 43) en-passant
          assertTrue(moves.exists(mv => mv.from == 36 && mv.to == 43))
      },
    ),

    suite("generateCaptures")(
      test("starting position has no captures") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(startFen))
        yield assertTrue(BasicMoveGen.generateCaptures(pos).isEmpty)
      },
      test("generates at least one capture when a piece is en prise") {
        // White rook can capture black pawn
        val fen = "k7/p7/8/8/8/8/8/K6R w - - 0 1"
        for pos <- ZIO.fromEither(BasicPosition.fromFen(fen))
        yield assertTrue(BasicMoveGen.legalCaptures(pos).isEmpty)
      },
    ),

    suite("isInCheck")(
      test("starting position is not in check") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(startFen))
        yield assertTrue(!BasicMoveGen.isInCheck(pos, 1))
      },
      test("detects check in the mated position") {
        for pos <- ZIO.fromEither(BasicPosition.fromFen(mateFen))
        yield
          // The mated position has the black king in check (Qf7 delivers check)
          assertTrue(BasicMoveGen.isInCheck(pos, -1))
      },
    ),
  )
