package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.Position
import maichess.engine.service.SearchV5
import maichess.engine.service.clients.{TablebaseClient, TablebaseResult}

object SearchV5Spec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val kqkFen   = "8/8/8/4k3/8/3K4/8/3Q4 w - - 0 1"
  private val mateFen  = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
  private val noKingFen = "k7/8/8/8/8/8/8/8 w - - 0 1"

  // Records the piece count the client was probed with — lets the test verify
  // SearchV5 computes and forwards it correctly.
  private final class RecordingStub(result: Option[TablebaseResult]) extends TablebaseClient:
    val observed: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(-1)
    def probe(fen: String, pieceCount: Int): UIO[Option[TablebaseResult]] =
      observed.set(pieceCount)
      ZIO.succeed(result)

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))

  def spec = suite("SearchV5")(

    test("uses tablebase result when client returns Some") {
      val expected = TablebaseResult("e2e4", 19990)
      val stub     = new RecordingStub(Some(expected))
      val search   = new SearchV5(stub)
      for
        pos    <- fen(startFen)
        result <- search.bestMove(pos, startFen, 100L)
      yield assertTrue(result == ("e2e4", 19990))
    },

    test("falls through to opening book / search when tablebase returns None") {
      val stub   = new RecordingStub(None)
      val search = new SearchV5(stub)
      for
        pos    <- fen(startFen)
        result <- search.bestMove(pos, startFen, 100L)
      yield
        val (mv, _) = result
        assertTrue(mv.length >= 4 && mv.length <= 5)
    },

    test("forwards the correct piece count to the client") {
      val stub   = new RecordingStub(None)
      val search = new SearchV5(stub)
      for
        pos <- fen(kqkFen)
        _   <- search.bestMove(pos, kqkFen, 100L)
      yield assertTrue(stub.observed.get == 3)
    },

    test("propagates 'No legal moves' when search reports Move.None on a mated position") {
      val stub   = new RecordingStub(None)
      val search = new SearchV5(stub)
      for
        pos    <- fen(mateFen)
        result <- search.bestMove(pos, mateFen, 100L).exit
      yield assertTrue(result.isFailure)
    },

    test("propagates 'Search failed' when inner search throws on a degenerate position") {
      val stub   = new RecordingStub(None)
      val search = new SearchV5(stub)
      for
        pos    <- fen(noKingFen)
        result <- search.bestMove(pos, noKingFen, 100L).exit
      yield assertTrue(result.isFailure)
    },
  )
