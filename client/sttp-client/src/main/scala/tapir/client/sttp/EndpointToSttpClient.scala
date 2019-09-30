package tapir.client.sttp

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

import sttp.client._
import sttp.model.{HeaderNames, Uri, Part => SttpPart}
import tapir.Codec.PlainCodec
import tapir._
import tapir.internal._
import tapir.model.{Method, MultiQueryParams, Part}

class EndpointToSttpClient(clientOptions: SttpClientOptions) {
  def toSttpRequest[I, E, O, S](e: Endpoint[I, E, O, S], baseUri: Uri): I => Request[Either[E, O], S] = { params =>
    val (uri, req1) =
      setInputParams(e.input.asVectorOfSingleInputs, paramsTupleToParams(params), 0, baseUri, basicRequest.asInstanceOf[PartialAnyRequest])

    val req2 = req1.copy[Identity, Any, Any](method = sttp.model.Method(e.input.method.getOrElse(Method.GET).m), uri = uri)

    val responseAs = fromMetadata { meta =>
      val output = if (meta.isSuccess) e.output else e.errorOutput
      if (output == EndpointOutput.Void()) {
        throw new IllegalStateException(s"Got response: $meta, cannot map to a void output of: $e.")
      }

      responseAsFromOutputs(meta, output)
    }.mapWithMetadata { (body, meta) =>
      val output = if (meta.isSuccess) e.output else e.errorOutput
      val params = getOutputParams(output.asVectorOfSingleOutputs, body, meta)
      if (meta.isSuccess) Right(params) else Left(params)
    }

    req2.response(responseAs).asInstanceOf[Request[Either[E, O], S]]
  }

  private def getOutputParams(outputs: Vector[EndpointOutput.Single[_]], body: Any, meta: ResponseMetadata): Any = {
    val values = outputs
      .flatMap {
        case EndpointIO.Body(codec, _) =>
          val so = if (codec.meta.isOptional && body == "") None else Some(body)
          Some(getOrThrow(codec.rawDecode(so)))

        case EndpointIO.StreamBodyWrapper(_) =>
          Some(body)

        case EndpointIO.Header(name, codec, _) =>
          Some(getOrThrow(codec.rawDecode(meta.headers(name).toList)))

        case EndpointIO.Headers(_) =>
          Some(meta.headers.map(h => (h.name, h.value)))

        case EndpointIO.Mapped(wrapped, f, _) =>
          Some(f.asInstanceOf[Any => Any].apply(getOutputParams(wrapped.asVectorOfSingleOutputs, body, meta)))

        case EndpointOutput.StatusCode() =>
          Some(meta.code.code)

        case EndpointOutput.FixedStatusCode(_, _) =>
          None
        case EndpointIO.FixedHeader(_, _, _) =>
          None

        case EndpointOutput.OneOf(mappings) =>
          val mapping = mappings
            .find(mapping => mapping.statusCode.isEmpty || mapping.statusCode.contains(meta.code.code))
            .getOrElse(throw new IllegalArgumentException(s"Cannot find mapping for status code ${meta.code} in outputs $outputs"))
          Some(getOutputParams(mapping.output.asVectorOfSingleOutputs, body, meta))

        case EndpointOutput.Mapped(wrapped, f, _) =>
          Some(f.asInstanceOf[Any => Any].apply(getOutputParams(wrapped.asVectorOfSingleOutputs, body, meta)))
      }

    SeqToParams(values)
  }

  private type PartialAnyRequest = PartialRequest[Any, Any]

  private def paramsTupleToParams[I](params: I): Vector[Any] = ParamsToSeq(params).toVector

