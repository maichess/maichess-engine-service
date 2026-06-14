package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.{OpeningBook, PolyglotZobrist, Position}

import java.nio.ByteBuffer

object OpeningBookSpec extends ZIOSpecDefault:

  private val startFen      = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val midGame31Fen  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 31"
  private val castlingFen   = "4k3/8/8/8/8/8/8/4K2R w K - 0 1"
  private val unbookedFen   = "8/8/8/3k4/8/3K4/8/4R3 w - - 0 5"

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))

  // Build a single-entry Polyglot book buffer for the given key, move bits, weight.
  private def oneEntryBuffer(key: Long, moveBits: Int, weight: Int): ByteBuffer =
    val buf = ByteBuffer.allocate(16)
    buf.putLong(0, key)
    buf.putShort(8, moveBits.toShort)
    buf.putShort(10, weight.toShort)
    buf.putInt(12, 0)
    buf

  // Polyglot move bits: promo(3, bits 12-14) | from(6, bits 6-11) | to(6, bits 0-5)
  private def encodeMove(from: Int, to: Int, promo: Int): Int =
    (promo << 12) | (from << 6) | to

  // Build a multi-entry book buffer (all sharing one key) from (moveBits, weight) pairs.
  private def multiEntryBuffer(key: Long, entries: List[(Int, Int)]): ByteBuffer =
    val buf = ByteBuffer.allocate(16 * entries.length)
    entries.zipWithIndex.foreach { case ((moveBits, weight), i) =>
      buf.putLong(i * 16, key)
      buf.putShort(i * 16 + 8, moveBits.toShort)
      buf.putShort(i * 16 + 10, weight.toShort)
      buf.putInt(i * 16 + 12, 0)
    }
    buf

  def spec = suite("OpeningBook")(

    test("probing the starting position returns either a legal UCI move or None") {
      // Tolerant of whether performance.bin is on the classpath: if present
      // we get a real book move; if absent we get None and fall through.
      for pos <- fen(startFen)
      yield OpeningBook.probe(pos) match
        case Some(uci) => assertTrue(uci.length >= 4 && uci.length <= 5)
        case None      => assertCompletes
    },

    test("returns None when fullmove number exceeds 30") {
      for pos <- fen(midGame31Fen)
      yield assertTrue(OpeningBook.probe(pos).isEmpty)
    },

    test("probeBuffer returns None for an empty buffer") {
      for pos <- fen(startFen)
      yield assertTrue(OpeningBook.probeBuffer(pos, ByteBuffer.allocate(0)).isEmpty)
    },

    test("probeBuffer returns None when no entry matches the key") {
      for pos <- fen(startFen)
      yield
        val buf = oneEntryBuffer(key = 0xDEADBEEFCAFEBABEL, moveBits = encodeMove(8, 16, 0), weight = 1)
        assertTrue(OpeningBook.probeBuffer(pos, buf).isEmpty)
    },

    test("probeBuffer returns None when all matching entries have zero weight") {
      for pos <- fen(startFen)
      yield
        val key = PolyglotZobrist.key(pos)
        val buf = oneEntryBuffer(key = key, moveBits = encodeMove(8, 16, 0), weight = 0)
        assertTrue(OpeningBook.probeBuffer(pos, buf).isEmpty)
    },

    test("probeBuffer returns None when the decoded move is not legal in the position") {
      // Encodes a8-h8 from the start position — no rook there, not legal.
      for pos <- fen(startFen)
      yield
        val key = PolyglotZobrist.key(pos)
        val buf = oneEntryBuffer(key = key, moveBits = encodeMove(56, 63, 0), weight = 100)
        assertTrue(OpeningBook.probeBuffer(pos, buf).isEmpty)
    },

    test("probeBuffer decodes a book-encoded castling move (e1h1 → e1g1)") {
      for pos <- fen(castlingFen)
      yield
        val key = PolyglotZobrist.key(pos)
        // Polyglot encodes white O-O as e1h1: from=4 (e1), to=7 (h1).
        val buf = oneEntryBuffer(key = key, moveBits = encodeMove(4, 7, 0), weight = 100)
        assertTrue(OpeningBook.probeBuffer(pos, buf) == Some("e1g1"))
    },

    test("probeBuffer deterministically picks the highest-weight move (strongest play)") {
      // Two book moves for the start position: a low-weight e2e4 and a high-weight
      // d2d4. Max-strength selection always returns the heavier-weighted move.
      for pos <- fen(startFen)
      yield
        val key = PolyglotZobrist.key(pos)
        val buf = multiEntryBuffer(key, List(
          (encodeMove(12, 28, 0), 10),    // e2e4, weight 10
          (encodeMove(11, 27, 0), 100),   // d2d4, weight 100
        ))
        assertTrue(OpeningBook.probeBuffer(pos, buf) == Some("d2d4"))
    },

    test("probeBuffer returns None for a position whose decoded move references an empty square") {
      for pos <- fen(unbookedFen)
      yield
        val key = PolyglotZobrist.key(pos)
        // a1-a2 — there's no piece on a1, so no legal move can match.
        val buf = oneEntryBuffer(key = key, moveBits = encodeMove(0, 8, 0), weight = 100)
        assertTrue(OpeningBook.probeBuffer(pos, buf).isEmpty)
    },
  )
