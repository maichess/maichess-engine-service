package maichess.engine.domain

final case class BotConfig(
  id:       String,
  name:     String,
  elo:      Int,
  strategy: TimingStrategy,
):
  def computeMoveTime(remainingTimeMs: Option[Long]): Long =
    remainingTimeMs.fold(strategy.fallbackMs)(strategy.computeMoveTime)
