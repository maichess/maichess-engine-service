package maichess.engine.service

import zio.{IO, ZIO}
import maichess.engine.chess.{Move, OpeningBook, Position, SearchV4}
import maichess.engine.service.clients.TablebaseClient

// ── SearchV5 — Knowledge tier ────────────────────────────────────────────────
// Wraps SearchV4 with two knowledge databases:
//   1. Endgame tablebase probe (Lichess) — returns provably optimal play in
//      positions with few pieces.
//   2. Polyglot opening book — returns instant theory moves in the opening.
//
// Lives in service/ (not chess/) because it composes ZIO effects and reaches
// out to a network service; the chess/ package is reserved for pure logic.
final class SearchV5(tablebaseClient: TablebaseClient):

  private val inner = new SearchV4()

  def bestMove(pos: Position, fen: String, timeLimitMs: Long): IO[String, (String, Int)] =
    val pieceCount = java.lang.Long.bitCount(pos.occupied)
    for
      tb <- tablebaseClient.probe(fen, pieceCount)
      result <- tb match
        case Some(r) =>
          ZIO.succeed((r.bestMove, r.evaluationCp))
        case None =>
          OpeningBook.probe(pos) match
            case Some(uci) =>
              ZIO.succeed((uci, 0))
            case None =>
              ZIO.attempt(inner.bestMove(pos, timeLimitMs))
                .mapError(e => s"Search failed: ${e.getMessage}")
                .flatMap { case (mv, sc) =>
                  if mv == Move.None then ZIO.fail(s"No legal moves in position: $fen")
                  else                    ZIO.succeed((Move.toUci(mv), sc))
                }
    yield result
