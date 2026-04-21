package maichess.engine.chess

// ── Primitive aliases ─────────────────────────────────────────────────────────
// Opaque types erase to their underlying primitive at the JVM level —
// zero allocation, zero boxing.

opaque type Bitboard = Long
object Bitboard:
  inline def apply(v: Long): Bitboard = v
  inline def empty: Bitboard          = 0L
  extension (b: Bitboard)
    inline def toLong: Long = b

opaque type Square = Int   // 0–63, a1=0, h8=63
object Square:
  inline def apply(v: Int): Square = v
  extension (s: Square)
    inline def toInt: Int = s

opaque type Piece = Int    // 4-bit: (color << 3) | pieceType; NoPiece = 14
object Piece:
  inline def apply(v: Int): Piece = v
  extension (p: Piece)
    inline def toInt: Int = p

// ── Piece-type constants (bits 0–2) ──────────────────────────────────────────
// No type annotation on inline val so the type is a literal singleton (0, 1 …)
object PType:
  inline val Pawn   = 0
  inline val Knight = 1
  inline val Bishop = 2
  inline val Rook   = 3
  inline val Queen  = 4
  inline val King   = 5

// ── Color constants ───────────────────────────────────────────────────────────
object Col:
  inline val White  = 0
  inline val Black  = 1

// ── Piece encoding ────────────────────────────────────────────────────────────
object Pieces:
  inline def make(color: Int, ptype: Int): Piece = Piece((color << 3) | ptype)
  inline def colorOf(p: Piece): Int  = p.toInt >> 3
  inline def typeOf(p: Piece): Int   = p.toInt & 7

  // NoPiece sentinel: value 14 fits in 4 bits and is never a valid piece code
  inline def NoPiece: Piece = Piece(14)

  inline def WPawn:   Piece = make(Col.White, PType.Pawn)
  inline def WKnight: Piece = make(Col.White, PType.Knight)
  inline def WBishop: Piece = make(Col.White, PType.Bishop)
  inline def WRook:   Piece = make(Col.White, PType.Rook)
  inline def WQueen:  Piece = make(Col.White, PType.Queen)
  inline def WKing:   Piece = make(Col.White, PType.King)
  inline def BPawn:   Piece = make(Col.Black, PType.Pawn)
  inline def BKnight: Piece = make(Col.Black, PType.Knight)
  inline def BBishop: Piece = make(Col.Black, PType.Bishop)
  inline def BRook:   Piece = make(Col.Black, PType.Rook)
  inline def BQueen:  Piece = make(Col.Black, PType.Queen)
  inline def BKing:   Piece = make(Col.Black, PType.King)

// ── Square constants (a1=0 … h8=63) ──────────────────────────────────────────
// inline def so every usage expands to a literal Int at the call site.
object Sq:
  inline def A1: Square = Square(0);  inline def B1: Square = Square(1)
  inline def C1: Square = Square(2);  inline def D1: Square = Square(3)
  inline def E1: Square = Square(4);  inline def F1: Square = Square(5)
  inline def G1: Square = Square(6);  inline def H1: Square = Square(7)
  inline def A2: Square = Square(8);  inline def B2: Square = Square(9)
  inline def C2: Square = Square(10); inline def D2: Square = Square(11)
  inline def E2: Square = Square(12); inline def F2: Square = Square(13)
  inline def G2: Square = Square(14); inline def H2: Square = Square(15)
  inline def A3: Square = Square(16); inline def B3: Square = Square(17)
  inline def C3: Square = Square(18); inline def D3: Square = Square(19)
  inline def E3: Square = Square(20); inline def F3: Square = Square(21)
  inline def G3: Square = Square(22); inline def H3: Square = Square(23)
  inline def A4: Square = Square(24); inline def B4: Square = Square(25)
  inline def C4: Square = Square(26); inline def D4: Square = Square(27)
  inline def E4: Square = Square(28); inline def F4: Square = Square(29)
  inline def G4: Square = Square(30); inline def H4: Square = Square(31)
  inline def A5: Square = Square(32); inline def B5: Square = Square(33)
  inline def C5: Square = Square(34); inline def D5: Square = Square(35)
  inline def E5: Square = Square(36); inline def F5: Square = Square(37)
  inline def G5: Square = Square(38); inline def H5: Square = Square(39)
  inline def A6: Square = Square(40); inline def B6: Square = Square(41)
  inline def C6: Square = Square(42); inline def D6: Square = Square(43)
  inline def E6: Square = Square(44); inline def F6: Square = Square(45)
  inline def G6: Square = Square(46); inline def H6: Square = Square(47)
  inline def A7: Square = Square(48); inline def B7: Square = Square(49)
  inline def C7: Square = Square(50); inline def D7: Square = Square(51)
  inline def E7: Square = Square(52); inline def F7: Square = Square(53)
  inline def G7: Square = Square(54); inline def H7: Square = Square(55)
  inline def A8: Square = Square(56); inline def B8: Square = Square(57)
  inline def C8: Square = Square(58); inline def D8: Square = Square(59)
  inline def E8: Square = Square(60); inline def F8: Square = Square(61)
  inline def G8: Square = Square(62); inline def H8: Square = Square(63)
  inline def None: Square = Square(64)   // sentinel: no square

  inline def file(s: Square): Int = s.toInt & 7
  inline def rank(s: Square): Int = s.toInt >> 3
  inline def make(f: Int, r: Int): Square = Square((r << 3) | f)

// ── Castling rights (4-bit flags) ─────────────────────────────────────────────
// No `: Int` annotation — literal type keeps `inline val` legal in Scala 3
object Castling:
  inline val WK   = 1   // White kingside
  inline val WQ   = 2   // White queenside
  inline val BK   = 4   // Black kingside
  inline val BQ   = 8   // Black queenside
  inline val All  = 15
  inline val None = 0

// ── Move encoding (Int, 16 bits used) ────────────────────────────────────────
// bits  0– 5 : from square
// bits  6–11 : to square
// bits 12–15 : flags (standard 4-bit encoding)
object Move:
  inline val FlagQuiet      = 0
  inline val FlagDoublePush = 1
  inline val FlagKCastle    = 2
  inline val FlagQCastle    = 3
  inline val FlagCapture    = 4
  inline val FlagEP         = 5
  inline val FlagNPromo     = 8
  inline val FlagBPromo     = 9
  inline val FlagRPromo     = 10
  inline val FlagQPromo     = 11
  inline val FlagNPromoCapt = 12
  inline val FlagBPromoCapt = 13
  inline val FlagRPromoCapt = 14
  inline val FlagQPromoCapt = 15
  inline val None           = -1

  inline def encode(from: Square, to: Square, flag: Int): Int =
    from.toInt | (to.toInt << 6) | (flag << 12)

  inline def from(m: Int): Square  = Square(m & 63)
  inline def to(m: Int): Square    = Square((m >> 6) & 63)
  inline def flag(m: Int): Int     = m >> 12

  inline def isCapture(m: Int): Boolean = (flag(m) & 4) != 0
  inline def isPromo(m: Int): Boolean   = (flag(m) & 8) != 0
  inline def promoType(m: Int): Int     = (flag(m) & 3) + PType.Knight

  def toUci(m: Int): String =
    def sqStr(i: Int) = s"${('a' + (i & 7)).toChar}${1 + (i >> 3)}"
    val promo = if isPromo(m) then
      promoType(m) match
        case PType.Knight => "n"
        case PType.Bishop => "b"
        case PType.Rook   => "r"
        case _            => "q"
    else ""
    sqStr(from(m).toInt) + sqStr(to(m).toInt) + promo
