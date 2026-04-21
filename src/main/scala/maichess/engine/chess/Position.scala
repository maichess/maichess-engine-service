package maichess.engine.chess

// ── Position ──────────────────────────────────────────────────────────────────
// Mutable, in-place board representation. All state fits in primitive arrays
// and scalar fields — the GC never touches a Position object during search.
//
// Piece bitboards: `pieces` is indexed by the 4-bit piece code
//   (Pieces.make(color, ptype)). Indices 0–5 = white, 8–13 = black.
//
// Mailbox: `mailbox(sq)` gives the Piece on that square, or Pieces.NoPiece.
// Used for O(1) piece-type lookup during capture handling.
//
// Make/Unmake:
//   makeMove pushes (undoState, capturedPiece, hash) onto parallel Int/Int/Long
//   undo arrays before mutating anything. unmakeMove pops them and reverses
//   every mutation using the *Raw helpers that skip the Zobrist update
//   (the saved hash is restored directly).
@SuppressWarnings(Array("org.wartremover.warts.Var"))
final class Position:
  import Pieces.*
  import BB.*

  // ── Bitboards ─────────────────────────────────────────────────────────────
  // Size 16: indices 0–5 (white), 8–13 (black). Slots 6,7,14,15 unused.
  val pieces: Array[Long]  = new Array[Long](16)
  val byColor: Array[Long] = new Array[Long](2)
  var occupied: Long       = 0L

  // ── Mailbox ───────────────────────────────────────────────────────────────
  val mailbox: Array[Int] = Array.fill(64)(NoPiece.toInt)

  // ── Game state ────────────────────────────────────────────────────────────
  var sideToMove:     Int  = Col.White
  var castlingRights: Int  = Castling.All
  var epSquare:       Int  = Sq.None.toInt
  var halfMoveClock:  Int  = 0
  var fullMoveNumber: Int  = 1
  var hash:           Long = 0L

  // ── Undo stack ────────────────────────────────────────────────────────────
  // undoState packs halfMoveClock, epSquare, and castlingRights into one Int:
  //   bits  0– 3 : castlingRights (4 bits)
  //   bits  4–10 : epSquare + 1 (7 bits; 0 means no ep)
  //   bits 11–17 : halfMoveClock (7 bits; max 100)
  private val undoState:    Array[Int]  = new Array[Int](1024)
  private val undoCapPiece: Array[Int]  = new Array[Int](1024)
  private val undoHash:     Array[Long] = new Array[Long](1024)
  private var undoTop: Int = 0

  // ── Piece placement ───────────────────────────────────────────────────────
  inline def putPiece(piece: Piece, sq: Square): Unit =
    val p  = piece.toInt
    val b  = bit(sq)
    val c  = colorOf(piece)
    pieces(p)  |= b
    byColor(c) |= b
    occupied   |= b
    mailbox(sq.toInt) = p
    hash ^= Zobrist.forPiece(piece, sq)

  inline def removePiece(piece: Piece, sq: Square): Unit =
    val p  = piece.toInt
    val b  = bit(sq)
    val c  = colorOf(piece)
    pieces(p)  &= ~b
    byColor(c) &= ~b
    occupied   &= ~b
    mailbox(sq.toInt) = NoPiece.toInt
    hash ^= Zobrist.forPiece(piece, sq)

  // Combined remove+put with a single pair of XORs per bitboard
  inline def movePiece(piece: Piece, from: Square, to: Square): Unit =
    val p    = piece.toInt
    val bFrom = bit(from)
    val bTo   = bit(to)
    val both  = bFrom | bTo
    val c     = colorOf(piece)
    pieces(p)  ^= both
    byColor(c) ^= both
    occupied   ^= both
    mailbox(from.toInt) = NoPiece.toInt
    mailbox(to.toInt)   = p
    hash ^= Zobrist.forPiece(piece, from) ^ Zobrist.forPiece(piece, to)

  // Raw variants: same bitboard mutations but NO hash update.
  // Used during unmakeMove where the saved hash is restored directly.
  private inline def putPieceRaw(piece: Piece, sq: Square): Unit =
    val p  = piece.toInt
    val b  = bit(sq)
    val c  = colorOf(piece)
    pieces(p)  |= b
    byColor(c) |= b
    occupied   |= b
    mailbox(sq.toInt) = p

  private inline def removePieceRaw(piece: Piece, sq: Square): Unit =
    val p  = piece.toInt
    val b  = bit(sq)
    val c  = colorOf(piece)
    pieces(p)  &= ~b
    byColor(c) &= ~b
    occupied   &= ~b
    mailbox(sq.toInt) = NoPiece.toInt

  private inline def movePieceRaw(piece: Piece, from: Square, to: Square): Unit =
    val p    = piece.toInt
    val both  = bit(from) | bit(to)
    val c     = colorOf(piece)
    pieces(p)  ^= both
    byColor(c) ^= both
    occupied   ^= both
    mailbox(from.toInt) = NoPiece.toInt
    mailbox(to.toInt)   = p

  // ── Hash helpers ──────────────────────────────────────────────────────────
  private inline def clearEpHash(): Unit =
    if epSquare != Sq.None.toInt then
      hash ^= Zobrist.forEpFile(Sq.file(Square(epSquare)))

  private inline def applyEpHash(sq: Int): Unit =
    if sq != Sq.None.toInt then
      hash ^= Zobrist.forEpFile(Sq.file(Square(sq)))

  // ── makeMove ──────────────────────────────────────────────────────────────
  def makeMove(move: Int): Unit =
    undoState(undoTop)    = halfMoveClock | ((epSquare + 1) << 11) | (castlingRights << 18)
    undoHash(undoTop)     = hash
    val from  = Move.from(move)
    val to    = Move.to(move)
    val fl    = Move.flag(move)
    val mover = Piece(mailbox(from.toInt))

    hash ^= Zobrist.forCastling(castlingRights)
    clearEpHash()
    hash ^= Zobrist.sideToMove

    val capPiece: Int =
      if fl == Move.FlagEP then
        val capSq = if sideToMove == Col.White then Square(to.toInt - 8) else Square(to.toInt + 8)
        val cp    = mailbox(capSq.toInt)
        removePiece(Piece(cp), capSq)
        cp
      else if (fl & 4) != 0 then
        val cp = mailbox(to.toInt)
        removePiece(Piece(cp), to)
        cp
      else NoPiece.toInt
    undoCapPiece(undoTop) = capPiece
    undoTop += 1

    if (fl & 8) != 0 then
      removePiece(mover, from)
      val promoType = (fl & 3) + PType.Knight
      putPiece(Pieces.make(sideToMove, promoType), to)
    else
      movePiece(mover, from, to)

    if fl == Move.FlagKCastle then
      if sideToMove == Col.White then movePiece(Pieces.WRook, Sq.H1, Sq.F1)
      else                            movePiece(Pieces.BRook, Sq.H8, Sq.F8)
    else if fl == Move.FlagQCastle then
      if sideToMove == Col.White then movePiece(Pieces.WRook, Sq.A1, Sq.D1)
      else                            movePiece(Pieces.BRook, Sq.A8, Sq.D8)

    epSquare =
      if fl == Move.FlagDoublePush then
        if sideToMove == Col.White then to.toInt - 8 else to.toInt + 8
      else Sq.None.toInt

    castlingRights &= Attacks.castlingMask(from.toInt) & Attacks.castlingMask(to.toInt)

    halfMoveClock =
      if Move.isCapture(move) || Pieces.typeOf(mover) == PType.Pawn then 0
      else halfMoveClock + 1

    if sideToMove == Col.Black then fullMoveNumber += 1

    hash ^= Zobrist.forCastling(castlingRights)
    applyEpHash(epSquare)

    sideToMove ^= 1

  // ── unmakeMove ────────────────────────────────────────────────────────────
  def unmakeMove(move: Int): Unit =
    undoTop -= 1
    sideToMove ^= 1
    if sideToMove == Col.Black then fullMoveNumber -= 1

    val from = Move.from(move)
    val to   = Move.to(move)
    val fl   = Move.flag(move)

    val state = undoState(undoTop)
    castlingRights = state >> 18
    epSquare       = ((state >> 11) & 127) - 1
    halfMoveClock  = state & 0x7FF
    hash           = undoHash(undoTop)

    if (fl & 8) != 0 then
      val promoType = (fl & 3) + PType.Knight
      removePieceRaw(Pieces.make(sideToMove, promoType), to)
      putPieceRaw(Pieces.make(sideToMove, PType.Pawn), from)
    else
      movePieceRaw(Piece(mailbox(to.toInt)), to, from)

    if fl == Move.FlagKCastle then
      if sideToMove == Col.White then movePieceRaw(Pieces.WRook, Sq.F1, Sq.H1)
      else                            movePieceRaw(Pieces.BRook, Sq.F8, Sq.H8)
    else if fl == Move.FlagQCastle then
      if sideToMove == Col.White then movePieceRaw(Pieces.WRook, Sq.D1, Sq.A1)
      else                            movePieceRaw(Pieces.BRook, Sq.D8, Sq.A8)

    val capPiece = undoCapPiece(undoTop)
    if capPiece != NoPiece.toInt then
      if fl == Move.FlagEP then
        val capSq = if sideToMove == Col.White then Square(to.toInt - 8) else Square(to.toInt + 8)
        putPieceRaw(Piece(capPiece), capSq)
      else
        putPieceRaw(Piece(capPiece), to)

  // ── Computed bitboard helpers ─────────────────────────────────────────────
  inline def pieceBB(color: Int, ptype: Int): Long = pieces(Pieces.make(color, ptype).toInt)

  inline def kingSquare(color: Int): Square =
    BB.lsb(pieceBB(color, PType.King))

  def recomputeHash(): Unit =
    hash = 0L
    var sq = 0
    while sq < 64 do
      val p = mailbox(sq)
      if p != NoPiece.toInt then
        hash ^= Zobrist.forPiece(Piece(p), Square(sq))
      sq += 1
    hash ^= Zobrist.forCastling(castlingRights)
    if epSquare != Sq.None.toInt then
      hash ^= Zobrist.forEpFile(Sq.file(Square(epSquare)))
    if sideToMove == Col.Black then
      hash ^= Zobrist.sideToMove

