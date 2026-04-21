package maichess.engine

import zio.test.*
import maichess.engine.domain.{BotConfig, BotRegistry}

object BotRegistrySpec extends ZIOSpecDefault:

  def spec = suite("BotRegistry")(

    suite("all")(
      test("exposes exactly three bots") {
        assertTrue(BotRegistry.all.length == 3)
      },
      test("bots have the expected ids") {
        val ids = BotRegistry.all.map(_.id)
        assertTrue(ids == List("bullet", "blitz", "classical"))
      },
      test("bots have increasing ELO") {
        val elos = BotRegistry.all.map(_.elo)
        assertTrue(elos == elos.sorted)
      },
      test("bots have increasing time limits") {
        val limits = BotRegistry.all.map(_.timeLimitMs)
        assertTrue(limits == limits.sorted)
      },
    ),

    suite("find")(
      test("returns the config for a known id") {
        assertTrue(BotRegistry.find("bullet").contains(BotConfig(id = "bullet", name = "Bullet", elo = 1400, timeLimitMs = 100L)))
      },
      test("returns None for an unknown id") {
        assertTrue(BotRegistry.find("nonexistent").isEmpty)
      },
      test("returns blitz config") {
        val result = BotRegistry.find("blitz")
        assertTrue(result.exists(_.timeLimitMs == 1000L))
      },
      test("returns classical config") {
        val result = BotRegistry.find("classical")
        assertTrue(result.exists(_.elo == 2000))
      },
    ),

    suite("BotConfig")(
      test("stores all fields") {
        val cfg = BotConfig(id = "x", name = "X", elo = 1000, timeLimitMs = 500L)
        assertTrue(cfg.id == "x")
        assertTrue(cfg.name == "X")
        assertTrue(cfg.elo == 1000)
        assertTrue(cfg.timeLimitMs == 500L)
      },
    ),
  )
