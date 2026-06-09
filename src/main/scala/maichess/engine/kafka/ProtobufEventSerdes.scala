package maichess.engine.kafka

import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import zio.ZIO
import zio.kafka.serde.Serde

// zio-kafka Serde over the ScalaPB-generated maichess.events.v1 event types
// (match.events BotMoveRequested/BotMoveCalculated, analysis.commands,
// analysis.events). It encodes/decodes raw Protobuf bytes via the ScalaPB
// companion that already ships with the gRPC stubs in platform-protos — no new
// proto tooling, only zio-kafka.
//
// This replaces the avro4s path noted in CONTRACT_NOTES. The bytes are the
// end-state wire encoding (the Schema Registry is removed in Kafka task 09);
// transitional registry framing, if needed where this engine joins a topic that
// still carries registry-framed messages, is added when the stream processor is
// actually wired (tasks 04 / 07). Task 01 is serde plumbing only — nothing is
// wired to it yet.
object ProtobufEventSerdes:

  /** Serde for a ScalaPB message `A` given its generated companion (e.g.
    * `ProtobufEventSerdes.serde(MatchEvent)`).
    */
  def serde[A <: GeneratedMessage](companion: GeneratedMessageCompanion[A]): Serde[Any, A] =
    Serde.byteArray.inmapM(bytes => ZIO.attempt(companion.parseFrom(bytes)))(msg =>
      ZIO.succeed(msg.toByteArray),
    )
