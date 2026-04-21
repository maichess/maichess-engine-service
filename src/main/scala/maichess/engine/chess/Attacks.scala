package maichess.engine.chess

// ── Static attack tables ──────────────────────────────────────────────────────
// Built once at JVM startup. Every lookup is a single array read — O(1) with
// no branching and no allocation.
//
// Slider attacks (bishop, rook, queen) require Magic Bitboards and live in Magics.
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object Attacks:

  // Pawn attacks: indexed by [color][square]
  // pawnAttacks(Col.White)(sq) = bitboard of squares attacked by a white pawn on sq
  val pawnAttacks: Array[Array[Long]] = Array(
    new Array[Long](64),   // White
    new Array[Long](64)    // Black
  )

  val knightAttacks: Array[Long] = new Array[Long](64)
  val kingAttacks:   Array[Long] = new Array[Long](64)

  // castlingMask(sq): AND this into castling rights when a piece moves from/to sq.
  // Any rook or king move instantly clears the relevant castling right in O(1).
  // Squares not involved in castling have mask 15 (no bits cleared).
  val castlingMask: Array[Int] = Array.fill(64)(Castling.All)

  // ── Table initialisation ──────────────────────────────────────────────────
  locally:
    var sq = 0
    while sq < 64 do
      val s   = Square(sq)
      val bb  = BB.bit(s)

      pawnAttacks(Col.White)(sq) = BB.shiftNE(bb) | BB.shiftNW(bb)
      pawnAttacks(Col.Black)(sq) = BB.shiftSE(bb) | BB.shiftSW(bb)

      val notA  = BB.NotFileA
      val notH  = BB.NotFileH
      val notAB = notA & (notA << 1)
      val notGH = notH & (notH >>> 1)
      knightAttacks(sq) =
        ((bb & notA)  << 15) | ((bb & notH)  << 17) |
        ((bb & notA)  >>> 17)| ((bb & notH)  >>> 15)|
        ((bb & notAB) << 6)  | ((bb & notGH) << 10) |
        ((bb & notAB) >>> 10)| ((bb & notGH) >>> 6)

      val side = BB.shiftE(bb) | BB.shiftW(bb) | bb
      kingAttacks(sq) = (BB.shiftN(side) | BB.shiftS(side) | side) & ~bb

      sq += 1

    castlingMask(Sq.A1.toInt) &= ~Castling.WQ
    castlingMask(Sq.H1.toInt) &= ~Castling.WK
    castlingMask(Sq.E1.toInt) &= ~(Castling.WK | Castling.WQ)
    castlingMask(Sq.A8.toInt) &= ~Castling.BQ
    castlingMask(Sq.H8.toInt) &= ~Castling.BK
    castlingMask(Sq.E8.toInt) &= ~(Castling.BK | Castling.BQ)
