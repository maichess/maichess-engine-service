package maichess.engine.chess

import java.nio.ByteBuffer

// ── Polyglot opening book ─────────────────────────────────────────────────────
// Reads a Polyglot `.bin` opening book from the classpath resource
// `/books/performance.bin`. Returns Some(uciMove) for positions present in the
// book, and None otherwise. Gracefully handles a missing resource.
//
// Polyglot entry format (16 bytes, big-endian):
//   bytes 0-7:  Zobrist key
//   bytes 8-9:  move
//                 bits  0- 5: to-square   (file + 8*rank, a1=0..h8=63)
//                 bits  6-11: from-square
//                 bits 12-14: promotion piece (0=none,1=N,2=B,3=R,4=Q)
//                 bit     15: unused
//   bytes 10-11: weight
//   bytes 12-15: learn data (ignored)
//
// Castling encoding caveat: Polyglot encodes O-O as king-to-rook square
// (e1h1, e1a1, e8h8, e8a8). The engine emits castling as e1g1 / e1c1 / e8g8 /
// e8c8. `rewriteCastling` translates between the two before matching against
// the legal move list.
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
object OpeningBook:

  private val ResourcePath = "/books/performance.bin"
  private val MaxFullMove  = 30
  private val EntrySize    = 16

  // Loaded once on first probe. None if the resource is absent.
  private lazy val bookBuffer: Option[ByteBuffer] = loadBook()

  private def loadBook(): Option[ByteBuffer] =
    Option(getClass.getResourceAsStream(ResourcePath)).map { is =>
      try
        val bytes = is.readAllBytes()
        ByteBuffer.wrap(bytes).asReadOnlyBuffer()
      finally is.close()
    }

  def probe(pos: Position): Option[String] =
    if pos.fullMoveNumber > MaxFullMove then None
    else bookBuffer.flatMap(buf => probeBuffer(pos, buf))

  // Test hook: probe an arbitrary buffer directly (skips the fullmove guard
  // so tests can stage a hand-built one-entry buffer).
  def probeBuffer(pos: Position, buf: ByteBuffer): Option[String] =
    val key = PolyglotZobrist.key(pos)
    val range = findKeyRange(buf, key)
    val lo = (range >> 32).toInt
    val hi = range.toInt
    if lo < 0 then None
    else
      // Strongest play: pick the single highest-weight book move deterministically
      // (max strength / consistency) rather than sampling proportionally to weight.
      val best = maxWeightIndex(buf, lo, hi)
      if best < 0 then None
      else decodeAndMatch(pos, buf.getShort(best * EntrySize + 8) & 0xFFFF)

  // Returns the matching [lo, hi) range packed into a Long: (lo << 32) | hi.
  // Returns (-1, -1) packed if not found. Avoids an allocation for the common
  // search path.
  private def findKeyRange(buf: ByteBuffer, key: Long): Long =
    val count = buf.capacity() / EntrySize
    var lo = 0
    var hi = count
    while lo < hi do
      val mid = (lo + hi) >>> 1
      val k   = buf.getLong(mid * EntrySize)
      if k < key then lo = mid + 1
      else if k > key then hi = mid
      else
        var s = mid
        while s > 0 && buf.getLong((s - 1) * EntrySize) == key do s -= 1
        var e = mid + 1
        while e < count && buf.getLong(e * EntrySize) == key do e += 1
        return (s.toLong << 32) | (e.toLong & 0xFFFFFFFFL)
    (-1L << 32) | (-1L & 0xFFFFFFFFL)

  // Index of the highest-weight entry in [lo, hi); -1 if every weight is zero.
  // Ties resolve to the first (lowest-index) entry, keeping selection deterministic.
  private def maxWeightIndex(buf: ByteBuffer, lo: Int, hi: Int): Int =
    var bestIdx = -1
    var bestW   = 0
    var i = lo
    while i < hi do
      val w = buf.getShort(i * EntrySize + 10) & 0xFFFF
      if w > bestW then
        bestW = w; bestIdx = i
      i += 1
    bestIdx

  private def decodeAndMatch(pos: Position, polyMove: Int): Option[String] =
    val promo = (polyMove >> 12) & 7
    val toRaw = polyMove & 63
    val from  = (polyMove >> 6) & 63
    val to    = rewriteCastling(pos, from, toRaw)
    val moves = new Array[Int](256)
    val cnt   = MoveGen.generate(pos, moves)
    var i = 0
    while i < cnt do
      val mv = moves(i)
      if Move.from(mv).toInt == from && Move.to(mv).toInt == to && matchesPromo(mv, promo) then
        if LegalCheck.isLegal(pos, mv) then return Some(Move.toUci(mv))
      i += 1
    None

  private def rewriteCastling(pos: Position, from: Int, to: Int): Int =
    val p = pos.mailbox(from)
    if p == Pieces.NoPiece.toInt then to
    else if Pieces.typeOf(Piece(p)) != PType.King then to
    else (from, to) match
      case (4, 7)   => 6   // white O-O:   e1h1 → e1g1
      case (4, 0)   => 2   // white O-O-O: e1a1 → e1c1
      case (60, 63) => 62  // black O-O:   e8h8 → e8g8
      case (60, 56) => 58  // black O-O-O: e8a8 → e8c8
      case _        => to

  private def matchesPromo(mv: Int, polyPromo: Int): Boolean =
    if polyPromo == 0 then !Move.isPromo(mv)
    else Move.isPromo(mv) && Move.promoType(mv) == polyPromo
