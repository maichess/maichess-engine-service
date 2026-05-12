package maichess.engine.chess.basic

@SuppressWarnings(Array("org.wartremover.warts.Var"))
object BasicEval:
  private val PawnVal   = 100
  private val KnightVal = 320
  private val BishopVal = 330
  private val RookVal   = 500
  private val QueenVal  = 900

  def evaluate(pos: BasicPosition): Int =
    var score = 0
    var sq    = 0
    while sq < 64 do
      val piece = pos.board(sq)
      val value = Math.abs(piece) match
        case 1 => PawnVal
        case 2 => KnightVal
        case 3 => BishopVal
        case 4 => RookVal
        case 5 => QueenVal
        case _ => 0
      if piece > 0 then score += value
      else if piece < 0 then score -= value
      sq += 1
    score * pos.sideToMove
