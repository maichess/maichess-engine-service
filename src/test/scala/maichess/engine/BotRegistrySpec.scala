package maichess.engine

import zio.test.*
import maichess.engine.domain.{BotConfig, BotRegistry, TimingStrategy}

object BotRegistrySpec extends ZIOSpecDefault:

  private val expectedIds = List(
    "bullet", "bullet_proportional", "bullet_aggressive",
    "blitz",  "blitz_proportional",  "blitz_aggressive",
    "classical", "classical_proportional", "classical_aggressive",
  )

  def spec = suite("BotRegistry")(

    suite("all")(
      test("exposes exactly nine bots") {
        assertTrue(BotRegistry.all.length == 9)
      },
      test("bots have the expected ids in order") {
        assertTrue(BotRegistry.all.map(_.id) == expectedIds)
      },
      test("fixed bots use the Fixed strategy") {
        val fixed = BotRegistry.all.filter(b => b.id == "bullet" || b.id == "blitz" || b.id == "classical")
        assertTrue(fixed.forall(_.strategy.isInstanceOf[TimingStrategy.Fixed]))
      },
      test("proportional bots use the Proportional strategy") {
        val props = BotRegistry.all.filter(_.id.endsWith("_proportional"))
        assertTrue(props.length == 3 && props.forall(_.strategy.isInstanceOf[TimingStrategy.Proportional]))
      },
      test("aggressive bots use the Aggressive strategy") {
        val aggs = BotRegistry.all.filter(_.id.endsWith("_aggressive"))
        assertTrue(aggs.length == 3 && aggs.forall(_.strategy.isInstanceOf[TimingStrategy.Aggressive]))
      },
    ),

    suite("find")(
      test("returns the config for every known id") {
        assertTrue(expectedIds.forall(id => BotRegistry.find(id).isDefined))
      },
      test("returns None for an unknown id") {
        assertTrue(BotRegistry.find("nonexistent").isEmpty)
      },
      test("bullet has elo 1400") {
        assertTrue(BotRegistry.find("bullet").exists(_.elo == 1400))
      },
      test("blitz has elo 1700") {
        assertTrue(BotRegistry.find("blitz").exists(_.elo == 1700))
      },
      test("classical has elo 2000") {
        assertTrue(BotRegistry.find("classical").exists(_.elo == 2000))
      },
    ),

    suite("BotConfig")(
      test("Fixed strategy: computeMoveTime ignores remaining time") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Fixed(500L))
        assertTrue(cfg.computeMoveTime(Some(100000L)) == 500L)
      },
      test("Fixed strategy: computeMoveTime falls back to moveTimeMs when no clock") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Fixed(500L))
        assertTrue(cfg.computeMoveTime(None) == 500L)
      },
      test("Proportional strategy: computes remaining / divisor") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Proportional(divisor = 30, minMs = 100L, fallbackMs = 1000L))
        assertTrue(cfg.computeMoveTime(Some(30000L)) == 1000L)
      },
      test("Proportional strategy: respects minimum") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Proportional(divisor = 30, minMs = 200L, fallbackMs = 1000L))
        assertTrue(cfg.computeMoveTime(Some(100L)) == 200L)
      },
      test("Proportional strategy: falls back when no clock") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Proportional(divisor = 30, minMs = 100L, fallbackMs = 1000L))
        assertTrue(cfg.computeMoveTime(None) == 1000L)
      },
      test("Aggressive strategy: computes fraction of remaining") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Aggressive(fraction = 0.1, minMs = 50L, fallbackMs = 500L))
        assertTrue(cfg.computeMoveTime(Some(10000L)) == 1000L)
      },
      test("Aggressive strategy: respects minimum") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Aggressive(fraction = 0.1, minMs = 200L, fallbackMs = 500L))
        assertTrue(cfg.computeMoveTime(Some(100L)) == 200L)
      },
      test("Aggressive strategy: falls back when no clock") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Aggressive(fraction = 0.1, minMs = 50L, fallbackMs = 500L))
        assertTrue(cfg.computeMoveTime(None) == 500L)
      },
    ),
  )
