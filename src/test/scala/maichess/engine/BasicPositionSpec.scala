package maichess.engine

import zio.*
import zio.test.*
import zio.test.Assertion.*
import maichess.engine.chess.basic.{BasicPiece, BasicPosition}

object BasicPositionSpec extends ZIOSpecDefault:

  private val startFen  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val midFen    = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR w KQkq - 2 4"
  private val epFen     = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"

  def spec = suite("BasicPosition")(

    suite("fromFen — valid positions")(
      test("parses the starting position") {
        val pos = BasicPosition.fromFen(startFen)
        assertTrue(pos.isRight) &&
        assertTrue(pos.exists(_.board(0)  == BasicPiece.WRook)) &&
        assertTrue(pos.exists(_.board(4)  == BasicPiece.WKing)) &&
        assertTrue(pos.exists(_.board(56) == BasicPiece.BRook)) &&
        assertTrue(pos.exists(_.board(60) == BasicPiece.BKing))
      },
      test("starting position side to move is white") {
        val pos = BasicPosition.fromFen(startFen)
        assertTrue(pos.exists(_.sideToMove == 1))
      },
      test("starting position has all castling rights") {
        val pos = BasicPosition.fromFen(startFen)
        assertTrue(pos.exists(_.castling == 15))
      },
      test("starting position has no en-passant square") {
        val pos = BasicPosition.fromFen(startFen)
        assertTrue(pos.exists(_.epSquare == -1))
      },
      test("starting position halfmove and fullmove") {
        val pos = BasicPosition.fromFen(startFen)
        assertTrue(pos.exists(_.halfmove == 0)) &&
        assertTrue(pos.exists(_.fullmove == 1))
      },
      test("parses a mid-game position") {
        val pos = BasicPosition.fromFen(midFen)
        assertTrue(pos.isRight) &&
        assertTrue(pos.exists(_.board(26) == BasicPiece.WBishop)) &&
        assertTrue(pos.exists(_.board(42) == BasicPiece.BKnight))
      },
      test("parses en-passant square correctly") {
        val pos = BasicPosition.fromFen(epFen)
        // d6 = rank 5, file 3 → sq = 5*8+3 = 43
        assertTrue(pos.exists(_.epSquare == 43))
      },
      test("black to move is parsed") {
        val pos = BasicPosition.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertTrue(pos.exists(_.sideToMove == -1))
      },
      test("partial castling rights are parsed") {
        val pos = BasicPosition.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 0 1")
        assertTrue(pos.exists(p => (p.castling & 1) != 0)) &&  // WK set
        assertTrue(pos.exists(p => (p.castling & 2) == 0)) &&  // WQ clear
        assertTrue(pos.exists(p => (p.castling & 4) == 0)) &&  // BK clear
        assertTrue(pos.exists(p => (p.castling & 8) != 0))     // BQ set
      },
    ),

    suite("fromFen — invalid FEN")(
      test("rejects a FEN with too few fields") {
        val result = BasicPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w")
        assert(result)(isLeft(containsString("Invalid FEN")))
      },
      test("rejects a FEN with wrong rank count") {
        val result = BasicPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assert(result)(isLeft(containsString("Invalid FEN")))
      },
    ),

    suite("makeMove / unmakeMove")(
      test("quiet move is reversible") {
        for
          pos  <- ZIO.fromEither(BasicPosition.fromFen(startFen))
          _     = { val mv = maichess.engine.chess.basic.BasicMove(12, 28); val s = pos.makeMove(mv); pos.unmakeMove(mv, s) }
        yield
          assertTrue(pos.board(12) == BasicPiece.WPawn) &&
          assertTrue(pos.board(28) == BasicPiece.Empty)
      },
    ),
  )
