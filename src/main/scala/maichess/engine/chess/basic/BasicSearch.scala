package maichess.engine.chess.basic

// Iterative-deepening minimax with alpha-beta pruning.
// No transposition table — the defining limitation of the Tier 0 engine.
// Move ordering: captures before quiet moves (partition only, no MVV-LVA scores).
// Check time every 512 nodes so the search respects the move-time budget.
//
// BasicSearch is a final class (not an object) so each RPC call gets its own
// mutable state with no sharing between concurrent searches.
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
final class BasicSearch:
  private val INF  = 100000
  private val MATE = 99000

  private var deadline  = 0L
  private var rootBest  = Option.empty[BasicMove]
  private var rootScore = 0
  private var nodes     = 0

  // Returns the best move found within the time limit, or None if the position
  // has no legal moves (checkmate or stalemate).
  def bestMove(pos: BasicPosition, timeLimitMs: Long): (Option[BasicMove], Int) =
    deadline  = System.currentTimeMillis() + timeLimitMs
    nodes     = 0
    rootScore = 0
    rootBest  = BasicMoveGen.legalMoves(pos).headOption
    if rootBest.isEmpty then return (None, 0)
    var depth = 1
    while depth < 64 && !timeUp() do
      minimax(pos, depth, -INF, INF, 0)
      depth += 1
    (rootBest, rootScore)

  private def timeUp(): Boolean = System.currentTimeMillis() > deadline

  private def minimax(pos: BasicPosition, depth: Int, aIn: Int, beta: Int, ply: Int): Int =
    nodes += 1
    if (nodes & 511) == 0 && timeUp() then return 0
    if depth == 0 then return BasicEval.evaluate(pos)

    val legal = BasicMoveGen.legalMoves(pos)
    if legal.isEmpty then
      return if BasicMoveGen.isInCheck(pos, pos.sideToMove) then -(MATE - ply) else 0

    // Captures first, then quiet moves — simple partition, no scoring
    val (captures, quiets) = legal.partition(mv => mv.flag == BasicFlag.Capture || mv.flag == BasicFlag.EP)
    val ordered = (captures ::: quiets).toArray

    var alpha  = aIn
    var best   = -INF
    var i      = 0
    while i < ordered.length do
      val mv    = ordered(i)
      val saved = pos.makeMove(mv)
      val sc    = -minimax(pos, depth - 1, -beta, -alpha, ply + 1)
      pos.unmakeMove(mv, saved)
      if sc > best then
        best = sc
        if ply == 0 then
          rootBest  = Some(mv)
          rootScore = sc
      if sc > alpha then alpha = sc
      if alpha >= beta then return alpha
      i += 1
    best

  // Converts a BasicMove to UCI notation (e.g. "e2e4", "e7e8q").
  def toUci(mv: BasicMove): String =
    val fromFile = ('a' + mv.from % 8).toChar
    val fromRank = ('1' + mv.from / 8).toChar
    val toFile   = ('a' + mv.to % 8).toChar
    val toRank   = ('1' + mv.to / 8).toChar
    val promoStr =
      if mv.promo == 0 then ""
      else Math.abs(mv.promo) match
        case BasicPiece.WKnight => "n"
        case BasicPiece.WBishop => "b"
        case BasicPiece.WRook   => "r"
        case _                  => "q"
    s"$fromFile$fromRank$toFile$toRank$promoStr"
