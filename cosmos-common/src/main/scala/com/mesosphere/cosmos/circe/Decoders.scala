package com.mesosphere.cosmos.circe

import com.mesosphere.cosmos.error.JsonDecodingError
import com.mesosphere.cosmos.error.JsonParsingError
import com.mesosphere.cosmos.finch.MediaTypedDecoder
import com.mesosphere.cosmos.http.MediaType
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Error
import io.circe.Json
import io.circe.ParsingFailure
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.reflect.ClassTag
import scala.reflect.classTag

object Decoders {

  def decode[T: Decoder: ClassTag](value: String): T = {
    convertToCosmosException(io.circe.jawn.decode[T](value), value)
  }

  def mediaTypedDecode[T: ClassTag](
    value: String,
    mediaType: MediaType
  )(
    implicit decoder: MediaTypedDecoder[T]
  ): T = {
    convertToCosmosException(decoder(parse(value).hcursor, mediaType), value)
  }

  def parse(value: String): Json = {
    convertToCosmosException(io.circe.jawn.parse(value), value)
  }

  def decode64[T: Decoder: ClassTag](value: String): T = {
    decode[T](base64DecodeString(value))
  }

  def parse64(value: String): Json = {
    parse(base64DecodeString(value))
  }

  def convertToCosmosException[T: ClassTag](
    result: Either[Error, T],
    inputValue: String
  ): T = result match {
    case Right(value) => value
    case Left(ParsingFailure(message, underlying)) =>
      throw JsonParsingError(underlying.getClass.getName, message, inputValue).exception
    case Left(DecodingFailure(message, _)) =>
      throw JsonDecodingError(classTag[T].runtimeClass.getName, message, inputValue).exception
  }

  private[this] def base64DecodeString(value: String): String = {
    new String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)
  }
}
