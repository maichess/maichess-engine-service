package maichess.engine.domain

object BotRegistry:

  val all: List[BotConfig] = List(
    BotConfig(id = "bullet",    name = "Bullet",    elo = 1400, timeLimitMs = 100),
    BotConfig(id = "blitz",     name = "Blitz",     elo = 1700, timeLimitMs = 1000),
    BotConfig(id = "classical", name = "Classical", elo = 2000, timeLimitMs = 5000),
  )

  def find(id: String): Option[BotConfig] = all.find(_.id == id)
