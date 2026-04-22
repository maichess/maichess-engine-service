package maichess.engine.domain

import maichess.engine.domain.TimingStrategy.*

object BotRegistry:

  val all: List[BotConfig] = List(
    BotConfig("bullet",               "Bullet",                1400, Fixed(100L)),
    BotConfig("bullet_proportional",  "Bullet Proportional",   1400, Proportional(divisor = 40, minMs = 50L,   fallbackMs = 100L)),
    BotConfig("bullet_aggressive",    "Bullet Aggressive",     1400, Aggressive(fraction = 0.07, minMs = 50L,  fallbackMs = 100L)),
    BotConfig("blitz",                "Blitz",                 1700, Fixed(1000L)),
    BotConfig("blitz_proportional",   "Blitz Proportional",    1700, Proportional(divisor = 30, minMs = 200L,  fallbackMs = 1000L)),
    BotConfig("blitz_aggressive",     "Blitz Aggressive",      1700, Aggressive(fraction = 0.06, minMs = 200L, fallbackMs = 1000L)),
    BotConfig("classical",            "Classical",             2000, Fixed(5000L)),
    BotConfig("classical_proportional","Classical Proportional",2000, Proportional(divisor = 25, minMs = 500L,  fallbackMs = 5000L)),
    BotConfig("classical_aggressive", "Classical Aggressive",  2000, Aggressive(fraction = 0.05, minMs = 500L, fallbackMs = 5000L)),
  )

  def find(id: String): Option[BotConfig] = all.find(_.id == id)
