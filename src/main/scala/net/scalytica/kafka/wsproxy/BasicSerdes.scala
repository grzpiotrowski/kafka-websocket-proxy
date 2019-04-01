package net.scalytica.kafka.wsproxy

import java.util.{Map => JMap}

import io.circe.parser._
import io.circe.{Json, Printer}
import org.apache.kafka.common.serialization.{
  Deserializer,
  Serde,
  Serializer,
  Serdes => KSerdes
}

object BasicSerdes {

  implicit val EmptySerializer   = EmptySerde.serializer()
  implicit val EmptyDeserializer = EmptySerde.deserializer()

  implicit val StringSerializer   = KSerdes.String().serializer()
  implicit val StringDeserializer = KSerdes.String().deserializer()

  implicit val BytesSerializer   = KSerdes.Bytes().serializer()
  implicit val BytesDeserializer = KSerdes.Bytes().deserializer()

  implicit val ByteArrSerializer   = KSerdes.ByteArray().serializer()
  implicit val ByteArrDeserializer = KSerdes.ByteArray().deserializer()

  implicit val ByteBufferSerializer   = KSerdes.ByteBuffer().serializer()
  implicit val ByteBufferDeserializer = KSerdes.ByteBuffer().deserializer()

  implicit val UuidSerializer   = KSerdes.UUID().serializer()
  implicit val UuidDeserializer = KSerdes.UUID().deserializer()

  implicit val IntSerializer =
    KSerdes.Integer().serializer().asInstanceOf[Serializer[Int]]
  implicit val IntDeserializer =
    KSerdes.Integer().deserializer().asInstanceOf[Deserializer[Int]]

  implicit val ShortSerializer =
    KSerdes.Short().serializer().asInstanceOf[Serializer[Short]]
  implicit val ShortDeserializer =
    KSerdes.Short().deserializer().asInstanceOf[Deserializer[Short]]

  implicit val LongSerializer =
    KSerdes.Long().serializer().asInstanceOf[Serializer[Long]]
  implicit val LongDeserializer =
    KSerdes.Long().deserializer().asInstanceOf[Deserializer[Long]]

  implicit val DoubleSerializer =
    KSerdes.Double().serializer().asInstanceOf[Serializer[Double]]
  implicit val DoubleDeserializer =
    KSerdes.Double().deserializer().asInstanceOf[Deserializer[Double]]

  implicit val FloatSerializer =
    KSerdes.Float().serializer().asInstanceOf[Serializer[Float]]
  implicit val FloatDeserializer =
    KSerdes.Float().deserializer().asInstanceOf[Deserializer[Float]]

  implicit val JsonSerializer   = JsonSerde.serializer()
  implicit val JsonDeserializer = JsonSerde.deserializer()

}

object EmptySerde
    extends Serde[Unit]
    with Serializer[Unit]
    with Deserializer[Unit] {

  override def serializer() = this

  override def deserializer() = this

  override def configure(configs: JMap[String, _], isKey: Boolean): Unit = {}

  override def serialize(topic: String, data: Unit) = Array.emptyByteArray

  override def deserialize(topic: String, data: Array[Byte]): Unit = ()

  override def close(): Unit = {}
}

object JsonSerde
    extends Serde[Json]
    with Serializer[Json]
    with Deserializer[Json] {

  private[this] val underlying = KSerdes.String()

  override def serializer()   = this
  override def deserializer() = this

  override def configure(configs: JMap[String, _], isKey: Boolean): Unit = {}

  override def serialize(topic: String, data: Json) =
    underlying.serializer().serialize(topic, data.pretty(Printer.noSpaces))

  override def deserialize(topic: String, data: Array[Byte]) = {
    val str = underlying.deserializer().deserialize(topic, data)
    parse(str) match {
      case Right(json) => json
      case Left(err)   => throw err.underlying
    }
  }

  override def close(): Unit = {}
}