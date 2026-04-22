package maichess.engine

import io.grpc.{Server, ServerBuilder}
import io.grpc.stub.StreamObserver
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

object Main extends ZIOAppDefault:

  private val port: Int =
    sys.env.get("GRPC_PORT").flatMap(_.toIntOption).getOrElse(50056)

  def run =
    ZIO.serviceWithZIO[BotsServiceImpl] { svc =>
      ZIO.runtime[Any].flatMap { runtime =>
        ZIO.acquireReleaseWith(
          ZIO.attempt(startServer(svc, runtime))
        )(s => ZIO.attempt(s.shutdown()).orDie) { _ =>
          ZIO.logInfo(s"gRPC server listening on port $port") *> ZIO.never
        }
      }
    }.provide(BotsServiceImpl.layer, EngineServiceLive.layer)

  private def startServer(svc: BotsServiceImpl, runtime: Runtime[Any]): Server =
    ServerBuilder
      .forPort(port)
      .addService(
        BotsGrpc.bindService(
          new BotsAdapter(svc, runtime),
          scala.concurrent.ExecutionContext.global,
        )
      )
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
