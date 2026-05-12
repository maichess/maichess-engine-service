package maichess.engine.chess.basic

object BasicPiece:
  val Empty   = 0
  val WPawn   = 1; val WKnight = 2; val WBishop = 3
  val WRook   = 4; val WQueen  = 5; val WKing   = 6
  val BPawn   = -1; val BKnight = -2; val BBishop = -3
  val BRook   = -4; val BQueen  = -5; val BKing   = -6

// Snapshot of all mutable fields needed to undo a move.
final case class BasicState(castling: Int, epSquare: Int, halfmove: Int, fullmove: Int, captured: Int)

@SuppressWarnings(Array("org.wartremover.warts.Var"))
final class BasicPosition:
  import BasicPiece.*

  val board      = new Array[Int](64)
  var sideToMove = 1    // 1=White, -1=Black
  var castling   = 0    // bits: 1=WK, 2=WQ, 4=BK, 8=BQ
  var epSquare   = -1   // target square for en-passant, -1 if none
  var halfmove   = 0
  var fullmove   = 1

  // Makes the move and returns the saved state needed for unmakeMove.
  def makeMove(mv: BasicMove): BasicState =
    import BasicFlag.*
    val mover = board(mv.from)
    val captured =
      if mv.flag == EP then
        val capSq = if sideToMove == 1 then mv.to - 8 else mv.to + 8
        val p = board(capSq)
        board(capSq) = Empty
        p
      else
        board(mv.to)
    val saved = BasicState(castling, epSquare, halfmove, fullmove, captured)

    board(mv.from) = Empty
    board(mv.to)   = if mv.promo != 0 then mv.promo * sideToMove else mover

    if mv.flag == Castle then
      mv.to match
        case 6  => board(5) = WRook;  board(7)  = Empty
        case 2  => board(3) = WRook;  board(0)  = Empty
        case 62 => board(61) = BRook; board(63) = Empty
        case 58 => board(59) = BRook; board(56) = Empty
        case _  => ()

    epSquare = if mv.flag == DoublePush then (mv.from + mv.to) / 2 else -1

    if mv.from == 4  || mv.to == 4  then castling &= ~3
    if mv.from == 60 || mv.to == 60 then castling &= ~12
    if mv.from == 7  || mv.to == 7  then castling &= ~1
    if mv.from == 0  || mv.to == 0  then castling &= ~2
    if mv.from == 63 || mv.to == 63 then castling &= ~4
    if mv.from == 56 || mv.to == 56 then castling &= ~8

    halfmove =
      if mv.flag == Capture || mv.flag == EP || Math.abs(mover) == 1 then 0
      else halfmove + 1
    if sideToMove == -1 then fullmove += 1
    sideToMove = -sideToMove
    saved

  def unmakeMove(mv: BasicMove, saved: BasicState): Unit =
    import BasicFlag.*
    sideToMove = -sideToMove
    castling   = saved.castling
    epSquare   = saved.epSquare
    halfmove   = saved.halfmove
    fullmove   = saved.fullmove

    val mover = board(mv.to)
    board(mv.from) = if mv.promo != 0 then sideToMove * WPawn else mover
    board(mv.to)   = if mv.flag != EP then saved.captured else Empty

    if mv.flag == Castle then
      mv.to match
        case 6  => board(7)  = WRook; board(5)  = Empty
        case 2  => board(0)  = WRook; board(3)  = Empty
        case 62 => board(63) = BRook; board(61) = Empty
        case 58 => board(56) = BRook; board(59) = Empty
        case _  => ()

    if mv.flag == EP then
      val capSq = if sideToMove == 1 then mv.to - 8 else mv.to + 8
      board(capSq) = saved.captured

@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
object BasicPosition:
  def fromFen(fen: String): Either[String, BasicPosition] =
    val parts = fen.split(' ')
    if parts.length < 6 then return Left(s"Invalid FEN: expected 6 fields, got ${parts.length}")
    val pos   = new BasicPosition()
    val ranks = parts(0).split('/')
    if ranks.length != 8 then return Left(s"Invalid FEN: expected 8 ranks, got ${ranks.length}")
    for (rankStr, rankFromTop) <- ranks.zipWithIndex do
      val r = 7 - rankFromTop
      var f = 0
      for c <- rankStr do
        if c.isDigit then f += c.asDigit
        else
          pos.board(r * 8 + f) = charToPiece(c)
          f += 1
    pos.sideToMove = if parts(1) == "w" then 1 else -1
    val cr = parts(2)
    pos.castling =
      (if cr.contains('K') then 1 else 0) |
      (if cr.contains('Q') then 2 else 0) |
      (if cr.contains('k') then 4 else 0) |
      (if cr.contains('q') then 8 else 0)
    pos.epSquare = if parts(3) == "-" then -1 else algToSq(parts(3))
    pos.halfmove = parts(4).toIntOption.getOrElse(0)
    pos.fullmove = parts(5).toIntOption.getOrElse(1)
    Right(pos)

  private def charToPiece(c: Char): Int =
    import BasicPiece.*
    c match
      case 'P' => WPawn;   case 'N' => WKnight; case 'B' => WBishop
      case 'R' => WRook;   case 'Q' => WQueen;  case 'K' => WKing
      case 'p' => BPawn;   case 'n' => BKnight; case 'b' => BBishop
      case 'r' => BRook;   case 'q' => BQueen;  case 'k' => BKing
      case _   => Empty

  private def algToSq(s: String): Int = (s(1) - '1') * 8 + (s(0) - 'a')
