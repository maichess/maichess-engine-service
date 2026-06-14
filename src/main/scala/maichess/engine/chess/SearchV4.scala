package maichess.engine.chess

// SearchV4 — identical to SearchV3 but evaluates leaves with EvalV2
// (the enhanced evaluation: king safety, pawn structure, full mobility,
// rook bonuses). The only difference is the call inside `quiesce`.
//
// Like Search/SearchV2/SearchV3, SearchV4 is a final class — each request gets
// a fresh instance, so killers, history and the transposition table are never
// shared between concurrent searches.
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return", "org.wartremover.warts.DefaultArguments"))
final class SearchV4 extends MultiPvSearch:
  private val INF     = 100000
  private val MATE    = 99000
  private val TT_SIZE = 1 << 20
  private val TT_MASK = (TT_SIZE - 1).toLong
  private val EXACT = 0; private val LOWER = 1; private val UPPER = 2
  private val MV = Array(100, 320, 330, 500, 900, 20000, 0)
  private val NullMoveR = 3
  private val HistCap   = 1 << 20
  private val MaxExtPly = 96

  private val ttKeys  = new Array[Long](TT_SIZE)
  private val ttData  = new Array[Long](TT_SIZE)
  private val moveBuf = Array.ofDim[Int](128, 256)
  private val mscores = Array.ofDim[Int](128, 256)
  private val killers = Array.ofDim[Int](128, 2)
  private val history = Array.ofDim[Int](2, 64, 64)
  private val quietsBuf = Array.ofDim[Int](128, 256)
  private val seeGain   = new Array[Int](32)

  private var deadline  = 0L
  private var rootBest  = Move.None
  private var rootScore = 0
  private var nodes     = 0
  private var excluded  = Array.empty[Int]

  private inline def isExcluded(mv: Int): Boolean =
    var i = 0; var found = false
    while i < excluded.length && !found do
      if excluded(i) == mv then found = true
      i += 1
    found

  private val MinDepth = 2

  def bestMove(pos: Position, timeLimitMs: Long): (Int, Int) =
    val start = System.currentTimeMillis()
    nodes     = 0
    rootScore = 0
    val initCnt = MoveGen.generate(pos, moveBuf(0))
    rootBest = Move.None
    var i = 0
    while i < initCnt && rootBest == Move.None do
      if LegalCheck.isLegal(pos, moveBuf(0)(i)) then rootBest = moveBuf(0)(i)
      i += 1
    deadline = Long.MaxValue
    var depth = 1
    while depth <= MinDepth do
      negamax(pos, depth, -INF, INF, 0)
      depth += 1
    deadline = start + timeLimitMs
    while depth < 64 && !timeUp() do
      aspirate(pos, depth)
      depth += 1
    (rootBest, rootScore)

  private def aspirate(pos: Position, depth: Int): Unit =
    if depth < 4 || rootScore == 0 then
      negamax(pos, depth, -INF, INF, 0)
    else
      val base  = rootScore
      var delta = 50
      var alpha = Math.max(base - delta, -INF)
      var beta  = Math.min(base + delta, INF)
      var done  = false
      while !done do
        negamax(pos, depth, alpha, beta, 0)
        if timeUp() then done = true
        else
          val sc = rootScore
          if sc <= alpha then
            delta *= 2; alpha = Math.max(base - delta, -INF)
          else if sc >= beta then
            delta *= 2; beta = Math.min(base + delta, INF)
          else done = true

  private inline def timeUp(): Boolean = System.currentTimeMillis() > deadline

  private inline def hasNonPawnMaterial(pos: Position, side: Int): Boolean =
    (pos.byColor(side) & ~pos.pieceBB(side, PType.Pawn) & ~pos.pieceBB(side, PType.King)) != 0L

  private inline def bumpHistory(side: Int, from: Int, to: Int, bonus: Int): Unit =
    val v = history(side)(from)(to) + bonus
    history(side)(from)(to) = if v > HistCap then HistCap else if v < -HistCap then -HistCap else v

  private def negamax(pos: Position, depth: Int, aIn: Int, beta: Int, ply: Int, wasNullMove: Boolean = false): Int =
    nodes += 1
    if (nodes & 4095) == 0 && timeUp() then return 0
    if ply >= 127 then return quiesce(pos, aIn, beta, ply)

    val inCheck = LegalCheck.isInCheck(pos, pos.sideToMove)
    val d       = if inCheck && ply < MaxExtPly then depth + 1 else depth
    if d <= 0 then return quiesce(pos, aIn, beta, ply)

    val idx  = (pos.hash & TT_MASK).toInt
    val tdat = ttData(idx)
    var ttMv = Move.None
    if ttKeys(idx) == (pos.hash ^ tdat) then
      ttMv = (tdat & 0xFFFF).toInt
      val td = ((tdat >> 38) & 63).toInt
      if td >= d then
        val ts = (((tdat >> 16) & 0xFFFFF) - 500000).toInt
        val tf = ((tdat >> 36) & 3).toInt
        if tf == EXACT then
          if ply == 0 && LegalCheck.isLegal(pos, ttMv) then
            rootBest = ttMv; rootScore = ts
          return ts
        if tf == LOWER && ts >= beta then return ts
        if tf == UPPER && ts <= aIn  then return ts

    if d >= 3 && !inCheck && ply > 0 && !wasNullMove && hasNonPawnMaterial(pos, pos.sideToMove) then
      val nd = d - 1 - NullMoveR
      pos.makeNullMove()
      val nullScore = -negamax(pos, if nd < 0 then 0 else nd, -beta, -beta + 1, ply + 1, wasNullMove = true)
      pos.unmakeNullMove()
      if nullScore >= beta then return beta

    val cnt = MoveGen.generate(pos, moveBuf(ply))
    scoreM(pos, moveBuf(ply), mscores(ply), cnt, ttMv, ply)

    val stm = pos.sideToMove
    var alpha = aIn; var best = -INF; var bestMv = Move.None; var legal = 0; var nQuiet = 0
    var i = 0
    while i < cnt do
      val mv = pick(moveBuf(ply), mscores(ply), cnt, i)
      if !(ply == 0 && isExcluded(mv)) && LegalCheck.isLegal(pos, mv) then
        legal += 1
        val isQuiet = !Move.isCapture(mv) && !Move.isPromo(mv)
        pos.makeMove(mv)
        val sc =
          if legal == 1 then
            -negamax(pos, d - 1, -beta, -alpha, ply + 1)
          else
            if d >= 3 && legal >= 4 && isQuiet && !inCheck then
              val rRaw = Math.max(1, (Math.log(d.toDouble) * Math.log(legal.toDouble) / 2.0).toInt)
              val r    = if rRaw > d - 2 then d - 2 else rRaw
              val reduced = -negamax(pos, d - 1 - r, -alpha - 1, -alpha, ply + 1)
              if reduced > alpha then -negamax(pos, d - 1, -beta, -alpha, ply + 1)
              else reduced
            else
              val zw = -negamax(pos, d - 1, -alpha - 1, -alpha, ply + 1)
              if zw > alpha && zw < beta then -negamax(pos, d - 1, -beta, -alpha, ply + 1)
              else zw
        pos.unmakeMove(mv)
        if sc > best then
          best = sc; bestMv = mv
          if ply == 0 then { rootBest = mv; rootScore = sc }
        if sc > alpha then alpha = sc
        if alpha >= beta then
          if isQuiet then
            if killers(ply)(0) != mv then
              killers(ply)(1) = killers(ply)(0)
              killers(ply)(0) = mv
            bumpHistory(stm, Move.from(mv).toInt, Move.to(mv).toInt, d * d)
            var q = 0
            while q < nQuiet do
              val qm = quietsBuf(ply)(q)
              bumpHistory(stm, Move.from(qm).toInt, Move.to(qm).toInt, -d)
              q += 1
          storeT(idx, pos.hash, mv, sc, d, LOWER)
          return alpha
        if isQuiet then
          quietsBuf(ply)(nQuiet) = mv; nQuiet += 1
      i += 1

    if legal == 0 then
      return if inCheck then -(MATE - ply) else 0

    storeT(idx, pos.hash, bestMv, best, d, if best > aIn then EXACT else UPPER)
    best

  private def quiesce(pos: Position, aIn: Int, beta: Int, ply: Int): Int =
    if timeUp() then return 0
    val stand = EvalV2.evaluate(pos)
    if stand >= beta then return stand
    if ply >= 127    then return stand
    var alpha = Math.max(aIn, stand)
    val cnt = MoveGen.generateCaptures(pos, moveBuf(ply))
    scoreM(pos, moveBuf(ply), mscores(ply), cnt, Move.None, ply)
    var i = 0
    while i < cnt do
      val mv = pick(moveBuf(ply), mscores(ply), cnt, i)
      if LegalCheck.isLegal(pos, mv) then
        pos.makeMove(mv)
        val sc = -quiesce(pos, -beta, -alpha, ply + 1)
        pos.unmakeMove(mv)
        if sc >= beta then return sc
        if sc > alpha then alpha = sc
      i += 1
    alpha

  def bestMoveAtDepth(pos: Position, targetDepth: Int, excl: Array[Int]): (Int, Int) =
    excluded  = excl
    deadline  = Long.MaxValue
    nodes     = 0
    rootScore = 0
    val initCnt = MoveGen.generate(pos, moveBuf(0))
    rootBest = Move.None
    var i = 0
    while i < initCnt && rootBest == Move.None do
      if !isExcluded(moveBuf(0)(i)) && LegalCheck.isLegal(pos, moveBuf(0)(i)) then
        rootBest = moveBuf(0)(i)
      i += 1
    var depth = 1
    while depth <= targetDepth do
      negamax(pos, depth, -INF, INF, 0)
      depth += 1
    (rootBest, rootScore)

  def extractPv(pos: Position, maxLength: Int): List[Int] =
    val buf     = new Array[Int](maxLength)
    val hashes  = new Array[Long](maxLength)
    var len     = 0
    var cont    = true
    while cont && len < maxLength do
      val idx  = (pos.hash & TT_MASK).toInt
      val tdat = ttData(idx)
      if ttKeys(idx) != (pos.hash ^ tdat) then cont = false
      else
        val mv = (tdat & 0xFFFF).toInt
        var cycle = false
        var j = 0
        while j < len && !cycle do
          if hashes(j) == pos.hash then cycle = true
          j += 1
        if mv == Move.None || cycle || !LegalCheck.isLegal(pos, mv) then cont = false
        else
          hashes(len) = pos.hash
          buf(len)    = mv
          len        += 1
          pos.makeMove(mv)
    var k = len - 1
    while k >= 0 do
      pos.unmakeMove(buf(k))
      k -= 1
    buf.take(len).toList

  private def scoreM(pos: Position, mvs: Array[Int], sc: Array[Int], cnt: Int, ttMv: Int, ply: Int): Unit =
    val stm = pos.sideToMove
    var i = 0
    while i < cnt do
      val mv = mvs(i)
      sc(i) =
        if mv == ttMv then 2000000
        else if Move.isCapture(mv) then
          val s = seeMove(pos, mv)
          if s >= 0 then 1000000 + s else s - 100000
        else if mv == killers(ply)(0) then 900000
        else if mv == killers(ply)(1) then 800000
        else if Move.isPromo(mv) then 500000
        else history(stm)(Move.from(mv).toInt)(Move.to(mv).toInt)
      i += 1

  def seeMove(pos: Position, mv: Int): Int =
    val to       = Move.to(mv).toInt
    val from     = Move.from(mv).toInt
    val isEp     = Move.flag(mv) == Move.FlagEP
    val target   = if isEp then PType.Pawn else Pieces.typeOf(Piece(pos.mailbox(to)))
    val attacker = Pieces.typeOf(Piece(pos.mailbox(from)))
    see(pos, to, target, from, attacker, isEp)

  private def see(pos: Position, to: Int, target: Int, from: Int, attacker: Int, isEp: Boolean): Int =
    var occ = pos.occupied & ~(1L << from)
    if isEp then
      val capSq = if pos.sideToMove == Col.White then to - 8 else to + 8
      occ &= ~(1L << capSq)
    var d = 0
    seeGain(0) = MV(target)
    var lastAtt = attacker
    var side = pos.sideToMove ^ 1
    var cont = true
    while cont && d < 31 do
      val att = attackersTo(pos, to, occ) & occ & pos.byColor(side)
      if att == 0L then cont = false
      else
        var pt = PType.Pawn; var sq = -1
        while sq < 0 && pt <= PType.King do
          val bb = att & pos.pieceBB(side, pt)
          if bb != 0L then sq = java.lang.Long.numberOfTrailingZeros(bb) else pt += 1
        d += 1
        seeGain(d) = MV(lastAtt) - seeGain(d - 1)
        if Math.max(-seeGain(d - 1), seeGain(d)) < 0 then cont = false
        else
          lastAtt = pt
          occ &= ~(1L << sq)
          side ^= 1
    while d > 0 do
      seeGain(d - 1) = -Math.max(-seeGain(d - 1), seeGain(d)); d -= 1
    seeGain(0)

  private def attackersTo(pos: Position, sq: Int, occ: Long): Long =
    val wp = pos.pieceBB(Col.White, PType.Pawn)
    val bp = pos.pieceBB(Col.Black, PType.Pawn)
    val n  = pos.pieceBB(Col.White, PType.Knight) | pos.pieceBB(Col.Black, PType.Knight)
    val k  = pos.pieceBB(Col.White, PType.King)   | pos.pieceBB(Col.Black, PType.King)
    val bq = pos.pieceBB(Col.White, PType.Bishop) | pos.pieceBB(Col.Black, PType.Bishop) |
             pos.pieceBB(Col.White, PType.Queen)  | pos.pieceBB(Col.Black, PType.Queen)
    val rq = pos.pieceBB(Col.White, PType.Rook)   | pos.pieceBB(Col.Black, PType.Rook) |
             pos.pieceBB(Col.White, PType.Queen)  | pos.pieceBB(Col.Black, PType.Queen)
    (Attacks.pawnAttacks(Col.Black)(sq) & wp) |
    (Attacks.pawnAttacks(Col.White)(sq) & bp) |
    (Attacks.knightAttacks(sq)          & n)  |
    (Attacks.kingAttacks(sq)            & k)  |
    (Magics.bishopAttacks(sq, occ)      & bq) |
    (Magics.rookAttacks(sq, occ)        & rq)

  private inline def pick(mvs: Array[Int], sc: Array[Int], cnt: Int, i: Int): Int =
    var best = i; var j = i + 1
    while j < cnt do
      if sc(j) > sc(best) then best = j
      j += 1
    if best != i then
      val tm = mvs(i); mvs(i) = mvs(best); mvs(best) = tm
      val ts = sc(i);  sc(i) = sc(best);   sc(best) = ts
    mvs(i)

  private inline def storeT(idx: Int, hash: Long, mv: Int, sc: Int, depth: Int, flag: Int): Unit =
    val d = (mv & 0xFFFF).toLong | ((sc.toLong + 500000L) << 16) |
            (flag.toLong << 36) | (depth.toLong << 38)
    ttData(idx) = d; ttKeys(idx) = hash ^ d