  private def setInputParams[I](
      inputs: Vector[EndpointInput.Single[_]],
      params: Vector[Any],
      paramIndex: Int,
      uri: Uri,
      req: PartialAnyRequest
  ): (Uri, PartialAnyRequest) = {
    def handleMapped[II, T](
        wrapped: EndpointInput[II],
        g: T => II,
        tail: Vector[EndpointInput.Single[_]]
    ): (Uri, PartialAnyRequest) = {
      val (uri2, req2) = setInputParams(
        wrapped.asVectorOfSingleInputs,
        paramsTupleToParams(g(params(paramIndex).asInstanceOf[T])),
        0,
        uri,
        req
      )

      setInputParams(tail, params, paramIndex + 1, uri2, req2)
    }

    inputs match {
      case Vector() => (uri, req)
      case EndpointInput.FixedMethod(_) +: tail =>
        setInputParams(tail, params, paramIndex, uri, req)
      case EndpointInput.FixedPath(p) +: tail =>
        setInputParams(tail, params, paramIndex, uri.copy(path = uri.path :+ p), req)
      case EndpointInput.PathCapture(codec, _, _) +: tail =>
        val v = codec.asInstanceOf[PlainCodec[Any]].encode(params(paramIndex): Any)
        setInputParams(tail, params, paramIndex + 1, uri.copy(path = uri.path :+ v), req)
      case EndpointInput.PathsCapture(_) +: tail =>
        val ps = params(paramIndex).asInstanceOf[Seq[String]]
        setInputParams(tail, params, paramIndex + 1, uri.copy(path = uri.path ++ ps), req)
      case EndpointInput.Query(name, codec, _) +: tail =>
        val uri2 = codec
          .encode(params(paramIndex))
          .foldLeft(uri) { case (u, v) => u.param(name, v) }
        setInputParams(tail, params, paramIndex + 1, uri2, req)
      case EndpointInput.Cookie(name, codec, _) +: tail =>
        val req2 = codec
          .encode(params(paramIndex))
          .foldLeft(req) { case (r, v) => r.cookie(name, v) }
        setInputParams(tail, params, paramIndex + 1, uri, req2)
      case EndpointInput.QueryParams(_) +: tail =>
        val mqp = params(paramIndex).asInstanceOf[MultiQueryParams]
        val uri2 = uri.params(mqp.toSeq: _*)
        setInputParams(tail, params, paramIndex + 1, uri2, req)
      case EndpointIO.Body(codec, _) +: tail =>
        val req2 = setBody(params(paramIndex), codec, req)
        setInputParams(tail, params, paramIndex + 1, uri, req2)
      case EndpointIO.StreamBodyWrapper(_) +: tail =>
        val req2 = req.streamBody(params(paramIndex))
        setInputParams(tail, params, paramIndex + 1, uri, req2)
      case EndpointIO.Header(name, codec, _) +: tail =>
        val req2 = codec
          .encode(params(paramIndex))
          .foldLeft(req) { case (r, v) => r.header(name, v) }
        setInputParams(tail, params, paramIndex + 1, uri, req2)
      case EndpointIO.Headers(_) +: tail =>
        val headers = params(paramIndex).asInstanceOf[Seq[(String, String)]]
        val req2 = headers.foldLeft(req) {
          case (r, (k, v)) =>
            val replaceExisting = HeaderNames.ContentType.equalsIgnoreCase(k) || HeaderNames.ContentLength.equalsIgnoreCase(k)
            r.header(k, v, replaceExisting)
        }
        setInputParams(tail, params, paramIndex + 1, uri, req2)
      case EndpointIO.FixedHeader(name, value, _) +: tail =>
        val req2 = Seq(value)
          .foldLeft(req) { case (r, v) => r.header(name, v) }
        setInputParams(tail, params, paramIndex, uri, req2)
      case EndpointInput.ExtractFromRequest(_) +: tail =>
        // ignoring
        setInputParams(tail, params, paramIndex + 1, uri, req)
      case (a: EndpointInput.Auth[_]) +: tail =>
        setInputParams(a.input +: tail, params, paramIndex, uri, req)
      case EndpointInput.Mapped(wrapped, _, g) +: tail =>
        handleMapped(wrapped, g, tail)
      case EndpointIO.Mapped(wrapped, _, g) +: tail =>
        handleMapped(wrapped, g, tail)
    }
  }

