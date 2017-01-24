package spinoco.fs2.http

import java.net.InetSocketAddress

import fs2.util.Async
import fs2.{Stream, _}
import scodec.bits.ByteVector
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.protocol.http.{HostPort, HttpScheme}
import spinoco.protocol.http.header.{HttpHeader, `Transfer-Encoding`}

import scala.reflect.ClassTag


package object internal {

  val `\r\n`: ByteVector = ByteVector('\r','\n')

  val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact

  /**
    * Converts Stream of chunks of bytes to stream of byteVector
    */
  def chunk2ByteVector(chunk: Chunk[Byte]): ByteVector = {
    chunk match  {
      case bv: ByteVectorChunk => bv.toByteVector
      case other =>
        val bs = other.toBytes
        ByteVector(bs.values, bs.offset, bs.size)
    }
  }

  /** yields to true, if chunked encoding header is present **/
  def bodyIsChunked(headers:List[HttpHeader]):Boolean = {
    headers.exists {
      case `Transfer-Encoding`(encodings) => encodings.exists(_.equalsIgnoreCase("chunked"))
      case _ => false
    }
  }

  /**
    * From the stream of bytes this extracts Http Header and body part.
    */
  def httpHeaderAndBody[F[_]](maxHeaderSize: Int): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    def go(self:Stream[F,Byte], buff: ByteVector): Stream[F, (ByteVector, Stream[F, Byte])] = {
      self.uncons.flatMap {
        case None => Stream.fail(new Throwable(s"Incomplete Header received: ${buff.decodeUtf8}"))
        case Some((chunk, next)) =>
          val bv = chunk2ByteVector(chunk)
          val all = buff ++ bv
          val idx = all.indexOfSlice(`\r\n\r\n`)
          if (idx < 0) {
            if (all.size > maxHeaderSize) Stream.fail(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else go(next, all)
          }
          else {
            val (h, t) = all.splitAt(idx)
            if (h.size > maxHeaderSize)  Stream.fail(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else Stream.emit((h, Stream.chunk(ByteVectorChunk(t.drop(`\r\n\r\n`.size))) ++ next))
          }
      }
    }

    go(_, ByteVector.empty)
  }


  /** evaluates address from the host port and scheme **/
  def addressForRequest[F[_]](scheme: HttpScheme.Value, host: HostPort)(implicit F: Async[F]):F[InetSocketAddress] = F.delay {
    val port = host.port.getOrElse {
      scheme match {
        case HttpScheme.HTTPS | HttpScheme.WSS => 443
        case HttpScheme.HTTP | HttpScheme.WS => 80
      }
    }

    new InetSocketAddress(host.host, port)
  }

  /** swaps header `H` for new value. If header exists, it is discarded. Appends header to the end**/
  def swapHeader[H <: HttpHeader](header: H)(headers: List[HttpHeader])(implicit CT: ClassTag[H]) : List[HttpHeader] = {
    headers.filterNot(CT.runtimeClass.isInstance) :+ header
  }

}