// ── Position companion: FEN parser ───────────────────────────────────────────
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
object Position:
  def fromFen(fen: String): Either[String, Position] =
    val parts = fen.split(' ')
    if parts.length < 6 then Left(s"Invalid FEN: expected 6 fields, got ${parts.length}")
    else
      val pos   = new Position()
      val ranks = parts(0).split('/')
      if ranks.length != 8 then return Left(s"Invalid FEN: expected 8 ranks, got ${ranks.length}")
      for (rankStr, rankFromTop) <- ranks.zipWithIndex do
        val r = 7 - rankFromTop; var f = 0
        for c <- rankStr do
          if c.isDigit then f += c.asDigit
          else
            val (color, ptype) = charToPiece(c)
            pos.putPiece(Pieces.make(color, ptype), Square((r << 3) | f))
            f += 1
      pos.sideToMove = if parts(1) == "w" then Col.White else Col.Black
      val cr = parts(2)
      pos.castlingRights =
        (if cr.contains('K') then Castling.WK else 0) |
        (if cr.contains('Q') then Castling.WQ else 0) |
        (if cr.contains('k') then Castling.BK else 0) |
        (if cr.contains('q') then Castling.BQ else 0)
      pos.epSquare       = if parts(3) == "-" then Sq.None.toInt else algToSq(parts(3))
      pos.halfMoveClock  = parts(4).toIntOption.getOrElse(0)
      pos.fullMoveNumber = parts(5).toIntOption.getOrElse(1)
      pos.recomputeHash()
      Right(pos)

  private def charToPiece(c: Char): (Int, Int) =
    val color = if c.isUpper then Col.White else Col.Black
    val ptype = c.toLower match
      case 'p' => PType.Pawn;   case 'n' => PType.Knight; case 'b' => PType.Bishop
      case 'r' => PType.Rook;   case 'q' => PType.Queen;  case _   => PType.King
    (color, ptype)

  private def algToSq(s: String): Int = ((s(1) - '1') << 3) | (s(0) - 'a')
