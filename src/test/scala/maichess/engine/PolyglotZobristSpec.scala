package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.{PolyglotZobrist, Position}

object PolyglotZobristSpec extends ZIOSpecDefault:

  // Three well-known Polyglot keys used as a regression suite. Together they
  // exercise piece XORs, side-to-move XOR, the castling-per-right encoding,
  // and the strict en-passant rule (XOR only when capture is possible).
  private val StartFen     = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val AfterE4Fen   = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
  private val AfterE4D5Fen = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2"

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))

  def spec = suite("PolyglotZobrist")(

    test("starting position key matches the published value") {
      for pos <- fen(StartFen)
      yield assertTrue(PolyglotZobrist.key(pos) == 0x463b96181691fc9cL)
    },

    test("after 1. e4 key matches the published value (no EP XOR — black cannot capture)") {
      for pos <- fen(AfterE4Fen)
      yield assertTrue(PolyglotZobrist.key(pos) == 0x823c9b50fd114196L)
    },

    test("after 1. e4 d5 key matches the published value (no EP XOR — no white pawn can capture on d6)") {
      for pos <- fen(AfterE4D5Fen)
      yield assertTrue(PolyglotZobrist.key(pos) == 0x0756b94461c50fb0L)
    },

    test("key changes after a single move") {
      for
        start <- fen(StartFen)
        after <- fen(AfterE4Fen)
      yield assertTrue(PolyglotZobrist.key(start) != PolyglotZobrist.key(after))
    },
  )
