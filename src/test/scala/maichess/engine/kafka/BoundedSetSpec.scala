package maichess.engine.kafka

import zio.test.*

// Covers the bounded FIFO dedupe set: membership, idempotent re-add, and oldest-id
// eviction once capacity is exceeded.
object BoundedSetSpec extends ZIOSpecDefault:

  def spec = suite("BoundedSet")(
    test("empty contains nothing") {
      assertTrue(!BoundedSet.empty(4).contains("a"))
    },
    test("an added id is present") {
      val set = BoundedSet.empty(4).add("a")
      assertTrue(set.contains("a"), set.members.size == 1)
    },
    test("re-adding an existing id is a no-op (no growth, no duplicate)") {
      val once  = BoundedSet.empty(4).add("a")
      val twice = once.add("a")
      assertTrue(twice == once, twice.order == Vector("a"), twice.members.size == 1)
    },
    test("fills up to capacity without eviction") {
      val set = BoundedSet.empty(3).add("a").add("b").add("c")
      assertTrue(set.contains("a"), set.contains("b"), set.contains("c"), set.members.size == 3)
    },
    test("evicts the oldest id once capacity is exceeded") {
      val set = BoundedSet.empty(2).add("a").add("b").add("c")
      assertTrue(
        !set.contains("a"),
        set.contains("b"),
        set.contains("c"),
        set.members.size == 2,
        set.order == Vector("b", "c"),
      )
    },
  )
