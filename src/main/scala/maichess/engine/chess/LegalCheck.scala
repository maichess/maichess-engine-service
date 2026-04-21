package maichess.engine.chess

@SuppressWarnings(Array("org.wartremover.warts.Return"))
object LegalCheck:

  // True if square sq is attacked by any piece of byColor in pos.
  // Uses the symmetric property: a knight/king on sq sees the same squares a
  // knight/king on those squares would see. For pawns, look up byColor's
  // opponent attack table (a white pawn on sq attacks the same diagonal that
  // a black pawn would have to stand on to attack sq from).
  def isAttacked(pos: Position, sq: Int, byColor: Int): Boolean =
    val occ = pos.occupied
    val pawns   = pos.pieceBB(byColor, PType.Pawn)
    val knights = pos.pieceBB(byColor, PType.Knight)
    val kings   = pos.pieceBB(byColor, PType.King)
    val bq = pos.pieceBB(byColor, PType.Bishop) | pos.pieceBB(byColor, PType.Queen)
    val rq = pos.pieceBB(byColor, PType.Rook)   | pos.pieceBB(byColor, PType.Queen)
    (Attacks.pawnAttacks(byColor ^ 1)(sq) & pawns)  != 0L ||
    (Attacks.knightAttacks(sq)            & knights) != 0L ||
    (Attacks.kingAttacks(sq)              & kings)   != 0L ||
    (Magics.bishopAttacks(sq, occ)        & bq)      != 0L ||
    (Magics.rookAttacks(sq, occ)          & rq)      != 0L

  def isInCheck(pos: Position, color: Int): Boolean =
    isAttacked(pos, pos.kingSquare(color).toInt, color ^ 1)

  // Make the move, test legality, then unmake. Also rejects castling through check
  // by verifying the king's starting square and transit square are not attacked.
  def isLegal(pos: Position, move: Int): Boolean =
    val fl = Move.flag(move)
    if fl == Move.FlagKCastle || fl == Move.FlagQCastle then
      val us = pos.sideToMove
      val kSq = pos.kingSquare(us).toInt
      if isAttacked(pos, kSq, us ^ 1) then return false
      val transit = if fl == Move.FlagKCastle then kSq + 1 else kSq - 1
      if isAttacked(pos, transit, us ^ 1) then return false
    pos.makeMove(move)
    val legal = !isInCheck(pos, pos.sideToMove ^ 1)
    pos.unmakeMove(move)
    legal
