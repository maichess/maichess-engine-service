package maichess.engine.chess

// Pseudo-legal move generator. Writes encoded Int moves into a caller-supplied
// array and returns the count. Legality (no king left in check) is checked by
// LegalCheck.isLegal.
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object MoveGen:

  def generate(pos: Position, moves: Array[Int]): Int =
    val us  = pos.sideToMove
    val own = pos.byColor(us); val opp = pos.byColor(us ^ 1); val occ = pos.occupied
    var cnt = genPawns(pos, us, opp, occ, moves, 0, capturesOnly = false)
    cnt = genKnights(pos.pieceBB(us, PType.Knight), own, opp, moves, cnt, capturesOnly = false)
    cnt = genKing(pos, us, own, opp, occ, moves, cnt, capturesOnly = false)
    cnt = genSliders(pos, us, PType.Bishop, own, opp, occ, moves, cnt, capturesOnly = false)
    cnt = genSliders(pos, us, PType.Rook,   own, opp, occ, moves, cnt, capturesOnly = false)
    cnt = genSliders(pos, us, PType.Queen,  own, opp, occ, moves, cnt, capturesOnly = false)
    cnt

  def generateCaptures(pos: Position, moves: Array[Int]): Int =
    val us  = pos.sideToMove
    val own = pos.byColor(us); val opp = pos.byColor(us ^ 1); val occ = pos.occupied
    var cnt = genPawns(pos, us, opp, occ, moves, 0, capturesOnly = true)
    cnt = genKnights(pos.pieceBB(us, PType.Knight), own, opp, moves, cnt, capturesOnly = true)
    cnt = genKing(pos, us, own, opp, occ, moves, cnt, capturesOnly = true)
    cnt = genSliders(pos, us, PType.Bishop, own, opp, occ, moves, cnt, capturesOnly = true)
    cnt = genSliders(pos, us, PType.Rook,   own, opp, occ, moves, cnt, capturesOnly = true)
    cnt = genSliders(pos, us, PType.Queen,  own, opp, occ, moves, cnt, capturesOnly = true)
    cnt

  // Emit captures on opp then quiet moves on empty squares (skipped when capturesOnly).
  private def addSplit(from: Int, atk: Long, own: Long, opp: Long,
                       moves: Array[Int], cnt: Int, capturesOnly: Boolean): Int =
    var caps = atk & opp; var c = cnt
    while BB.nonEmpty(caps) do
      moves(c) = Move.encode(Square(from), BB.lsb(caps), Move.FlagCapture); c += 1
      caps = BB.clearLsb(caps)
    if !capturesOnly then
      var quiets = atk & ~(own | opp)
      while BB.nonEmpty(quiets) do
        moves(c) = Move.encode(Square(from), BB.lsb(quiets), Move.FlagQuiet); c += 1
        quiets = BB.clearLsb(quiets)
    c

  // Emit four promotion moves (N/B/R/Q) for one (from, to) pair.
  private def addPromos(from: Int, to: Int, isCapt: Boolean, moves: Array[Int], cnt: Int): Int =
    val b = if isCapt then Move.FlagNPromoCapt else Move.FlagNPromo
    moves(cnt)   = Move.encode(Square(from), Square(to), b)
    moves(cnt+1) = Move.encode(Square(from), Square(to), b+1)
    moves(cnt+2) = Move.encode(Square(from), Square(to), b+2)
    moves(cnt+3) = Move.encode(Square(from), Square(to), b+3)
    cnt + 4

  private def genKnights(pBB: Long, own: Long, opp: Long,
                         moves: Array[Int], cnt: Int, capturesOnly: Boolean): Int =
    var bb = pBB; var c = cnt
    while BB.nonEmpty(bb) do
      val from = BB.lsb(bb).toInt
      c = addSplit(from, Attacks.knightAttacks(from), own, opp, moves, c, capturesOnly)
      bb = BB.clearLsb(bb)
    c

  private def genKing(pos: Position, us: Int, own: Long, opp: Long, occ: Long,
                      moves: Array[Int], cnt: Int, capturesOnly: Boolean): Int =
    val from = pos.kingSquare(us).toInt
    var c = addSplit(from, Attacks.kingAttacks(from), own, opp, moves, cnt, capturesOnly)
    if !capturesOnly then
      val cr = pos.castlingRights
      if us == Col.White then
        if (cr & Castling.WK) != 0 && (occ & 0x60L) == 0L then
          moves(c) = Move.encode(Sq.E1, Sq.G1, Move.FlagKCastle); c += 1
        if (cr & Castling.WQ) != 0 && (occ & 0xEL) == 0L then
          moves(c) = Move.encode(Sq.E1, Sq.C1, Move.FlagQCastle); c += 1
      else
        if (cr & Castling.BK) != 0 && (occ & 0x6000000000000000L) == 0L then
          moves(c) = Move.encode(Sq.E8, Sq.G8, Move.FlagKCastle); c += 1
        if (cr & Castling.BQ) != 0 && (occ & 0xE00000000000000L) == 0L then
          moves(c) = Move.encode(Sq.E8, Sq.C8, Move.FlagQCastle); c += 1
    c

  private def genSliders(pos: Position, us: Int, ptype: Int, own: Long, opp: Long, occ: Long,
                         moves: Array[Int], cnt: Int, capturesOnly: Boolean): Int =
    var bb = pos.pieceBB(us, ptype); var c = cnt
    while BB.nonEmpty(bb) do
      val from = BB.lsb(bb).toInt
      val atk = ptype match
        case PType.Bishop => Magics.bishopAttacks(from, occ)
        case PType.Rook   => Magics.rookAttacks(from, occ)
        case _            => Magics.bishopAttacks(from, occ) | Magics.rookAttacks(from, occ)
      c = addSplit(from, atk, own, opp, moves, c, capturesOnly)
      bb = BB.clearLsb(bb)
    c

  private def genPawns(pos: Position, us: Int, opp: Long, occ: Long,
                       moves: Array[Int], cnt: Int, capturesOnly: Boolean): Int =
    val pawns = pos.pieceBB(us, PType.Pawn)
    val promos   = pawns & (if us == Col.White then BB.Rank7 else BB.Rank2)
    val nonPromo = pawns ^ promos
    val empty    = ~occ
    // fwd = square offset for one forward step; c1/c2 = capture diagonal offsets
    val (fwd, c1, c2) = if us == Col.White then (8, 9, 7) else (-8, -7, -9)
    var c = cnt

    if !capturesOnly then
      var push = (if us == Col.White then BB.shiftN(nonPromo) else BB.shiftS(nonPromo)) & empty
      while BB.nonEmpty(push) do
        val to = BB.lsb(push).toInt
        moves(c) = Move.encode(Square(to - fwd), Square(to), Move.FlagQuiet); c += 1
        push = BB.clearLsb(push)
      val p1 = (if us == Col.White then BB.shiftN(pawns & BB.Rank2) else BB.shiftS(pawns & BB.Rank7)) & empty
      var push2 = (if us == Col.White then BB.shiftN(p1) else BB.shiftS(p1)) & empty
      while BB.nonEmpty(push2) do
        val to = BB.lsb(push2).toInt
        moves(c) = Move.encode(Square(to - fwd * 2), Square(to), Move.FlagDoublePush); c += 1
        push2 = BB.clearLsb(push2)
      var ppush = (if us == Col.White then BB.shiftN(promos) else BB.shiftS(promos)) & empty
      while BB.nonEmpty(ppush) do
        val to = BB.lsb(ppush).toInt; c = addPromos(to - fwd, to, false, moves, c)
        ppush = BB.clearLsb(ppush)

    var pc1 = (if us == Col.White then BB.shiftNE(promos) else BB.shiftSE(promos)) & opp
    while BB.nonEmpty(pc1) do
      val to = BB.lsb(pc1).toInt; c = addPromos(to - c1, to, true, moves, c)
      pc1 = BB.clearLsb(pc1)
    var pc2 = (if us == Col.White then BB.shiftNW(promos) else BB.shiftSW(promos)) & opp
    while BB.nonEmpty(pc2) do
      val to = BB.lsb(pc2).toInt; c = addPromos(to - c2, to, true, moves, c)
      pc2 = BB.clearLsb(pc2)

    var cap1 = (if us == Col.White then BB.shiftNE(nonPromo) else BB.shiftSE(nonPromo)) & opp
    while BB.nonEmpty(cap1) do
      val to = BB.lsb(cap1).toInt
      moves(c) = Move.encode(Square(to - c1), Square(to), Move.FlagCapture); c += 1
      cap1 = BB.clearLsb(cap1)
    var cap2 = (if us == Col.White then BB.shiftNW(nonPromo) else BB.shiftSW(nonPromo)) & opp
    while BB.nonEmpty(cap2) do
      val to = BB.lsb(cap2).toInt
      moves(c) = Move.encode(Square(to - c2), Square(to), Move.FlagCapture); c += 1
      cap2 = BB.clearLsb(cap2)

    val ep = pos.epSquare
    if ep != Sq.None.toInt then
      var att = Attacks.pawnAttacks(us ^ 1)(ep) & pawns
      while BB.nonEmpty(att) do
        moves(c) = Move.encode(BB.lsb(att), Square(ep), Move.FlagEP); c += 1
        att = BB.clearLsb(att)
    c
