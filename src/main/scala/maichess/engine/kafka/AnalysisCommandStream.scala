package maichess.engine.kafka

import maichess.engine.service.EngineService
import maichess.events.v1.analysis_commands.AnalysisCommand
import maichess.events.v1.analysis_events.AnalysisEvent
import org.apache.kafka.clients.producer.ProducerRecord
import zio.*
import zio.kafka.consumer.{CommittableRecord, Consumer, ConsumerSettings, OffsetBatch, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.stream.ZStream

// I/O wiring for the engine analysis stream processor. Excluded from coverage and
// mutation like the other live-Kafka shells: the session/cancel decision logic
// lives in the pure, fully-tested AnalysisProcessor; this file only moves bytes.
// It consumes analysis.commands.v1 and hands each AnalysisCommand to the processor,
// whose forked runs produce AnalysisDepthCompleted / AnalysisCompleted /
// AnalysisFailed back to analysis.events.v1 via the injected `emit` sink.
//
// Unlike the bot-move stream (1:1 consume→produce in the chunk), analysis is
// consume-a-command → emit-many-events-later from background fibers, so the
// producer is captured in the sink rather than driven inline. Command offsets are
// committed once handed off; a redelivered Start/Stop is harmless (it restarts /
// re-stops the same session) and a crash simply drops in-flight analysis, which
// the client recreates.
object AnalysisCommandStream:

  val CommandsTopic = "analysis.commands.v1"
  val EventsTopic   = "analysis.events.v1"
  val GroupId       = "engine-analysis"

  private val commandSerde: Serde[Any, AnalysisCommand] = ProtobufEventSerdes.serde(AnalysisCommand)
  private val eventSerde: Serde[Any, AnalysisEvent]     = ProtobufEventSerdes.serde(AnalysisEvent)

  def run(bootstrap: List[String]): ZIO[EngineService, Throwable, Unit] =
    ZIO.scoped(make(bootstrap).flatMap(_.runDrain))

  private def make(
      bootstrap: List[String],
  ): ZIO[EngineService & Scope, Throwable, ZStream[Any, Throwable, Unit]] =
    for
      engine     <- ZIO.service[EngineService]
      state      <- Ref.Synchronized.make(Map.empty[String, AnalysisProcessor.Running])
      sequence   <- Ref.make(Map.empty[String, Long])
      generation <- Ref.make(0L)
      consumer   <- Consumer.make(consumerSettings(bootstrap))
      producer   <- Producer.make(producerSettings(bootstrap))
      emit        = emitTo(producer)
      processor   = AnalysisProcessor(engine, state, sequence, generation, emit)
    yield stream(consumer, processor)

  private def emitTo(producer: Producer)(event: AnalysisEvent): UIO[Unit] =
    producer
      .produce(new ProducerRecord(EventsTopic, event.aggregateId, event), Serde.string, eventSerde)
      .unit
      .catchAllCause(cause => ZIO.logErrorCause("analysis event produce failed", cause))

  private def consumerSettings(bootstrap: List[String]): ConsumerSettings =
    ConsumerSettings(bootstrap).withGroupId(GroupId)

  private def producerSettings(bootstrap: List[String]): ProducerSettings =
    ProducerSettings(bootstrap)

  private def stream(consumer: Consumer, processor: AnalysisProcessor): ZStream[Any, Throwable, Unit] =
    consumer
      .plainStream(Subscription.topics(CommandsTopic), Serde.string, commandSerde)
      .mapChunksZIO(handleAndCommit(processor))

  private def handleAndCommit(processor: AnalysisProcessor)(
      chunk: Chunk[CommittableRecord[String, AnalysisCommand]],
  ): ZIO[Any, Throwable, Chunk[Unit]] =
    for
      _ <- ZIO.foreachDiscard(chunk)(record => processor.handle(record.value))
      _ <- OffsetBatch(chunk.map(_.offset)).commit
    yield Chunk.empty[Unit]
