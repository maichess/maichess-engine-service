package maichess.engine.chess

// Static evaluation. Returns centipawns from the perspective of pos.sideToMove.
// Components: material + piece-square tables (tapered king) + bishop/knight mobility.
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object Eval:

  private val Mat = Array(100, 320, 330, 500, 900, 0)   // Pawn..King material
  private val Ph  = Array(0, 1, 1, 2, 4, 0)             // phase weights per piece type
  // Maximum phase = 2 queens(8) + 4 rooks(8) + 4 bishops(4) + 4 knights(4) = 24

  // PST arrays in engine sq order: sq0=a1 (bottom-left) .. sq63=h8 (top-right).
  // Each row is one rank; rank 1 first, rank 8 last.
  // Black pieces use index (sq ^ 56) to mirror the rank.
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

  // King prefers castled corners in the opening, active centre in the endgame.
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

  def evaluate(pos: Position): Int =
    val occ = pos.occupied
    var mat = 0; var phase = 0; var mob = 0; var opK = 0; var egK = 0
    var c = 0
    while c < 2 do
      val s = if c == pos.sideToMove then 1 else -1
      var pt = 0
      while pt < 5 do
        var bb = pos.pieceBB(c, pt)
        while BB.nonEmpty(bb) do
          val sq  = BB.lsb(bb).toInt
          val idx = if c == Col.White then sq else sq ^ 56
          mat   += s * (Mat(pt) + Pst(pt)(idx))
          phase += Ph(pt)
          if pt == PType.Knight then mob += s * BB.popcount(Attacks.knightAttacks(sq))
          if pt == PType.Bishop then mob += s * BB.popcount(Magics.bishopAttacks(sq, occ))
          bb = BB.clearLsb(bb)
        pt += 1
      val kSq  = pos.kingSquare(c).toInt
      val kIdx = if c == Col.White then kSq else kSq ^ 56
      opK += s * KingMid(kIdx)
      egK += s * KingEnd(kIdx)
      c += 1
    val p = Math.min(phase, 24)
    mat + (opK * p + egK * (24 - p)) / 24 + mob
