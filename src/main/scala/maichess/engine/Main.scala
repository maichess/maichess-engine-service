package maichess.engine

import io.grpc.{Server, ServerBuilder, ServerInterceptor}
import io.grpc.stub.StreamObserver
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes
import scala.concurrent.Future
import zio.{Duration, Runtime, Schedule, Unsafe, ZIO, ZIOAppDefault}
import maichess.engine.chess.{Position, Search}
import maichess.engine.grpc.BotsServiceImpl
import maichess.engine.kafka.{AnalysisCommandStream, EngineCommandStream, EngineStream}
import maichess.engine.service.{EngineService, EngineServiceLive}
import maichess.engine.service.clients.TablebaseClientLive
import maichess.engine.v1.bots.bots.{
  AnalysisUpdate    => ProtoAnalysisUpdate,
  AnalyzePositionRequest,
  BotsGrpc,
  GetBestMoveRequest,
  GetBestMoveResponse,
  ListBotsRequest,
  ListBotsResponse,
}

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
object Main extends ZIOAppDefault:

  private val port: Int =
    sys.env.get("GRPC_PORT").flatMap(_.toIntOption).getOrElse(50056)

  private val otlpEndpoint: String =
    sys.env.getOrElse("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317")

  // The stream processors are opt-in (KAFKA_ENABLED=true in staging); off in prod
  // where Kafka is not deployed, so the service runs as a pure gRPC query server.
  private val kafkaEnabled: Boolean =
    sys.env.get("KAFKA_ENABLED").exists(_.equalsIgnoreCase("true"))

  private val kafkaBootstrap: List[String] =
    sys.env.getOrElse("KAFKA_BOOTSTRAP", "kafka:9092").split(',').map(_.trim).toList

  // Each stream processor retries forever so a transient broker outage never takes
  // down the gRPC query path; restarted independently of the other.
  private def streamWork(
    name: String,
    work: ZIO[EngineService, Throwable, Unit],
  ): ZIO[EngineService, Nothing, Unit] =
    work
      .tapErrorCause(cause => ZIO.logErrorCause(s"$name failed; retrying", cause))
      .retry(Schedule.spaced(Duration.fromSeconds(5)))
      .ignore

  // Runs concurrently with the gRPC server: the native bot-move processor
  // (match.events), the external-game bot loop (engine.commands → engine.events,
  // driving the tournament-bridge), and the analysis processor (analysis.commands →
  // analysis.events). ZIO.never when Kafka is disabled (prod), so the service runs as
  // a pure gRPC server.
  private val kafkaWork: ZIO[EngineService, Nothing, Unit] =
    if kafkaEnabled then
      streamWork("engine bot-move stream", EngineStream.run(kafkaBootstrap))
        .zipPar(streamWork("engine command stream", EngineCommandStream.run(kafkaBootstrap)))
        .zipPar(streamWork("engine analysis stream", AnalysisCommandStream.run(kafkaBootstrap)))
        .unit
    else ZIO.never

  // Force one-time engine initialisation (magic-bitboard / attack-table generation)
  // and JIT warm-up of the search hot path before the server accepts traffic, so
  // the first GetBestMove request doesn't pay that cost mid-request.
  private def warmUpEngine(): Unit =
    Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      .foreach(pos => new Search().bestMove(pos, 50L))

  private def buildTracerProvider(): SdkTracerProvider =
    val resource = Resource.getDefault().merge(
      Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, "engine-service")))
    val exporter = OtlpGrpcSpanExporter.builder().setEndpoint(otlpEndpoint).build()
    SdkTracerProvider.builder()
      .setResource(resource)
      .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
      .build()

  private def buildGrpcTelemetry(tracerProvider: SdkTracerProvider): GrpcTelemetry =
    val otel = OpenTelemetrySdk.builder()
      .setTracerProvider(tracerProvider)
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .buildAndRegisterGlobal()
    GrpcTelemetry.create(otel)

  def run =
    ZIO.attempt(warmUpEngine()).orDie *>
    ZIO.acquireReleaseWith(
      ZIO.attempt(buildTracerProvider())
    )(tp => ZIO.attempt(tp.close()).orDie) { tracerProvider =>
      val grpcTelemetry = buildGrpcTelemetry(tracerProvider)
      ZIO.serviceWithZIO[BotsServiceImpl] { svc =>
        ZIO.runtime[Any].flatMap { runtime =>
          ZIO.acquireReleaseWith(
            ZIO.attempt(startServer(svc, runtime, grpcTelemetry.newServerInterceptor()))
          )(s => ZIO.attempt(s.shutdown()).orDie) { _ =>
            ZIO.logInfo(s"gRPC server listening on port $port") *> kafkaWork
          }
        }
      }.provide(BotsServiceImpl.layer, EngineServiceLive.layer, TablebaseClientLive.layer)
    }

  private def startServer(
    svc: BotsServiceImpl,
    runtime: Runtime[Any],
    interceptor: ServerInterceptor,
  ): Server =
    ServerBuilder
      .forPort(port)
      .addService(
        BotsGrpc.bindService(
          new BotsAdapter(svc, runtime),
          scala.concurrent.ExecutionContext.global,
        )
      )
      .intercept(interceptor)
      .build()
      .start()

  private final class BotsAdapter(svc: BotsServiceImpl, runtime: Runtime[Any])
      extends BotsGrpc.Bots:

    private def run[A](effect: zio.IO[Throwable, A]): Future[A] =
      Unsafe.unsafe { implicit u => runtime.unsafe.runToFuture(effect) }

    def getBestMove(request: GetBestMoveRequest): Future[GetBestMoveResponse] =
      run(svc.getBestMove(request).mapError(_.asException()))

    def listBots(request: ListBotsRequest): Future[ListBotsResponse] =
      run(svc.listBots(request))

    def analyzePosition(request: AnalyzePositionRequest, observer: StreamObserver[ProtoAnalysisUpdate]): Unit =
      Unsafe.unsafe { implicit u =>
        runtime.unsafe.fork(
          svc.analyzePosition(request)
            .mapError(_.asException())
            .tap(update => ZIO.attempt(observer.onNext(update)).orDie)
            .runDrain
            .ensuring(ZIO.attempt(observer.onCompleted()).orDie)
            .catchAll(err => ZIO.attempt(observer.onError(err)).orDie)
        )
      }
      ()
