package maichess.engine.chess

// Static evaluation, tier 4. Extends Eval with king safety, pawn structure
// (doubled / isolated / passed), full piece mobility (rooks and queens),
// and rook placement bonuses (open files, 7th rank, connected rooks).
// All new terms are tapered using the 0–24 phase scale so the evaluation
// transitions smoothly from opening to endgame.
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object EvalV2:

  private val Mat = Array(100, 320, 330, 500, 900, 0)   // Pawn..King material
  private val Ph  = Array(0, 1, 1, 2, 4, 0)             // phase weights per piece type

  private val PawnPst = Array[Int](
     0,  0,  0,  0,  0,  0,  0,  0,
     5, 10, 10,-20,-20, 10, 10,  5,
     5, -5,-10,  0,  0,-10, -5,  5,
     0,  0,  0, 20, 20,  0,  0,  0,
     5,  5, 10, 25, 25, 10,  5,  5,
    10, 10, 20, 30, 30, 20, 10, 10,
    50, 50, 50, 50, 50, 50, 50, 50,
     0,  0,  0,  0,  0,  0,  0,  0)

  private val KnightPst = Array[Int](
    -50,-40,-30,-30,-30,-30,-40,-50,
    -40,-20,  0,  5,  5,  0,-20,-40,
    -30,  5, 10, 15, 15, 10,  5,-30,
    -30,  0, 15, 20, 20, 15,  0,-30,
    -30,  5, 15, 20, 20, 15,  5,-30,
    -30,  0, 10, 15, 15, 10,  0,-30,
    -40,-20,  0,  5,  5,  0,-20,-40,
    -50,-40,-30,-30,-30,-30,-40,-50)

  private val BishopPst = Array[Int](
    -20,-10,-10,-10,-10,-10,-10,-20,
    -10,  5,  0,  0,  0,  0,  5,-10,
    -10, 10, 10, 10, 10, 10, 10,-10,
    -10,  0, 10, 10, 10, 10,  0,-10,
    -10,  5,  5, 10, 10,  5,  5,-10,
    -10,  0,  5, 10, 10,  5,  0,-10,
    -10,  0,  0,  0,  0,  0,  0,-10,
    -20,-10,-10,-10,-10,-10,-10,-20)

  private val RookPst = Array[Int](
     0,  0,  0,  5,  5,  0,  0,  0,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
     5, 10, 10, 10, 10, 10, 10,  5,
     0,  0,  0,  0,  0,  0,  0,  0)

  private val QueenPst = Array[Int](
    -20,-10,-10, -5, -5,-10,-10,-20,
    -10,  0,  5,  0,  0,  0,  0,-10,
    -10,  5,  5,  5,  5,  5,  0,-10,
     -5,  0,  5,  5,  5,  5,  0, -5,
      0,  0,  5,  5,  5,  5,  0, -5,
    -10,  5,  5,  5,  5,  5,  0,-10,
    -10,  0,  5,  0,  0,  0,  0,-10,
    -20,-10,-10, -5, -5,-10,-10,-20)

  private val KingMid = Array[Int](
     20, 30, 10,  0,  0, 10, 30, 20,
     20, 20,  0,  0,  0,  0, 20, 20,
    -10,-20,-20,-20,-20,-20,-20,-10,
    -20,-30,-30,-40,-40,-30,-30,-20,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30)

  private val KingEnd = Array[Int](
    -50,-30,-30,-30,-30,-30,-30,-50,
    -30,-30,  0,  0,  0,  0,-30,-30,
    -30,-10, 20, 30, 30, 20,-10,-30,
    -30,-10, 30, 40, 40, 30,-10,-30,
    -30,-10, 30, 40, 40, 30,-10,-30,
    -30,-10, 20, 30, 30, 20,-10,-30,
    -30,-20,-10,  0,  0,-10,-20,-30,
    -50,-40,-30,-20,-20,-30,-40,-50)

  private val Pst = Array(PawnPst, KnightPst, BishopPst, RookPst, QueenPst)

  // ── New evaluation tables / constants ─────────────────────────────────────

  private val PassedRankBonus = Array(0, 10, 20, 35, 55, 80, 120, 0)   // rank 1..8
  private val KingZoneThreat  = Array(0, 2, 3, 3, 5, 10, 0)            // per piece type (Pawn..Queen, King unused)

  private val DoubledPenalty   = 20
  private val IsolatedPenalty  = 15
  private val BishopPairBonus  = 30
  private val Tempo            = 10
  private val KnightOutpostBonus = 22
  private val BishopOutpostBonus = 14
  // Penalty for an own non-pawn piece attacked by an enemy pawn, indexed by PType
  // (Pawn, Knight, Bishop, Rook, Queen, King). A pawn forking a queen or rook is a
  // serious threat / tempo loss; minors less so; pawn/king entries unused.
  private val PawnThreatPenalty = Array(0, 20, 20, 35, 50, 0)
  private val OpenFileRookBonus     = 20
  private val SemiOpenFileRookBonus = 10
  private val RookOn7thBonus        = 25
  private val ConnectedRooksBonus   = 10
  private val MissingShieldPawnPenalty = 10
  private val KingFileSemiOpenPenalty  = 25
  private val KingFileFullyOpenPenalty = 40

  // passedMask(color)(sq): squares on files f-1..f+1 strictly ahead of sq from
  // color's perspective. If `pawns(opp) & passedMask(c)(sq) == 0` then the pawn
  // on sq is passed.
  private val passedMask: Array[Array[Long]] = Array.ofDim(2, 64)

  // pawnSpanMask(color)(sq): the *adjacent* files (f-1, f+1) strictly ahead of sq
  // from color's perspective. If `enemyPawns & pawnSpanMask(c)(sq) == 0` no enemy
  // pawn can ever advance to attack sq — the safety condition for an outpost.
  private val pawnSpanMask: Array[Array[Long]] = Array.ofDim(2, 64)

  // fileMask(f) — eight bits for file f
  private val fileMask: Array[Long] = new Array(8)

  locally:
    var f = 0
    while f < 8 do
      var m = 0L
      var r = 0
      while r < 8 do
        m |= 1L << ((r << 3) | f)
        r += 1
      fileMask(f) = m
      f += 1

    var sq = 0
    while sq < 64 do
      val file = sq & 7
      val rank = sq >> 3
      // White: squares strictly above rank, on files file-1..file+1
      var mw = 0L; var mb = 0L
      var rr = rank + 1
      while rr < 8 do
        var ff = file - 1
        while ff <= file + 1 do
          if ff >= 0 && ff < 8 then mw |= 1L << ((rr << 3) | ff)
          ff += 1
        rr += 1
      // Black: squares strictly below rank, on files file-1..file+1
      var rb = rank - 1
      while rb >= 0 do
        var ff = file - 1
        while ff <= file + 1 do
          if ff >= 0 && ff < 8 then mb |= 1L << ((rb << 3) | ff)
          ff += 1
        rb -= 1
      passedMask(Col.White)(sq) = mw
      passedMask(Col.Black)(sq) = mb
      // Same spans but adjacent files only (exclude the piece's own file).
      val ownFile = fileMask(file)
      pawnSpanMask(Col.White)(sq) = mw & ~ownFile
      pawnSpanMask(Col.Black)(sq) = mb & ~ownFile
      sq += 1

  // ── Public API ────────────────────────────────────────────────────────────

  def evaluate(pos: Position): Int =
    val occ = pos.occupied
    var mat = 0; var phase = 0; var mobOp = 0; var mobEg = 0; var opK = 0; var egK = 0
    val wPawns = pos.pieceBB(Col.White, PType.Pawn)
    val bPawns = pos.pieceBB(Col.Black, PType.Pawn)
    var c = 0
    while c < 2 do
      val s = if c == pos.sideToMove then 1 else -1
      val own = pos.byColor(c)
      var pt = 0
      while pt < 5 do
        var bb = pos.pieceBB(c, pt)
        while BB.nonEmpty(bb) do
          val sq  = BB.lsb(bb).toInt
          val idx = if c == Col.White then sq else sq ^ 56
          mat   += s * (Mat(pt) + Pst(pt)(idx))
          phase += Ph(pt)
          if pt == PType.Knight then
            val cnt = BB.popcount(Attacks.knightAttacks(sq) & ~own)
            mobOp += s * cnt; mobEg += s * cnt
          else if pt == PType.Bishop then
            val cnt = BB.popcount(Magics.bishopAttacks(sq, occ) & ~own)
            mobOp += s * cnt; mobEg += s * cnt
          else if pt == PType.Rook then
            val cnt = BB.popcount(Magics.rookAttacks(sq, occ) & ~own)
            mobOp += s * (3 * cnt)
            mobEg += s * (5 * cnt)
          else if pt == PType.Queen then
            val cnt = BB.popcount((Magics.rookAttacks(sq, occ) | Magics.bishopAttacks(sq, occ)) & ~own)
            mobOp += s * cnt
            mobEg += s * cnt
          bb = BB.clearLsb(bb)
        pt += 1
      val kSq  = pos.kingSquare(c).toInt
      val kIdx = if c == Col.White then kSq else kSq ^ 56
      opK += s * KingMid(kIdx)
      egK += s * KingEnd(kIdx)
      c += 1

    val p = Math.min(phase, 24)

    // ── Pawn structure ────────────────────────────────────────────────────
    val pawnStruct = pawnStructureScore(pos, wPawns, bPawns, p)

    // ── Rook bonuses ──────────────────────────────────────────────────────
    val rookScore = rookBonuses(pos, wPawns, bPawns, p)

    // ── King safety ───────────────────────────────────────────────────────
    val kingSafety = kingSafetyScore(pos, wPawns, bPawns, occ, p)

    // ── Bishop pair ───────────────────────────────────────────────────────
    val bishopPair = bishopPairScore(pos)

    // ── Outposts (knights/bishops on pawn-protected, pawn-safe squares) ─────
    val outpost = outpostScore(pos, wPawns, bPawns, p)

    // ── Threats: own pieces attacked by enemy pawns ────────────────────────
    val threat = threatScore(pos)

    val mobile = (mobOp * p + mobEg * (24 - p)) / 24

    // Tempo: a small bonus for having the move, always from the side-to-move's view.
    mat + (opK * p + egK * (24 - p)) / 24 + mobile + pawnStruct + rookScore + kingSafety +
      bishopPair + outpost + threat + Tempo

  // Two or more bishops are worth a small bonus (open diagonals, complementary
  // colour coverage). White-positive raw, then signed to the side to move.
  private def bishopPairScore(pos: Position): Int =
    var raw = 0
    if BB.popcount(pos.pieceBB(Col.White, PType.Bishop)) >= 2 then raw += BishopPairBonus
    if BB.popcount(pos.pieceBB(Col.Black, PType.Bishop)) >= 2 then raw -= BishopPairBonus
    if pos.sideToMove == Col.White then raw else -raw

  // ── Outposts ──────────────────────────────────────────────────────────────
  // A knight or bishop on the opponent's half (relative ranks 4–6), defended by an
  // own pawn and unreachable by any enemy pawn, is a durable strong square.
  private def outpostScore(pos: Position, wPawns: Long, bPawns: Long, phase: Int): Int =
    val raw = sideOutposts(pos, Col.White, wPawns, bPawns) -
              sideOutposts(pos, Col.Black, bPawns, wPawns)
    val tapered = raw * (phase + 8) / 24
    if pos.sideToMove == Col.White then tapered else -tapered

  private def sideOutposts(pos: Position, c: Int, ownPawns: Long, enemyPawns: Long): Int =
    var raw = 0
    var nb = pos.pieceBB(c, PType.Knight)
    while BB.nonEmpty(nb) do
      if isOutpost(c, BB.lsb(nb).toInt, ownPawns, enemyPawns) then raw += KnightOutpostBonus
      nb = BB.clearLsb(nb)
    var bb = pos.pieceBB(c, PType.Bishop)
    while BB.nonEmpty(bb) do
      if isOutpost(c, BB.lsb(bb).toInt, ownPawns, enemyPawns) then raw += BishopOutpostBonus
      bb = BB.clearLsb(bb)
    raw

  private def isOutpost(c: Int, sq: Int, ownPawns: Long, enemyPawns: Long): Boolean =
    val relRank = if c == Col.White then sq >> 3 else 7 - (sq >> 3)
    if relRank < 3 || relRank > 5 then false
    else
      val defended = (ownPawns & Attacks.pawnAttacks(c ^ 1)(sq)) != 0L
      val safe     = (enemyPawns & pawnSpanMask(c)(sq)) == 0L
      defended && safe

  // ── Threats: a non-pawn piece attacked by an enemy pawn ────────────────────
  private def threatScore(pos: Position): Int =
    val wpa = pawnAttacksBB(pos, Col.White)
    val bpa = pawnAttacksBB(pos, Col.Black)
    val raw = attackedValue(pos, Col.Black, wpa) - attackedValue(pos, Col.White, bpa)
    if pos.sideToMove == Col.White then raw else -raw

  private def attackedValue(pos: Position, c: Int, enemyPawnAttacks: Long): Int =
    var sum = 0
    var pt = PType.Knight
    while pt <= PType.Queen do
      sum += BB.popcount(pos.pieceBB(c, pt) & enemyPawnAttacks) * PawnThreatPenalty(pt)
      pt += 1
    sum

  private def pawnAttacksBB(pos: Position, c: Int): Long =
    var atk = 0L
    var bb = pos.pieceBB(c, PType.Pawn)
    while BB.nonEmpty(bb) do
      atk |= Attacks.pawnAttacks(c)(BB.lsb(bb).toInt)
      bb = BB.clearLsb(bb)
    atk

  // ── Pawn structure: doubled, isolated, passed ─────────────────────────────

  private def pawnStructureScore(pos: Position, wPawns: Long, bPawns: Long, phase: Int): Int =
    val sign = if pos.sideToMove == Col.White then 1 else -1
    var score = 0
    // Doubled and isolated
    var f = 0
    while f < 8 do
      val wOnFile = BB.popcount(wPawns & fileMask(f))
      val bOnFile = BB.popcount(bPawns & fileMask(f))
      if wOnFile > 1 then score -= DoubledPenalty * (wOnFile - 1)
      if bOnFile > 1 then score += DoubledPenalty * (bOnFile - 1)
      val adjW =
        (if f > 0 then wPawns & fileMask(f - 1) else 0L) |
        (if f < 7 then wPawns & fileMask(f + 1) else 0L)
      val adjB =
        (if f > 0 then bPawns & fileMask(f - 1) else 0L) |
        (if f < 7 then bPawns & fileMask(f + 1) else 0L)
      if wOnFile > 0 && adjW == 0L then score -= IsolatedPenalty * wOnFile
      if bOnFile > 0 && adjB == 0L then score += IsolatedPenalty * bOnFile
      f += 1

    // Passed pawns (rank-tapered)
    var bb = wPawns
    while BB.nonEmpty(bb) do
      val sq = BB.lsb(bb).toInt
      if (bPawns & passedMask(Col.White)(sq)) == 0L then
        val r = sq >> 3
        score += PassedRankBonus(r) * (24 - phase) / 24
      bb = BB.clearLsb(bb)
    bb = bPawns
    while BB.nonEmpty(bb) do
      val sq = BB.lsb(bb).toInt
      if (wPawns & passedMask(Col.Black)(sq)) == 0L then
        // mirror rank: black's rank from its own perspective
        val r = 7 - (sq >> 3)
        score -= PassedRankBonus(r) * (24 - phase) / 24
      bb = BB.clearLsb(bb)
    score * sign

  // ── Rook bonuses: open / semi-open files, 7th rank, connected rooks ───────

  private def rookBonuses(pos: Position, wPawns: Long, bPawns: Long, phase: Int): Int =
    val sign = if pos.sideToMove == Col.White then 1 else -1
    var raw = 0
    val occRooks = pos.pieceBB(Col.White, PType.Rook) | pos.pieceBB(Col.Black, PType.Rook)

    // White rooks
    var wr = pos.pieceBB(Col.White, PType.Rook)
    val wRookBB = wr
    while BB.nonEmpty(wr) do
      val sq = BB.lsb(wr).toInt
      val f  = sq & 7
      val r  = sq >> 3
      val wp = wPawns & fileMask(f)
      val bp = bPawns & fileMask(f)
      if wp == 0L && bp == 0L then raw += OpenFileRookBonus
      else if wp == 0L           then raw += SemiOpenFileRookBonus
      if r == 6 then
        val bkSq = pos.kingSquare(Col.Black).toInt
        if (bkSq >> 3) == 7 then raw += RookOn7thBonus
      wr = BB.clearLsb(wr)

    // Black rooks
    var br = pos.pieceBB(Col.Black, PType.Rook)
    val bRookBB = br
    while BB.nonEmpty(br) do
      val sq = BB.lsb(br).toInt
      val f  = sq & 7
      val r  = sq >> 3
      val wp = wPawns & fileMask(f)
      val bp = bPawns & fileMask(f)
      if wp == 0L && bp == 0L then raw -= OpenFileRookBonus
      else if bp == 0L           then raw -= SemiOpenFileRookBonus
      if r == 1 then
        val wkSq = pos.kingSquare(Col.White).toInt
        if (wkSq >> 3) == 0 then raw -= RookOn7thBonus
      br = BB.clearLsb(br)

    raw += ConnectedRooksBonus * connectedRookPairs(wRookBB, pos.occupied)
    raw -= ConnectedRooksBonus * connectedRookPairs(bRookBB, pos.occupied)

    val tapered = raw * (phase + 12) / 24
    tapered * sign

  private def connectedRookPairs(rookBB: Long, occ: Long): Int =
    var pairs = 0
    var b1 = rookBB
    while BB.nonEmpty(b1) do
      val sq1 = BB.lsb(b1).toInt
      var b2 = BB.clearLsb(b1)
      while BB.nonEmpty(b2) do
        val sq2 = BB.lsb(b2).toInt
        val occMinus = occ & ~((1L << sq1) | (1L << sq2))
        if (Magics.rookAttacks(sq1, occMinus) & (1L << sq2)) != 0L then pairs += 1
        b2 = BB.clearLsb(b2)
      b1 = BB.clearLsb(b1)
    pairs

  // ── King safety: zone attackers, pawn shield, file openness ───────────────

  private def kingSafetyScore(pos: Position, wPawns: Long, bPawns: Long, occ: Long, phase: Int): Int =
    if phase <= 8 then 0
    else
      val sign = if pos.sideToMove == Col.White then 1 else -1
      val wPenalty = kingSidePenalty(pos, Col.White, wPawns, bPawns, occ, phase)
      val bPenalty = kingSidePenalty(pos, Col.Black, wPawns, bPawns, occ, phase)
      sign * (bPenalty - wPenalty)

  // Aggregated penalty (positive number) for the king of `c`.
  private def kingSidePenalty(pos: Position, c: Int, wPawns: Long, bPawns: Long, occ: Long, phase: Int): Int =
    val kSq  = pos.kingSquare(c).toInt
    val zone = Attacks.kingAttacks(kSq) | (1L << kSq)
    val opp  = c ^ 1
    var threat = 0
    var pt = 0
    while pt < 5 do
      var bb = pos.pieceBB(opp, pt)
      while BB.nonEmpty(bb) do
        val sq = BB.lsb(bb).toInt
        val atk =
          if pt == PType.Pawn        then Attacks.pawnAttacks(opp)(sq)
          else if pt == PType.Knight then Attacks.knightAttacks(sq)
          else if pt == PType.Bishop then Magics.bishopAttacks(sq, occ)
          else if pt == PType.Rook   then Magics.rookAttacks(sq, occ)
          else                            Magics.bishopAttacks(sq, occ) | Magics.rookAttacks(sq, occ)
        if (atk & zone) != 0L then threat += KingZoneThreat(pt)
        bb = BB.clearLsb(bb)
      pt += 1
    val threatPenalty = threat * phase / 24
    val shieldPenalty = pawnShieldPenalty(c, kSq, if c == Col.White then wPawns else bPawns) * phase / 24
    val filePenalty   = kingFilePenalty(c, kSq, wPawns, bPawns) * phase / 24
    threatPenalty + shieldPenalty + filePenalty

  // Shield: two ranks directly in front of the king on the king's file and
  // adjacent files. Each missing pawn = -10. Only applies when king is on its
  // back two ranks.
  private def pawnShieldPenalty(c: Int, kSq: Int, ownPawns: Long): Int =
    val kFile = kSq & 7
    val kRank = kSq >> 3
    val ownRank = if c == Col.White then kRank else 7 - kRank
    if ownRank > 1 then 0
    else
      var missing = 0
      var df = -1
      while df <= 1 do
        val f = kFile + df
        if f >= 0 && f < 8 then
          val rOne = if c == Col.White then kRank + 1 else kRank - 1
          val rTwo = if c == Col.White then kRank + 2 else kRank - 2
          if rOne >= 0 && rOne < 8 then
            if (ownPawns & (1L << ((rOne << 3) | f))) == 0L then missing += 1
          if rTwo >= 0 && rTwo < 8 then
            if (ownPawns & (1L << ((rTwo << 3) | f))) == 0L then missing += 1
        df += 1
      missing * MissingShieldPawnPenalty

  // Open / semi-open file under the king's file.
  private def kingFilePenalty(c: Int, kSq: Int, wPawns: Long, bPawns: Long): Int =
    val f = kSq & 7
    val ownPawns = if c == Col.White then wPawns else bPawns
    val enemyPawns = if c == Col.White then bPawns else wPawns
    val ownOnFile = (ownPawns & fileMask(f)) != 0L
    val enemyOnFile = (enemyPawns & fileMask(f)) != 0L
    if !ownOnFile && !enemyOnFile then KingFileFullyOpenPenalty
    else if !ownOnFile then KingFileSemiOpenPenalty
    else 0
