package spinoco.fs2.http.body

import scodec.bits.ByteVector
import scodec.{Attempt, Decoder, Err}
import spinoco.protocol.http.header.value.{ContentType, HttpCharset}


trait BodyDecoder[A] {
  def decode(bytes: ByteVector, contentType: ContentType): Attempt[A]
}


object BodyDecoder {

  def apply[A](f: (ByteVector, ContentType) => Attempt[A]): BodyDecoder[A] =
    new BodyDecoder[A] {
      def decode(bytes: ByteVector, contentType: ContentType): Attempt[A] =
        f(bytes, contentType)
    }

  def forDecoder[A](f: ContentType => Attempt[Decoder[A]]): BodyDecoder[A] =
    BodyDecoder { (bs, ct) => f(ct).flatMap(_.decodeValue(bs.bits)) }

  val stringDecoder: BodyDecoder[String] = BodyDecoder { case (bytes, ct) =>
    if (! ct.mediaType.isText) Attempt.Failure(Err(s"Media Type must be text, but is ${ct.mediaType}"))
    else {
      HttpCharset.asJavaCharset(ct.charset.getOrElse(HttpCharset.`UTF-8`)).flatMap { implicit chs =>
        Attempt.fromEither(bytes.decodeString.left.map(ex => Err(s"Failed to decode string ContentType: $ct, charset: $chs, err: ${ex.getMessage}")))
      }
    }
  }

}
