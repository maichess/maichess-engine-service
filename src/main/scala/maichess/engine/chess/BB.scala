package maichess.engine.chess

// ── Bitboard utilities ────────────────────────────────────────────────────────
// Shift/query methods are `inline` — the Scala 3 compiler emits the expression
// directly at the call site: no virtual dispatch, no boxing.
//
// Mask constants are `final val`: stored as static long fields, which the JIT
// treats as compile-time constants and will constant-fold into surrounding
// arithmetic.
//
// JVM intrinsics:
//   numberOfTrailingZeros → BSF / TZCNT  (single cycle on x86-64)
//   bitCount              → POPCNT       (single cycle on x86-64)
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object BB:

  // ── File masks ────────────────────────────────────────────────────────────
  final val FileA: Long = 0x0101010101010101L
  final val FileB: Long = 0x0202020202020202L
  final val FileC: Long = 0x0404040404040404L
  final val FileD: Long = 0x0808080808080808L
  final val FileE: Long = 0x1010101010101010L
  final val FileF: Long = 0x2020202020202020L
  final val FileG: Long = 0x4040404040404040L
  final val FileH: Long = 0x8080808080808080L

  final val NotFileA: Long = 0xFEFEFEFEFEFEFEFEL
  final val NotFileH: Long = 0x7F7F7F7F7F7F7F7FL

  // ── Rank masks ────────────────────────────────────────────────────────────
  final val Rank1: Long = 0x00000000000000FFL
  final val Rank2: Long = 0x000000000000FF00L
  final val Rank3: Long = 0x0000000000FF0000L
  final val Rank4: Long = 0x00000000FF000000L
  final val Rank5: Long = 0x000000FF00000000L
  final val Rank6: Long = 0x0000FF0000000000L
  final val Rank7: Long = 0x00FF000000000000L
  final val Rank8: Long = 0xFF00000000000000L

  // ── Bit queries ───────────────────────────────────────────────────────────
  inline def bit(sq: Square): Long          = 1L << sq.toInt
  inline def test(bb: Long, sq: Square): Boolean = (bb & bit(sq)) != 0L
  inline def nonEmpty(bb: Long): Boolean    = bb != 0L
  inline def isEmpty(bb: Long): Boolean     = bb == 0L
  inline def popcount(bb: Long): Int        = java.lang.Long.bitCount(bb)

  // Least-significant bit index (undefined if bb == 0)
  inline def lsb(bb: Long): Square = Square(java.lang.Long.numberOfTrailingZeros(bb))
  // Most-significant bit index (undefined if bb == 0)
  inline def msb(bb: Long): Square = Square(63 - java.lang.Long.numberOfLeadingZeros(bb))

  // Pop the LSB: returns the square; caller must call clearLsb separately.
  //   while (bb != 0L) { val sq = popLsb(bb); bb = clearLsb(bb); ... }
  inline def popLsb(bb: Long): Square  = lsb(bb)
  inline def clearLsb(bb: Long): Long  = bb & (bb - 1L)

  // ── Bit manipulation ──────────────────────────────────────────────────────
  inline def set(bb: Long, sq: Square): Long    = bb | bit(sq)
  inline def clear(bb: Long, sq: Square): Long  = bb & ~bit(sq)
  inline def toggle(bb: Long, sq: Square): Long = bb ^ bit(sq)

  // ── Directional shifts ────────────────────────────────────────────────────
  // Masking before shift prevents wrap-around at file boundaries.
  inline def shiftN(bb: Long): Long  = bb << 8
  inline def shiftS(bb: Long): Long  = bb >>> 8
  inline def shiftE(bb: Long): Long  = (bb & NotFileH) << 1
  inline def shiftW(bb: Long): Long  = (bb & NotFileA) >>> 1
  inline def shiftNE(bb: Long): Long = (bb & NotFileH) << 9
  inline def shiftNW(bb: Long): Long = (bb & NotFileA) << 7
  inline def shiftSE(bb: Long): Long = (bb & NotFileH) >>> 7
  inline def shiftSW(bb: Long): Long = (bb & NotFileA) >>> 9

  // ── Pawn attack helpers ───────────────────────────────────────────────────
  inline def pawnAttacksWhite(pawns: Long): Long = shiftNE(pawns) | shiftNW(pawns)
  inline def pawnAttacksBlack(pawns: Long): Long = shiftSE(pawns) | shiftSW(pawns)
