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
import zio.{Runtime, Unsafe, ZIO, ZIOAppDefault}
import maichess.engine.grpc.BotsServiceImpl
import maichess.engine.service.EngineServiceLive
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
    ZIO.acquireReleaseWith(
      ZIO.attempt(buildTracerProvider())
    )(tp => ZIO.attempt(tp.close()).orDie) { tracerProvider =>
      val grpcTelemetry = buildGrpcTelemetry(tracerProvider)
      ZIO.serviceWithZIO[BotsServiceImpl] { svc =>
        ZIO.runtime[Any].flatMap { runtime =>
          ZIO.acquireReleaseWith(
            ZIO.attempt(startServer(svc, runtime, grpcTelemetry.newServerInterceptor()))
          )(s => ZIO.attempt(s.shutdown()).orDie) { _ =>
            ZIO.logInfo(s"gRPC server listening on port $port") *> ZIO.never
          }
        }
      }.provide(BotsServiceImpl.layer, EngineServiceLive.layer)
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
