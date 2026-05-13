package maichess.engine.service.clients

import zio.*

import scala.util.matching.Regex

// Endgame tablebase probe result: best UCI move plus a centipawn evaluation
// from the engine's perspective.
final case class TablebaseResult(bestMove: String, evaluationCp: Int)

// Probes an endgame tablebase. Never fails — returns ZIO.none for any
// situation that should fall through to the next search layer:
//   * pieceCount above the supported threshold
//   * network failure or timeout
//   * non-200 HTTP response
//   * malformed JSON
//   * unknown tablebase category
trait TablebaseClient:
  def probe(fen: String, pieceCount: Int): UIO[Option[TablebaseResult]]

object TablebaseClient:
  val MaxPieces = 7

  // Always-empty client used by tests and by any caller that wants the live
  // search path without provisioning a real HTTP backend.
  val noop: TablebaseClient = new TablebaseClient:
    def probe(fen: String, pieceCount: Int): UIO[Option[TablebaseResult]] = ZIO.none

  private val CategoryPattern: Regex = """"category"\s*:\s*"([^"]+)"""".r
  private val DtzPattern:      Regex = """"dtz"\s*:\s*(-?\d+)""".r
  private val UciPattern:      Regex = """"uci"\s*:\s*"([^"]+)"""".r

  // Top-level fields appear before the "moves" array in Lichess responses, so
  // splitting on that key cleanly isolates the position-level category/dtz
  // from the per-move entries.
  def parseResponse(json: String): Option[TablebaseResult] =
    val movesIdx  = json.indexOf("\"moves\"")
    val topLevel  = if movesIdx >= 0 then json.substring(0, movesIdx) else json
    val arrayPart = if movesIdx >= 0 then json.substring(movesIdx) else ""
    for
      category <- CategoryPattern.findFirstMatchIn(topLevel).map(_.group(1))
      bestMove <- UciPattern.findFirstMatchIn(arrayPart).map(_.group(1))
      dtz       = DtzPattern.findFirstMatchIn(topLevel).map(_.group(1).toInt).getOrElse(0)
      score    <- scoreFor(category, dtz)
    yield TablebaseResult(bestMove, score)

  // Centipawn score from engine perspective. dtz in Lichess responses is
  // negative when the side to move is losing; using |dtz| lets the same
  // formula prefer fast wins and slow losses without sign juggling.
  private def scoreFor(category: String, dtz: Int): Option[Int] =
    val abs = math.abs(dtz)
    category match
      case "win"          => Some(20000 - abs)
      case "cursed-win"   => Some(5000)
      case "draw"         => Some(0)
      case "blessed-loss" => Some(-5000)
      case "loss"         => Some(-20000 + abs)
      case _              => None
