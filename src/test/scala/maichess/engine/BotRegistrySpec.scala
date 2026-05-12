package maichess.engine

import zio.test.*
import maichess.engine.domain.{BotConfig, BotRegistry, EngineVariant, TimingStrategy}

object BotRegistrySpec extends ZIOSpecDefault:

  private val baseIds = List(
    "bullet", "bullet_proportional", "bullet_aggressive",
    "blitz",  "blitz_proportional",  "blitz_aggressive",
    "classical", "classical_proportional", "classical_aggressive",
  )

  private val basicIds = List(
    "basic_bullet", "basic_bullet_prop", "basic_bullet_aggr",
    "basic_blitz",  "basic_blitz_prop",  "basic_blitz_aggr",
    "basic_classical", "basic_classical_prop", "basic_classical_aggr",
  )

  private val searchIds = List(
    "search_bullet", "search_bullet_prop", "search_bullet_aggr",
    "search_blitz",  "search_blitz_prop",  "search_blitz_aggr",
    "search_classical", "search_classical_prop", "search_classical_aggr",
  )

  private val allIds = baseIds ::: basicIds ::: searchIds

  def spec = suite("BotRegistry")(

    suite("all")(
      test("exposes exactly twenty-seven bots") {
        assertTrue(BotRegistry.all.length == 27)
      },
      test("bots have the expected ids in order") {
        assertTrue(BotRegistry.all.map(_.id) == allIds)
      },
      test("fixed bots use the Fixed strategy") {
        val fixed = BotRegistry.all.filter(b =>
          b.id == "bullet" || b.id == "blitz" || b.id == "classical" ||
          b.id == "basic_bullet" || b.id == "basic_blitz" || b.id == "basic_classical" ||
          b.id == "search_bullet" || b.id == "search_blitz" || b.id == "search_classical")
        assertTrue(fixed.length == 9 && fixed.forall(_.strategy.isInstanceOf[TimingStrategy.Fixed]))
      },
      test("proportional bots use the Proportional strategy") {
        val props = BotRegistry.all.filter(b => b.id.endsWith("_proportional") || b.id.endsWith("_prop"))
        assertTrue(props.length == 9 && props.forall(_.strategy.isInstanceOf[TimingStrategy.Proportional]))
      },
      test("aggressive bots use the Aggressive strategy") {
        val aggs = BotRegistry.all.filter(b => b.id.endsWith("_aggressive") || b.id.endsWith("_aggr"))
        assertTrue(aggs.length == 9 && aggs.forall(_.strategy.isInstanceOf[TimingStrategy.Aggressive]))
      },
      test("all bots have a non-empty description") {
        assertTrue(BotRegistry.all.forall(_.description.nonEmpty))
      },
      test("all base bots have variant Base") {
        assertTrue(baseIds.forall(id => BotRegistry.find(id).exists(_.variant == EngineVariant.Base)))
      },
      test("all basic bots have variant Basic") {
        assertTrue(basicIds.forall(id => BotRegistry.find(id).exists(_.variant == EngineVariant.Basic)))
      },
      test("all search bots have variant EnhancedSearch") {
        assertTrue(searchIds.forall(id => BotRegistry.find(id).exists(_.variant == EngineVariant.EnhancedSearch)))
      },
      test("all search bots have elo from the enhanced-search tier") {
        assertTrue(searchIds.forall(id => BotRegistry.find(id).exists(b => Set(1600, 1900, 2150).contains(b.elo))))
      },
    ),

    suite("find")(
      test("returns the config for every known id") {
        assertTrue(allIds.forall(id => BotRegistry.find(id).isDefined))
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
      test("basic_bullet has elo 700") {
        assertTrue(BotRegistry.find("basic_bullet").exists(_.elo == 700))
      },
      test("basic_blitz has elo 800") {
        assertTrue(BotRegistry.find("basic_blitz").exists(_.elo == 800))
      },
      test("basic_classical has elo 900") {
        assertTrue(BotRegistry.find("basic_classical").exists(_.elo == 900))
      },
      test("every known bot has a non-empty description") {
        assertTrue(allIds.forall(id => BotRegistry.find(id).exists(_.description.nonEmpty)))
      },
    ),

    suite("BotConfig")(
      test("Fixed strategy: computeMoveTime ignores remaining time") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Fixed(500L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(Some(100000L)) == 500L)
      },
      test("Fixed strategy: computeMoveTime falls back to moveTimeMs when no clock") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Fixed(500L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(None) == 500L)
      },
      test("Proportional strategy: computes remaining / divisor") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Proportional(divisor = 30, minMs = 100L, fallbackMs = 1000L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(Some(30000L)) == 1000L)
      },
      test("Proportional strategy: respects minimum") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Proportional(divisor = 30, minMs = 200L, fallbackMs = 1000L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(Some(100L)) == 200L)
      },
      test("Proportional strategy: falls back when no clock") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Proportional(divisor = 30, minMs = 100L, fallbackMs = 1000L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(None) == 1000L)
      },
      test("Aggressive strategy: computes fraction of remaining") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Aggressive(fraction = 0.1, minMs = 50L, fallbackMs = 500L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(Some(10000L)) == 1000L)
      },
      test("Aggressive strategy: respects minimum") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Aggressive(fraction = 0.1, minMs = 200L, fallbackMs = 500L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(Some(100L)) == 200L)
      },
      test("Aggressive strategy: falls back when no clock") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, strategy = TimingStrategy.Aggressive(fraction = 0.1, minMs = 50L, fallbackMs = 500L), description = "", variant = EngineVariant.Base)
        assertTrue(cfg.computeMoveTime(None) == 500L)
      },
    ),
  )
