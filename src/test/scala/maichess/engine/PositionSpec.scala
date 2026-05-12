package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.Position

object PositionSpec extends ZIOSpecDefault:

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))

  // White just played d4-d5? no — black played c7-c5, so c6 is the ep square.
  private val epFen    = "rnbqkbnr/pp1ppppp/8/2pP4/8/8/PPP1PPPP/RNBQKBNR w KQkq c6 0 2"
  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val midFen   = "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

  def spec = suite("Position")(

    suite("makeNullMove / unmakeNullMove")(

      test("flips the side to move and restores it on unmake") {
        for pos <- fen(startFen)
        yield
          val before = pos.sideToMove
          pos.makeNullMove()
          val flipped = pos.sideToMove
          pos.unmakeNullMove()
          assertTrue(flipped != before) && assertTrue(pos.sideToMove == before)
      },

      test("clears the en-passant square and restores it on unmake") {
        for pos <- fen(epFen)
        yield
          val before = pos.epSquare
          pos.makeNullMove()
          val duringEp = pos.epSquare
          pos.unmakeNullMove()
          assertTrue(before >= 0 && before < 64) &&
          assertTrue(duringEp != before) &&
          assertTrue(pos.epSquare == before)
      },

      test("restores castling rights and clocks on unmake") {
        for pos <- fen(epFen)
        yield
          val cr = pos.castlingRights
          val hm = pos.halfMoveClock
          val fm = pos.fullMoveNumber
          pos.makeNullMove()
          pos.unmakeNullMove()
          assertTrue(pos.castlingRights == cr) &&
          assertTrue(pos.halfMoveClock == hm) &&
          assertTrue(pos.fullMoveNumber == fm)
      },

      test("hash changes while the null move is in effect and is restored on unmake") {
        for pos <- fen(midFen)
        yield
          val h0 = pos.hash
          pos.makeNullMove()
          val h1 = pos.hash
          pos.unmakeNullMove()
          assertTrue(h1 != h0) && assertTrue(pos.hash == h0)
      },

      test("incremental hash after a null move matches a full recomputation") {
        for pos <- fen(epFen)
        yield
          pos.makeNullMove()
          val incremental = pos.hash
          pos.recomputeHash()
          val recomputed = pos.hash
          pos.unmakeNullMove()
          assertTrue(incremental == recomputed)
      },

      test("a null move pair is a no-op on a position with no ep square") {
        for pos <- fen(startFen)
        yield
          val h0  = pos.hash
          val ep0 = pos.epSquare
          pos.makeNullMove()
          pos.unmakeNullMove()
          assertTrue(pos.hash == h0) && assertTrue(pos.epSquare == ep0)
      },
    ),
  )
