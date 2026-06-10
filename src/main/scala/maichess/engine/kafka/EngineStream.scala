package maichess.engine.kafka

import maichess.engine.service.EngineService
import maichess.events.v1.match_events.MatchEvent
import org.apache.kafka.clients.producer.ProducerRecord
import zio.*
import zio.kafka.consumer.{CommittableRecord, Consumer, ConsumerSettings, OffsetBatch, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.stream.ZStream

// I/O wiring for the engine bot-move stream processor. Excluded from coverage and
// mutation, like the platform's other live-Kafka shells: the decision + dedupe logic
// lives in the pure, fully-tested BotMoveProcessor; this file only moves bytes.
//
// It consumes match.events.v1, runs the processor on each BotMoveRequested, and
// produces the resulting BotMoveCalculated back to match.events.v1. Unlike the
// move-validator (which wraps its consume->produce in a Kafka transaction for
// effectively-once), the engine relies on the processor's request_id dedupe for
// idempotency: calculation is nondeterministic, so reprocessing is unsafe regardless
// of transaction boundaries. Offsets are committed after the produce, so at-least-once
// redelivery is bounded by the seen-set.
object EngineStream:

  val Topic   = "match.events.v1"
  val GroupId = "engine"

  private val valueSerde: Serde[Any, MatchEvent] = ProtobufEventSerdes.serde(MatchEvent)

  def run(bootstrap: List[String]): ZIO[EngineService, Throwable, Unit] =
    ZIO.scoped(make(bootstrap).flatMap(_.runDrain))

  private def make(
      bootstrap: List[String],
  ): ZIO[EngineService & Scope, Throwable, ZStream[Any, Throwable, Unit]] =
    for
      engine    <- ZIO.service[EngineService]
      processor <- BotMoveProcessor.make(engine)
      consumer  <- Consumer.make(consumerSettings(bootstrap))
      producer  <- Producer.make(ProducerSettings(bootstrap))
    yield stream(consumer, producer, processor)

  private def consumerSettings(bootstrap: List[String]): ConsumerSettings =
    ConsumerSettings(bootstrap)
      .withGroupId(GroupId)
      .withRebalanceSafeCommits(true)
      .withMaxRebalanceDuration(30.seconds)

  private def stream(
      consumer: Consumer,
      producer: Producer,
      processor: BotMoveProcessor,
  ): ZStream[Any, Throwable, Unit] =
    consumer
      .plainStream(Subscription.topics(Topic), Serde.string, valueSerde)
      .mapChunksZIO(process(producer, processor))

  private def process(producer: Producer, processor: BotMoveProcessor)(
      chunk: Chunk[CommittableRecord[String, MatchEvent]],
  ): ZIO[Any, Throwable, Chunk[Unit]] =
    for
      outputs <- ZIO.foreach(chunk)(toOutput(processor))
      _       <- ZIO.foreachDiscard(outputs.flatten)(producer.produce(_, Serde.string, valueSerde))
      _       <- OffsetBatch(chunk.map(_.offset)).commit
    yield Chunk.empty[Unit]

  private def toOutput(processor: BotMoveProcessor)(
      record: CommittableRecord[String, MatchEvent],
  ): UIO[Option[ProducerRecord[String, MatchEvent]]] =
    for
      eventId    <- Random.nextUUID.map(_.toString)
      occurredAt <- Clock.instant.map(_.toEpochMilli)
      result     <- processor.handle(record.value, eventId, occurredAt)
    yield result.map(event => new ProducerRecord(Topic, record.key, event))
