package maichess.engine.chess

// Common surface for fixed-depth multi-PV analysis, implemented by every non-Basic
// search tier (Search, SearchV2, SearchV3, SearchV4). `bestMoveAtDepth` runs a
// depth-limited root search that skips `excl` root moves (so successive ranks
// enumerate alternative lines); `extractPv` reconstructs the principal variation
// from the transposition table populated by the immediately preceding call.
//
// Lets the analysis layer dispatch on bot variant without knowing the concrete
// search class — a stronger bot analyses with its own (stronger) search + eval.
trait MultiPvSearch:
  def bestMoveAtDepth(pos: Position, targetDepth: Int, excl: Array[Int]): (Int, Int)
  def extractPv(pos: Position, maxLength: Int): List[Int]
