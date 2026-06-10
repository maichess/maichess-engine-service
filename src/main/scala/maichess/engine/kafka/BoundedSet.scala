package maichess.engine.kafka

// Bounded FIFO set of request ids the engine has already answered. Bot-move
// search is nondeterministic, so a redelivered BotMoveRequested must not be
// recomputed (it would yield a different move). The oldest ids are evicted past
// `capacity`; the bound caps memory at the cost of dropping dedupe for very old
// requests, which by then are long committed (the in-memory guard only has to
// cover the at-least-once redelivery window).
final case class BoundedSet private (
    capacity: Int,
    order: Vector[String],
    members: Set[String],
):
  def contains(id: String): Boolean = members.contains(id)

  def add(id: String): BoundedSet =
    if members.contains(id) then this
    else
      val grown = order :+ id
      if grown.size > capacity then
        val trimmed = grown.drop(grown.size - capacity)
        BoundedSet(capacity, trimmed, trimmed.toSet)
      else BoundedSet(capacity, grown, members + id)

object BoundedSet:
  def empty(capacity: Int): BoundedSet = BoundedSet(capacity, Vector.empty, Set.empty)
