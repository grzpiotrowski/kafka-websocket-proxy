package net.scalytica.kafka.wsproxy.models

import akka.kafka.ConsumerMessage
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroConsumerRecord,
  KafkaMessageHeader
}
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import org.apache.kafka.common.serialization.Serializer

/**
 * ADT describing any record that can be sent out from the service through the
 * WebSocket connection. Basically there are two types of messages; those with
 * a defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records
 * that only contain a value of type {{{V}}}.
 *
 * @param key   The [[OutValueDetails]] describing the message key
 * @param value The [[OutValueDetails]] describing the message value
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
sealed abstract class WsConsumerRecord[+K, +V](
    key: Option[OutValueDetails[K]],
    value: OutValueDetails[V]
) {

  val topic: TopicName
  val partition: Partition
  val offset: Offset
  val timestamp: Timestamp
  val headers: Option[Seq[KafkaHeader]]
  val committableOffset: Option[ConsumerMessage.CommittableOffset]

  lazy val wsProxyMessageId: WsMessageId =
    WsMessageId(topic, partition, offset, timestamp)

  def toAvroRecord[Key >: K, Value >: V](
      implicit keySer: Serializer[Key],
      valSer: Serializer[Value]
  ): AvroConsumerRecord = {
    val k = key.flatMap { det =>
      det.format.flatMap { fmt =>
        val v = det.value.asInstanceOf[fmt.Tpe]
        fmt.toCoproduct(v)
      }
    }
    // FIXME: There's something odd about this code 🤔
    AvroConsumerRecord(
      wsProxyMessageId = wsProxyMessageId.value,
      topic = topic.value,
      partition = partition.value,
      offset = offset.value,
      timestamp = timestamp.value,
      headers = headers.map(_.map(h => KafkaMessageHeader(h.key, h.value))),
      key = k,
      value = valSer.serialize(topic.value, value.value)
    )
  }

  def withKeyFormatType(keyType: Formats.FormatType): WsConsumerRecord[K, V]
  def withValueFormatType(valType: Formats.FormatType): WsConsumerRecord[K, V]
}

object WsConsumerRecord {

  def fromAvro[K](
      avro: AvroConsumerRecord
  )(
      formatType: FormatType
  ): WsConsumerRecord[K, Array[Byte]] = {
    avro.key match {
      case Some(key) =>
        val k: K = formatType.unsafeFromCoproduct[K](key)
        ConsumerKeyValueRecord(
          topic = TopicName(avro.topic),
          partition = Partition(avro.partition),
          offset = Offset(avro.offset),
          timestamp = Timestamp(avro.timestamp),
          headers = avro.headers.map(_.map(KafkaHeader.fromAvro)),
          key = OutValueDetails(k, Option(formatType)),
          value = OutValueDetails(avro.value, Option(Formats.AvroType)),
          committableOffset = None
        )

      case None =>
        ConsumerValueRecord(
          topic = TopicName(avro.topic),
          partition = Partition(avro.partition),
          offset = Offset(avro.offset),
          timestamp = Timestamp(avro.timestamp),
          value = OutValueDetails(avro.value, Option(Formats.AvroType)),
          headers = avro.headers.map(_.map(KafkaHeader.fromAvro)),
          committableOffset = None
        )
    }
  }

}

/**
 * Consumer record type with key and value.
 *
 * @param topic             The topic name the message was consumed from
 * @param partition         The topic partition for the message
 * @param offset            The topic offset of the message
 * @param timestamp         The timestamp for when Kafka received the record
 * @param headers           The [[KafkaHeader]]s found on the message.
 * @param key               The [[OutValueDetails]] for the message key
 * @param value             The [[OutValueDetails]] for the message value
 * @param committableOffset An optional handle to the mechanism that allows
 *                          committing the message offset back to Kafka.
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
case class ConsumerKeyValueRecord[K, V](
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    timestamp: Timestamp,
    headers: Option[Seq[KafkaHeader]],
    key: OutValueDetails[K],
    value: OutValueDetails[V],
    committableOffset: Option[ConsumerMessage.CommittableOffset]
) extends WsConsumerRecord[K, V](Some(key), value) {

  def withKeyFormatType(keyType: Formats.FormatType): WsConsumerRecord[K, V] =
    copy(key = key.copy(format = Option(keyType)))

  def withValueFormatType(valType: Formats.FormatType): WsConsumerRecord[K, V] =
    copy(value = value.copy(format = Option(valType)))

}

/**
 * Consumer record type with value only.
 *
 * @param topic             The topic name the message was consumed from
 * @param partition         The topic partition for the message
 * @param offset            The topic offset of the message
 * @param timestamp         The timestamp for when Kafka received the record
 * @param headers           The [[KafkaHeader]]s found on the message.
 * @param value             The [[OutValueDetails]] for the message value
 * @param committableOffset An optional handle to the mechanism that allows
 *                          committing the message offset back to Kafka.
 * @tparam V the type of the value
 */
case class ConsumerValueRecord[V](
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    timestamp: Timestamp,
    headers: Option[Seq[KafkaHeader]],
    value: OutValueDetails[V],
    committableOffset: Option[ConsumerMessage.CommittableOffset]
) extends WsConsumerRecord[Nothing, V](None, value) {

  def withKeyFormatType(
      keyType: Formats.FormatType
  ): WsConsumerRecord[Nothing, V] = this

  def withValueFormatType(
      valType: Formats.FormatType
  ): WsConsumerRecord[Nothing, V] =
    copy(value = value.copy(format = Option(valType)))
}
