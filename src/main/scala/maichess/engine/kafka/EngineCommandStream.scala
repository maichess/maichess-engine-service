package maichess.engine.kafka

import maichess.engine.service.EngineService
import maichess.events.v1.match_events.MatchEvent
import org.apache.kafka.clients.producer.ProducerRecord
import zio.*
import zio.kafka.consumer.{CommittableRecord, Consumer, ConsumerSettings, OffsetBatch, Subscription}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.stream.ZStream

// I/O wiring for the engine's external-game bot-move loop. Excluded from coverage
// and mutation, like the platform's other live-Kafka shells: the decision + dedupe
// logic lives in the pure, fully-tested BotMoveProcessor; this file only moves bytes.
//
// It is the request/reply twin of EngineStream. Where EngineStream serves the native
// match loop (consume BotMoveRequested from match.events.v1, reply on the same topic),
// this serves the tournament-bridge: it consumes BotMoveRequested from a DEDICATED
// engine.commands.v1 topic (keyed by request_id) and produces the BotMoveCalculated to
// engine.events.v1. The bridge correlates the reply by request_id. The dedicated pair
// keeps external-game requests off match.events.v1, whose projector would otherwise
// turn them into phantom live matches (Kafka task 09).
//
// Idempotency relies on BotMoveProcessor's request_id seen-set (calculation is
// nondeterministic, so reprocessing is unsafe); offsets commit after the produce, so
// at-least-once redelivery is bounded by the seen-set.
object EngineCommandStream:

  val CommandTopic = "engine.commands.v1"
  val EventTopic   = "engine.events.v1"
  val GroupId      = "engine-commands"

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
      .plainStream(Subscription.topics(CommandTopic), Serde.string, valueSerde)
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
    yield result.map(event => new ProducerRecord(EventTopic, record.key, event))
