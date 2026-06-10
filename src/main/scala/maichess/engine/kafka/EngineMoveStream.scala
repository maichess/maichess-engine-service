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
// mutation, like the other live-Kafka shells in the platform: the decision and
// dedupe logic lives in the pure, fully-tested BotMoveProcessor / BoundedSet;
// this file only moves bytes. It consumes match.events.v1, runs the processor on
// each BotMoveRequested, and produces the resulting BotMoveCalculated back to
// match.events.v1.
//
// Unlike the move-validator (which is pure and uses a Kafka transaction for
// effectively-once), the engine is nondeterministic and instead guards on
// request_id (BoundedSet). Delivery is at-least-once: produce, then commit the
// offset batch; a crash between the two redelivers the request, and the dedupe
// drops it. Records the engine ignores still have their offset committed so the
// consumer advances without re-reading them.
object EngineMoveStream:

  val Topic   = "match.events.v1"
  val GroupId = "engine"

  // The in-memory dedupe guard only spans the redelivery window, so a few
  // thousand recent request ids is ample; downstream (the projector) dedupes too.
  private val SeenCapacity = 10_000

  private val valueSerde: Serde[Any, MatchEvent] = ProtobufEventSerdes.serde(MatchEvent)

  def run(bootstrap: List[String]): ZIO[EngineService, Throwable, Unit] =
    ZIO.scoped(make(bootstrap).flatMap(_.runDrain))

  private def make(
      bootstrap: List[String],
  ): ZIO[EngineService & Scope, Throwable, ZStream[Any, Throwable, Unit]] =
    for
      engine   <- ZIO.service[EngineService]
      seen     <- Ref.make(BoundedSet.empty(SeenCapacity))
      consumer <- Consumer.make(consumerSettings(bootstrap))
      producer <- Producer.make(producerSettings(bootstrap))
    yield stream(consumer, producer, BotMoveProcessor(engine, seen))

  private def consumerSettings(bootstrap: List[String]): ConsumerSettings =
    ConsumerSettings(bootstrap).withGroupId(GroupId)

  private def producerSettings(bootstrap: List[String]): ProducerSettings =
    ProducerSettings(bootstrap)

  private def stream(
      consumer: Consumer,
      producer: Producer,
      processor: BotMoveProcessor,
  ): ZStream[Any, Throwable, Unit] =
    consumer
      .plainStream(Subscription.topics(Topic), Serde.string, valueSerde)
      .mapChunksZIO(produceAndCommit(producer, processor))

  private def produceAndCommit(producer: Producer, processor: BotMoveProcessor)(
      chunk: Chunk[CommittableRecord[String, MatchEvent]],
  ): ZIO[Any, Throwable, Chunk[Unit]] =
    for
      outputs <- ZIO.foreach(chunk)(toOutput(processor))
      records  = outputs.flatten
      _       <- producer.produceChunk(records, Serde.string, valueSerde).when(records.nonEmpty)
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
