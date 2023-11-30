/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.notification.controllers

import play.api.mvc._
import uk.gov.hmrc.customs.notification.config.BasicAuthConfig
import uk.gov.hmrc.customs.notification.controllers.ValidationError._
import uk.gov.hmrc.customs.notification.controllers.Responses._
import uk.gov.hmrc.customs.notification.controllers.Validation._
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableHeaders
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, InternalServiceError}
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.FutureEither.Implicits._
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Controller @Inject()()(implicit
                             cc: ControllerComponents,
                             incomingNotificationService: IncomingNotificationService,
                             dateTimeService: DateTimeService,
                             hcService: HeaderCarrierService,
                             newNotificationIdService: NotificationIdService,
                             csidTranslationHotfixService: CsidTranslationHotfixService,
                             basicAuthConfig: BasicAuthConfig,
                             repo: Repository,
                             logger: Logger) extends BackendController(cc) {
  override val controllerComponents: ControllerComponents = cc
  implicit val ec: ExecutionContext = cc.executionContext

  def submit(): Action[AnyContent] = {
    Action.async { request =>
      implicit val h: Headers = request.headers

      (for {
        _ <- authorize(basicAuthConfig.token).toFutureEither
        _ <- validateAcceptHeader.toFutureEither
        _ <- validateContentTypeHeader.toFutureEither
        xml <- validateXml(request).toFutureEither
        rm <- validateRequestMetadata(xml, newNotificationIdService.newId(), dateTimeService.now()).toFutureEither
      } yield (xml, rm)).value.flatMap({
        case Left(error) =>
          Future.successful(handleValidationError(error))
        case Right((xml, rm)) =>
          incomingNotificationService.process(xml)(rm, hcService.hcFrom(request)).map {
            case Left(error) =>
              handleProcessingError(error)
            case Right(_) =>
              Results.Accepted
          }
      })
    }
  }

  private def handleValidationError(error: SubmitValidationError): Result = error match {
    case e: InvalidBasicAuth =>
      Responses.Unauthorised(e.responseMessage)
    case e: InvalidContentType =>
      Responses.UnsupportedMediaType(e.responseMessage)
    case e: InvalidAccept =>
      Responses.NotAcceptable(e.responseMessage)
    case InvalidHeaders(headerErrors) =>
      // Pick first error to keep in line with original short-circuiting behaviour
      Responses.BadRequest(headerErrors.head.responseMessage)
    case BadlyFormedXml =>
      Responses.BadRequest(BadlyFormedXml.message)
  }

  private def handleProcessingError(error: IncomingNotificationService.Error): Result = error match {
    case DeclarantNotFound =>
      Responses.BadRequest(s"The $X_CLIENT_SUB_ID_HEADER_NAME header is invalid")
    case InternalServiceError =>
      Responses.InternalServerError()
  }

  def blockedCount(): Action[AnyContent] = Action.async { request =>
    getClientId(request.headers) match {
      case Left(MissingClientId) =>
        logger.error(s"$X_CLIENT_ID_HEADER_NAME header missing when calling blocked-count endpoint", request.headers)
        Future.successful(Responses.BadRequest(MissingClientId.responseMessage))
      case Right(clientId) =>
        repo.getFailedAndBlockedCount(clientId).map {
          case Left(mongoDbError) =>
            logger.error(mongoDbError.message, request.headers)
            Responses.InternalServerError()
          case Right(count) =>
            blockedCountOkResponseFrom(count)
        }
    }
  }

  def deleteBlocked(): Action[AnyContent] = Action.async { request =>
    getClientId(request.headers) match {
      case Left(MissingClientId) =>
        logger.error(s"$X_CLIENT_ID_HEADER_NAME header missing when calling delete blocked-flag endpoint", request.headers)
        Future.successful(Responses.BadRequest(MissingClientId.responseMessage))
      case Right(clientId) =>
        repo.unblockFailedAndBlocked(clientId).map {
          case Left(mongoDbError) =>
            logger.error(mongoDbError.message, request.headers)
            Responses.InternalServerError()
          case Right(count) =>
            logger.info(s"$count FailedAndBlocked notifications set to FailedButNotBlocked", request.headers)
            Results.NoContent
        }
    }
  }
}
