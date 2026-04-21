package maichess.engine.domain

final case class BotConfig(
  id:           String,
  name:         String,
  elo:          Int,
  timeLimitMs:  Long,
)
