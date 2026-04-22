package maichess.engine.domain

sealed trait TimingStrategy:
  def fallbackMs: Long
  def computeMoveTime(remainingMs: Long): Long

object TimingStrategy:
  // Ignores the game clock entirely — always allocates the same fixed time.
  final case class Fixed(moveTimeMs: Long) extends TimingStrategy:
    val fallbackMs: Long                          = moveTimeMs
    def computeMoveTime(remainingMs: Long): Long  = moveTimeMs

  // Assumes approximately `divisor` moves remain; uses a proportional slice.
  final case class Proportional(divisor: Int, minMs: Long, override val fallbackMs: Long) extends TimingStrategy:
    def computeMoveTime(remainingMs: Long): Long = Math.max(minMs, remainingMs / divisor)

  // Spends a fixed fraction of remaining time per move, biased toward the opening/midgame.
  final case class Aggressive(fraction: Double, minMs: Long, override val fallbackMs: Long) extends TimingStrategy:
    def computeMoveTime(remainingMs: Long): Long = Math.max(minMs, (remainingMs * fraction).toLong)