  private def setBody[T, M <: MediaType, R](v: T, codec: CodecForOptional[T, M, R], req: PartialAnyRequest): PartialAnyRequest = {
    codec
      .encode(v)
      .map { t =>
        val req2 = codec.meta.rawValueType match {
          case StringValueType(charset) => req.body(t, charset.name())
          case ByteArrayValueType       => req.body(t)
          case ByteBufferValueType      => req.body(t)
          case InputStreamValueType     => req.body(t)
          case FileValueType            => req.body(t)
          case mvt: MultipartValueType =>
            val parts: Seq[SttpPart[BasicRequestBody]] = (t: Seq[RawPart]).flatMap { p =>
              mvt.partCodecMeta(p.name).map { partCodecMeta =>
                val sttpPart1 = partToSttpPart(p.asInstanceOf[Part[Any]], partCodecMeta.asInstanceOf[CodecMeta[_, _, Any]])
                val sttpPart2 = sttpPart1.contentType(partCodecMeta.mediaType.mediaTypeNoParams)
                val sttpPart3 = p.headers.foldLeft(sttpPart2) {
                  case (sp, (hk, hv)) =>
                    if (hk.equalsIgnoreCase(HeaderNames.ContentType)) {
                      sp.contentType(hv)
                    } else {
                      sp.header(hk, hv)
                    }
                }
                p.fileName.map(sttpPart3.fileName).getOrElse(sttpPart3)
              }
            }

            req.multipartBody(parts.toList)
        }

        req2.header(HeaderNames.ContentType, codec.meta.mediaType.mediaType, replaceExisting = false)
      }
      .getOrElse(req)
  }

  private def partToSttpPart[R](p: Part[R], codecMeta: CodecMeta[_, _, R]): SttpPart[BasicRequestBody] = codecMeta.rawValueType match {
    case StringValueType(charset) => multipart(p.name, p.body, charset.toString)
    case ByteArrayValueType       => multipart(p.name, p.body)
    case ByteBufferValueType      => multipart(p.name, p.body)
    case InputStreamValueType     => multipart(p.name, p.body)
    case FileValueType            => multipartFile(p.name, p.body)
    case MultipartValueType(_, _) => throw new IllegalArgumentException("Nested multipart bodies aren't supported")
  }

  private def responseAsFromOutputs(meta: ResponseMetadata, out: EndpointOutput[_]): ResponseAs[Any, Any] = {
    if (bodyIsStream(out)) asStreamAlways[Any]
    else {
      out.bodyType
        .map {
          case StringValueType(charset) => asStringAlways(charset.name())
          case ByteArrayValueType       => asByteArrayAlways
          case ByteBufferValueType      => asByteArrayAlways.map(ByteBuffer.wrap)
          case InputStreamValueType     => asByteArrayAlways.map(new ByteArrayInputStream(_))
          case FileValueType            => asFileAlways(clientOptions.createFile(meta))
          case MultipartValueType(_, _) => throw new IllegalArgumentException("Multipart bodies aren't supported in responses")
        }
        .getOrElse(ignore)
    }.asInstanceOf[ResponseAs[Any, Any]]
  }

  private def bodyIsStream[I](out: EndpointOutput[I]): Boolean = {
    out match {
      case _: EndpointIO.StreamBodyWrapper[_, _] => true
      case EndpointIO.Multiple(inputs)           => inputs.exists(i => bodyIsStream(i))
      case EndpointOutput.Multiple(inputs)       => inputs.exists(i => bodyIsStream(i))
      case EndpointIO.Mapped(wrapped, _, _)      => bodyIsStream(wrapped)
      case EndpointOutput.Mapped(wrapped, _, _)  => bodyIsStream(wrapped)
      case _                                     => false
    }
  }

  private def getOrThrow[T](dr: DecodeResult[T]): T = dr match {
    case DecodeResult.Value(v)    => v
    case DecodeResult.Error(o, e) => throw new IllegalArgumentException(s"Cannot decode from $o", e)
    case f                        => throw new IllegalArgumentException(s"Cannot decode: $f")
  }
}
