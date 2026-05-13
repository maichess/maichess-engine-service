package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.service.clients.{TablebaseClient, TablebaseResult}

object TablebaseClientSpec extends ZIOSpecDefault:

  private val winJson =
    """{"category":"win","dtz":5,"moves":[{"uci":"e1e2","category":"loss","dtz":-4,"zeroing":false}]}"""

  private val lossJson =
    """{"category":"loss","dtz":-30,"moves":[{"uci":"e8d8","category":"win","dtz":29,"zeroing":false}]}"""

  private val drawJson =
    """{"category":"draw","dtz":0,"moves":[{"uci":"a1a2","category":"draw","dtz":0,"zeroing":false}]}"""

  private val cursedWinJson =
    """{"category":"cursed-win","dtz":80,"moves":[{"uci":"b1c2","category":"blessed-loss","dtz":-79,"zeroing":false}]}"""

  private val blessedLossJson =
    """{"category":"blessed-loss","dtz":-90,"moves":[{"uci":"h7h8","category":"cursed-win","dtz":89,"zeroing":false}]}"""

  private val unknownCategoryJson =
    """{"category":"unknown","moves":[{"uci":"a1a2"}]}"""

  private val emptyMovesJson =
    """{"category":"win","dtz":1,"moves":[]}"""

  def spec = suite("TablebaseClient")(

    suite("parseResponse")(
      test("win maps to positive score and prefers shortest mate via |dtz|") {
        val r = TablebaseClient.parseResponse(winJson)
        assertTrue(r == Some(TablebaseResult("e1e2", 20000 - 5)))
      },
      test("loss maps to negative score that improves with longer resistance") {
        val r = TablebaseClient.parseResponse(lossJson)
        // |dtz|=30, longer resistance → less-negative
        assertTrue(r == Some(TablebaseResult("e8d8", -20000 + 30)))
      },
      test("draw maps to 0") {
        val r = TablebaseClient.parseResponse(drawJson)
        assertTrue(r == Some(TablebaseResult("a1a2", 0)))
      },
      test("cursed-win maps to +5000") {
        val r = TablebaseClient.parseResponse(cursedWinJson)
        assertTrue(r == Some(TablebaseResult("b1c2", 5000)))
      },
      test("blessed-loss maps to -5000") {
        val r = TablebaseClient.parseResponse(blessedLossJson)
        assertTrue(r == Some(TablebaseResult("h7h8", -5000)))
      },
      test("unknown category returns None") {
        assertTrue(TablebaseClient.parseResponse(unknownCategoryJson).isEmpty)
      },
      test("empty moves array returns None") {
        assertTrue(TablebaseClient.parseResponse(emptyMovesJson).isEmpty)
      },
      test("malformed JSON returns None") {
        assertTrue(TablebaseClient.parseResponse("totally not json").isEmpty)
      },
      test("JSON without a category returns None") {
        assertTrue(TablebaseClient.parseResponse("""{"moves":[{"uci":"e2e4"}]}""").isEmpty)
      },
    ),

    suite("noop client")(
      test("probe always returns None regardless of piece count") {
        for
          a <- TablebaseClient.noop.probe("anything", 3)
          b <- TablebaseClient.noop.probe("anything", 32)
        yield assertTrue(a.isEmpty && b.isEmpty)
      },
    ),
  )
