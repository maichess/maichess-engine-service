package maichess.engine.chess

// Iterative-deepening negamax alpha-beta with:
//   - Transposition table (XOR-key, 20-bit score offset, fail-hard)
//   - Quiescence search (captures only)
//   - Move ordering: TT move first, then MVV-LVA for captures, quiet moves last
//
// Search is a final class (not an object) so each request gets its own
// transposition table — no shared mutable state between concurrent searches.
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
final class Search:
  private val INF     = 100000
  private val MATE    = 99000
  private val TT_SIZE = 1 << 20          // ~1M entries × 16 bytes = 16 MB
  private val TT_MASK = (TT_SIZE - 1).toLong
  private val EXACT = 0; private val LOWER = 1; private val UPPER = 2
  private val MV = Array(100, 320, 330, 500, 900, 20000, 0)   // MVV-LVA values; index 6 guards NoPiece

  private val ttKeys  = new Array[Long](TT_SIZE)
  private val ttData  = new Array[Long](TT_SIZE)
  private val moveBuf = Array.ofDim[Int](128, 256)
  private val mscores = Array.ofDim[Int](128, 256)

  private var deadline  = 0L
  private var rootBest  = Move.None
  private var rootScore = 0
  private var nodes     = 0

  def bestMove(pos: Position, timeLimitMs: Long): (Int, Int) =
    deadline  = System.currentTimeMillis() + timeLimitMs
    nodes     = 0
    rootScore = 0
    val initCnt = MoveGen.generate(pos, moveBuf(0))
    rootBest = Move.None
    var i = 0
    while i < initCnt && rootBest == Move.None do
      if LegalCheck.isLegal(pos, moveBuf(0)(i)) then rootBest = moveBuf(0)(i)
      i += 1
    var depth = 1
    while depth < 64 && !timeUp() do
      negamax(pos, depth, -INF, INF, 0)
      depth += 1
    (rootBest, rootScore)

  private inline def timeUp(): Boolean = System.currentTimeMillis() > deadline

  private def negamax(pos: Position, depth: Int, aIn: Int, beta: Int, ply: Int): Int =
    nodes += 1
    if (nodes & 4095) == 0 && timeUp() then return 0
    if depth == 0 then return quiesce(pos, aIn, beta, ply)

    val idx  = (pos.hash & TT_MASK).toInt
    val tdat = ttData(idx)
    var ttMv = Move.None
    if ttKeys(idx) == (pos.hash ^ tdat) then
      ttMv = (tdat & 0xFFFF).toInt
      val td = ((tdat >> 38) & 63).toInt
      if td >= depth then
        val ts = (((tdat >> 16) & 0xFFFFF) - 500000).toInt
        val tf = ((tdat >> 36) & 3).toInt
        if tf == EXACT then
          if ply == 0 && LegalCheck.isLegal(pos, ttMv) then
            rootBest = ttMv; rootScore = ts
          return ts
        if tf == LOWER && ts >= beta then return ts
        if tf == UPPER && ts <= aIn  then return ts

    val cnt = MoveGen.generate(pos, moveBuf(ply))
    scoreM(pos, moveBuf(ply), mscores(ply), cnt, ttMv)

    var alpha = aIn; var best = -INF; var bestMv = Move.None; var legal = 0
    var i = 0
    while i < cnt do
      val mv = pick(moveBuf(ply), mscores(ply), cnt, i)
      if LegalCheck.isLegal(pos, mv) then
        legal += 1
        pos.makeMove(mv)
        val sc = -negamax(pos, depth - 1, -beta, -alpha, ply + 1)
        pos.unmakeMove(mv)
        if sc > best then
          best = sc; bestMv = mv
          if ply == 0 then rootBest = mv; rootScore = sc
        if sc > alpha then alpha = sc
        if alpha >= beta then
          storeT(idx, pos.hash, mv, sc, depth, LOWER)
          return alpha
      i += 1

    if legal == 0 then
      return if LegalCheck.isInCheck(pos, pos.sideToMove) then -(MATE - ply) else 0

    storeT(idx, pos.hash, bestMv, best, depth, if best > aIn then EXACT else UPPER)
    best

  private def quiesce(pos: Position, aIn: Int, beta: Int, ply: Int): Int =
    if timeUp() then return 0
    val stand = Eval.evaluate(pos)
    if stand >= beta then return stand
    if ply >= 127    then return stand
    var alpha = Math.max(aIn, stand)
    val cnt = MoveGen.generateCaptures(pos, moveBuf(ply))
    scoreM(pos, moveBuf(ply), mscores(ply), cnt, Move.None)
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

  private def scoreM(pos: Position, mvs: Array[Int], sc: Array[Int], cnt: Int, ttMv: Int): Unit =
    var i = 0
    while i < cnt do
      val mv = mvs(i)
      sc(i) =
        if mv == ttMv then 2000000
        else if Move.isCapture(mv) then
          val vic = if Move.flag(mv) == Move.FlagEP then PType.Pawn
                    else Pieces.typeOf(Piece(pos.mailbox(Move.to(mv).toInt)))
          val att = Pieces.typeOf(Piece(pos.mailbox(Move.from(mv).toInt)))
          1000000 + MV(vic) * 10 - MV(att)
        else if Move.isPromo(mv) then 500000
        else 0
      i += 1

  // Partial selection sort: bring the highest-scored move at index i to the front.
  private inline def pick(mvs: Array[Int], sc: Array[Int], cnt: Int, i: Int): Int =
    var best = i; var j = i + 1
    while j < cnt do
      if sc(j) > sc(best) then best = j
      j += 1
    if best != i then
      val tm = mvs(i); mvs(i) = mvs(best); mvs(best) = tm
      val ts = sc(i);  sc(i) = sc(best);   sc(best) = ts
    mvs(i)

  // Pack entry: bits 0-15 = move, 16-35 = score+500000, 36-37 = flag, 38-43 = depth.
  private inline def storeT(idx: Int, hash: Long, mv: Int, sc: Int, depth: Int, flag: Int): Unit =
    val d = (mv & 0xFFFF).toLong | ((sc.toLong + 500000L) << 16) |
            (flag.toLong << 36) | (depth.toLong << 38)
    ttData(idx) = d; ttKeys(idx) = hash ^ d
