package uk.gov.hmrc.customs.notification.controllers

import play.api.http.DefaultHttpErrorHandler
import play.api.http.Status._
import play.api.mvc.{RequestHeader, Result}
import play.api.routing.Router
import play.api.{Configuration, Environment, OptionalSourceMapper}
import uk.gov.hmrc.customs.notification.models.LogContext
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableHeaders
import uk.gov.hmrc.customs.notification.util.Logger

import javax.inject.{Inject, Provider}
import scala.concurrent.Future


// Referenced in play.http.errorHandler
private class ErrorHandler @Inject()(environment: Environment, configuration: Configuration,
                                     sourceMapper: OptionalSourceMapper, router: Provider[Router])
  extends DefaultHttpErrorHandler(environment, configuration, sourceMapper, router) with Logger {

  override def onBadRequest(request: RequestHeader, error: String): Future[Result] =
    Future.successful(Responses.BadRequest("Request body does not contain well-formed XML."))

  override def onNotFound(request: RequestHeader, message: String): Future[Result] =
    Future.successful(Responses.NotFound())

  override def onOtherClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    onBadRequest(request, message)

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    logger.error(s"HTTP error: $statusCode $message")(LogContext(request.headers))
    statusCode match {
      case BAD_REQUEST => onBadRequest(request, message)
      case NOT_FOUND => onNotFound(request, message)
      case UNSUPPORTED_MEDIA_TYPE =>
        Future.successful(Responses.UnsupportedMediaType("The Content-Type header is missing or invalid."))
      case NOT_ACCEPTABLE =>
        Future.successful(Responses.NotAcceptable("The Accept header is missing or invalid."))
      case _ => onOtherClientError(request, statusCode, message)
    }
  }
}
