package maichess.engine.service.clients

import zio.*

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration

// Live network-bound implementation of `TablebaseClient`. Excluded from
// coverage because the HTTP code path requires a live remote service and is
// not exercised by unit tests; correctness is owned by `parseResponse` (which
// lives in the trait companion and IS unit-tested).
final class TablebaseClientLive(httpClient: HttpClient) extends TablebaseClient:

  private val Endpoint       = "https://tablebase.lichess.ovh/standard"
  private val TimeoutSeconds = 2L

  def probe(fen: String, pieceCount: Int): UIO[Option[TablebaseResult]] =
    if pieceCount > TablebaseClient.MaxPieces then ZIO.none
    else
      val url = s"$Endpoint?fen=${URLEncoder.encode(fen, StandardCharsets.UTF_8)}"
      val req = HttpRequest.newBuilder(URI.create(url))
        .timeout(JDuration.ofSeconds(TimeoutSeconds))
        .GET()
        .build()
      ZIO
        .attemptBlocking {
          val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
          if resp.statusCode() == 200 then TablebaseClient.parseResponse(resp.body()) else None
        }
        .timeout(zio.Duration.fromSeconds(TimeoutSeconds))
        .catchAll(_ => ZIO.none)
        .map(_.flatten)

object TablebaseClientLive:
  def make(): TablebaseClientLive =
    new TablebaseClientLive(HttpClient.newHttpClient())

  val layer: ULayer[TablebaseClient] = ZLayer.succeed[TablebaseClient](make())
