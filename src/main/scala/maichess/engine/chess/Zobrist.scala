package maichess.engine.chess

// ── Zobrist hashing tables ────────────────────────────────────────────────────
// Pre-computed at object initialisation (once, at JVM startup).
// During search the Position class XORs individual entries — each makeMove call
// touches at most ~6 XOR operations, all on Long primitives (no allocation).
//
// PRNG: xorshift64 seeded with the fractional bits of the golden ratio.
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object Zobrist:

  // 16 piece indices × 64 squares.
  // Valid piece indices are 0–5 (white) and 8–13 (black) — see Pieces.make.
  val pieceSquare: Array[Long] = new Array[Long](16 * 64)

  // 16 castling-rights combinations (4-bit mask 0–15)
  val castling: Array[Long] = new Array[Long](16)

  // En-passant file (0–7). Index 8 = no ep square.
  val epFile: Array[Long] = new Array[Long](9)

  // XOR into hash whenever it is Black's turn to move
  var sideToMove: Long = 0L

  private var rngState: Long = 0x9E3779B97F4A7C15L   // golden-ratio fractional bits

  private def nextRandom(): Long =
    var x = rngState
    x ^= x << 13
    x ^= x >>> 7
    x ^= x << 17
    rngState = x
    x

  locally:
    var i = 0
    while i < pieceSquare.length do
      pieceSquare(i) = nextRandom()
      i += 1
    i = 0
    while i < castling.length do
      castling(i) = nextRandom()
      i += 1
    i = 0
    while i < epFile.length do
      epFile(i) = nextRandom()
      i += 1
    sideToMove = nextRandom()

  inline def forPiece(piece: Piece, sq: Square): Long =
    pieceSquare((piece.toInt << 6) | sq.toInt)

  inline def forEpFile(file: Int): Long =
    epFile(file)

  inline def forCastling(rights: Int): Long =
    castling(rights)
