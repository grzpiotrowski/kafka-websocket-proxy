package net.scalytica.kafka.wsproxy.web

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.Credentials
import akka.stream.Materializer
import akka.util.Timeout
import io.circe.Json
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.kafka.wsproxy.session.{
  SessionHandlerProtocol,
  SessionId,
  SessionOpResult
}
import org.apache.kafka.common.KafkaException

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * The base routing implementation. Defines authentication, error and rejection
 * handling, as well as other shared implementations.
 */
trait BaseRoutes extends QueryParamParsers with WithProxyLogger {

  implicit private val sessionHandlerTimeout: Timeout = 3 seconds

  protected val serverId: WsServerId

  implicit protected def jsonToString(json: Json): String = json.spaces2

  protected def jsonMessageFromString(msg: String): Json =
    Json.obj("message" -> Json.fromString(msg))

  private[this] def jsonResponseMsg(
      statusCode: StatusCode,
      message: String
  ): HttpResponse = {
    HttpResponse(
      status = statusCode,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = jsonMessageFromString(message)
      )
    )
  }

  protected def basicAuthCredentials(
      creds: Credentials
  )(implicit cfg: AppCfg): Option[WsProxyAuthResult] = {
    cfg.server.basicAuth
      .flatMap { bac =>
        for {
          u <- bac.username
          p <- bac.password
        } yield (u, p)
      }
      .map { case (usr, pwd) =>
        creds match {
          case p @ Credentials.Provided(id) // constant time comparison
              if usr.equals(id) && p.verify(pwd) =>
            log.trace("Successfully authenticated bearer token.")
            Some(BasicAuthResult(id))

          case _ =>
            log.info("Could not authenticate basic auth credentials")
            None
        }
      }
      .getOrElse(Some(AuthDisabled))
  }

  protected def openIdAuth(
      creds: Credentials
  )(
      implicit appCfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Future[Option[WsProxyAuthResult]] = {
    log.trace(s"Going to validate openid token $creds")
    implicit val ec = mat.executionContext

    maybeOpenIdClient match {
      case Some(oidcClient) =>
        creds match {
          case Credentials.Provided(token) =>
            val bearerToken = OAuth2BearerToken(token)
            oidcClient.validate(bearerToken).flatMap {
              case Success(jwtClaim) =>
                log.trace("Successfully authenticated bearer token.")
                val jar = JwtAuthResult(bearerToken, jwtClaim)
                if (jar.isValid) Future.successful(Some(jar))
                else Future.successful(None)
              case Failure(err) =>
                err match {
                  case err: ProxyAuthError =>
                    log.info("Could not authenticate bearer token", err)
                    Future.successful(None)
                  case err =>
                    Future.failed(err)
                }
            }
          case _ =>
            log.info("Could not authenticate bearer token")
            Future.successful(None)
        }
      case None =>
        log.info("OpenID Connect is not enabled")
        Future.successful(None)
    }
  }

  protected def maybeAuthenticateOpenId[T](
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Directive1[WsProxyAuthResult] = {
    log.debug("Attempting authentication using openid-connect...")
    cfg.server.openidConnect
      .flatMap { oidcCfg =>
        val realm = oidcCfg.realm.getOrElse("")
        if (oidcCfg.enabled) Option(authenticateOAuth2Async(realm, openIdAuth))
        else None
      }
      .getOrElse {
        log.info("OpenID Connect is not enabled.")
        provide(AuthDisabled)
      }
  }

  protected def maybeAuthenticateBasic[T](
      implicit cfg: AppCfg
  ): Directive1[WsProxyAuthResult] = {
    log.debug("Attempting authentication using basic authentication...")
    cfg.server.basicAuth
      .flatMap { ba =>
        if (ba.enabled)
          ba.realm.map(r => authenticateBasic(r, basicAuthCredentials))
        else None
      }
      .getOrElse {
        log.info("Basic authentication is not enabled.")
        provide(AuthDisabled)
      }
  }

  protected def maybeAuthenticate[T](
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Directive1[WsProxyAuthResult] = {
    if (cfg.server.isOpenIdConnectEnabled) maybeAuthenticateOpenId[T]
    else if (cfg.server.isBasicAuthEnabled) maybeAuthenticateBasic[T]
    else provide(AuthDisabled)
  }

  private[this] def removeClientComplete(
      sid: SessionId,
      fid: FullClientId
  )(tryRes: Try[SessionOpResult]): Unit = {
    val (removingMsg, failMsg) = fid match {
      case FullConsumerId(gid, cid) =>
        val remStr = (msg: String) =>
          s"Removing consumer ${cid.value} from group ${gid.value} " +
            s"in session ${sid.value} on server ${serverId.value} " +
            s"returned: $msg"

        val errStr = "An error occurred when trying to remove consumer" +
          s" ${cid.value} from group ${gid.value} in session ${sid.value} " +
          s"on server ${serverId.value}."

        (remStr, errStr)

      case FullProducerId(pid, iid) =>
        val instStr = iid.map(i => s" instance ${i.value} for").getOrElse("")
        val remStr = (msg: String) =>
          s"Removing$instStr producer ${pid.value} in session ${sid.value} " +
            s"on server ${serverId.value} returned: $msg"

        val errStr = s"An error occurred when trying to remove$instStr" +
          s"producer ${pid.value} in session ${sid.value} " +
          s"on server ${serverId.value}."

        (remStr, errStr)
    }

    tryRes match {
      case Success(res) => log.debug(removingMsg(res.asString))
      case Failure(err) => log.warn(failMsg, err)
    }
  }

  private[this] def rejectRequest(
      request: HttpRequest
  )(
      cleanup: (SessionId, FullClientId) => Unit
  )(c: => ToResponseMarshallable) = {
    paramsOnError(request) { args =>
      extractMaterializer { implicit mat =>
        args match {
          case ConsumerParamError(sid, cid, gid) =>
            val fid = FullConsumerId(gid, cid)
            cleanup(sid, fid)

          case ProducerParamError(sid, pid, ins) =>
            val fid = FullProducerId(pid, ins)
            cleanup(sid, fid)

          case other =>
            log.warn(s"Request rejected with ${other.niceClassSimpleName}")
        }

        request.discardEntityBytes()
        complete(c)
      }
    }
  }

  private[this] def rejectAndComplete(
      m: => ToResponseMarshallable
  )(cleanup: (SessionId, FullClientId) => Unit) = {
    extractRequest { request =>
      log.warn(
        s"Request ${request.method.value} ${request.uri.toString} failed"
      )
      rejectRequest(request)(cleanup)(m)
    }
  }

  private[this] val noOp = (_: SessionId, _: FullClientId) => ()

  private[this] def notAuthenticatedRejection(
      proxyErr: ProxyError,
      clientErr: ClientError
  ) = extractUri { uri =>
    log.info(s"Request to $uri could not be authenticated.", proxyErr)
    val msg = jsonResponseMsg(clientErr, proxyErr.getMessage)
    rejectAndComplete(msg)(noOp)
  }

  private[this] def invalidTokenRejection(tokenErr: InvalidTokenError) =
    extractUri { uri =>
      log.info(s"JWT token in request $uri is not valid.", tokenErr)
      val msg = jsonResponseMsg(Unauthorized, tokenErr.getMessage)
      rejectAndComplete(msg)(noOp)
    }

  private[this] def cleanupClient(
      sid: SessionId,
      fid: FullClientId
  )(
      implicit sh: ActorRef[SessionHandlerProtocol.Protocol],
      mat: Materializer
  ): Unit = {
    implicit val ec        = mat.executionContext
    implicit val scheduler = mat.system.toTyped.scheduler

    fid match {
      case fcid: FullConsumerId =>
        sh.removeConsumer(fcid, serverId)
          .onComplete(removeClientComplete(sid, fid))

      case fpid: FullProducerId =>
        sh.removeProducer(fpid, serverId)
          .onComplete(removeClientComplete(sid, fid))
    }
  }

  // scalastyle:off method.length
  def wsExceptionHandler(
      implicit sh: ActorRef[SessionHandlerProtocol.Protocol],
      mat: Materializer
  ): ExceptionHandler =
    ExceptionHandler {
      case t: TopicNotFoundError =>
        extractUri { uri =>
          log.info(s"Topic in request $uri was not found.", t)
          val msg = jsonResponseMsg(BadRequest, t.message)
          rejectAndComplete(msg)(cleanupClient)
        }

      case r: RequestValidationError =>
        log.info(s"Request failed with RequestValidationError", r)
        val msg = jsonResponseMsg(BadRequest, r.msg)
        rejectAndComplete(msg)(cleanupClient)

      case i: InvalidPublicKeyError =>
        log.warn(s"Request failed with an InvalidPublicKeyError.", i)
        notAuthenticatedRejection(i, Unauthorized)

      case i: InvalidTokenError =>
        log.warn(s"Request failed with an InvalidTokenError.", i)
        invalidTokenRejection(i)

      case a: AuthenticationError =>
        log.warn(s"Request failed with an AuthenticationError.", a)
        notAuthenticatedRejection(a, Unauthorized)

      case a: AuthorisationError =>
        log.warn(s"Request failed with an AuthorizationError.", a)
        notAuthenticatedRejection(a, Forbidden)

      case o: OpenIdConnectError =>
        extractUri { uri =>
          log.warn(s"Request to $uri failed with an OpenIDConnectError.", o)
          val msg = jsonResponseMsg(ServiceUnavailable, o.getMessage)
          rejectAndComplete(msg)(cleanupClient)
        }

      case k: KafkaException =>
        extractUri { uri =>
          log.warn(s"Request to $uri failed with a KafkaException.", k)
          val msg = jsonResponseMsg(InternalServerError, k.getMessage)
          rejectAndComplete(msg)(cleanupClient)
        }

      case t =>
        extractUri { uri =>
          log.warn(s"Request to $uri could not be handled normally", t)
          val msg = jsonResponseMsg(InternalServerError, t.getMessage)
          rejectAndComplete(msg)(cleanupClient)
        }
    }
  // scalastyle:on method.length

  implicit def serverRejectionHandler: RejectionHandler = {
    RejectionHandler
      .newBuilder()
      .handle { case MissingQueryParamRejection(paramName) =>
        rejectAndComplete(
          jsonResponseMsg(
            statusCode = BadRequest,
            message = s"Request is missing required parameter '$paramName'"
          )
        )(noOp)
      }
      .handleNotFound {
        rejectAndComplete(
          jsonResponseMsg(
            statusCode = NotFound,
            message = "This is not the resource you are looking for."
          )
        )(noOp)
      }
      .result()
      .withFallback(RejectionHandler.default)
      .mapRejectionResponse { res =>
        res.entity match {
          case HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, body) =>
            res.withEntity(
              HttpEntity(
                contentType = ContentTypes.`application/json`,
                string = jsonMessageFromString(body.utf8String)
              )
            )

          case _ => res
        }
      }
  }
}