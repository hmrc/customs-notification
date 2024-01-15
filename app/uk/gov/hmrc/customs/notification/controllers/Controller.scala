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

import play.api.mvc.{Action, AnyContent, ControllerComponents, Headers, Result, Results as PlayResults}
import uk.gov.hmrc.customs.notification.config.BasicAuthConfig
import uk.gov.hmrc.customs.notification.controllers.Results.*
import uk.gov.hmrc.customs.notification.controllers.errors.ValidationError.*
import uk.gov.hmrc.customs.notification.controllers.errors.{SubmitValidationError, Validation}
import uk.gov.hmrc.customs.notification.models.{LogContext, NotificationId}
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.{loggableHeaders, loggableRequestMetadata}
import uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository
import uk.gov.hmrc.customs.notification.services.*
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, InternalServiceError}
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.customs.notification.util.FutureEither.Ops.*
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class Controller @Inject()()(implicit
                             cc: ControllerComponents,
                             incomingNotificationService: IncomingNotificationService,
                             dateTimeService: DateTimeService,
                             hcService: HeaderCarrierService,
                             uuidService: UuidService,
                             csidTranslationHotfixService: CsidTranslationHotfixService,
                             basicAuthConfig: BasicAuthConfig,
                             blockedCsidRepo: BlockedCsidRepository) extends BackendController(cc) with Logger with Validation {
  override val controllerComponents: ControllerComponents = cc
  implicit val ec: ExecutionContext = cc.executionContext

  def submit(): Action[NodeSeq] = {
    Action.async(parse.xml) { request =>
      implicit val h: Headers = request.headers
      implicit val lc: LogContext = LogContext(request.headers)

      (for {
        _ <- authorise(basicAuthConfig.token).toFutureEither
        _ <- validateAcceptHeader.toFutureEither
        notificationId = NotificationId(uuidService.randomUuid())
        now = dateTimeService.now()
        rm <- validateRequestMetadata(request.body, notificationId, now).toFutureEither
      } yield (request.body, rm)).value.flatMap {
        case Left(error) =>
          logger.error(error.errorMessage)
          Future.successful {
            handleValidationError(error)
          }
        case Right((xml, rm)) =>
          implicit val hc: HeaderCarrier = hcService.hcFrom(request)
          implicit val lc: LogContext = LogContext(rm)

          incomingNotificationService.process(xml, rm).map {
            case Left(error) =>
              handleProcessingError(error)
            case Right(_) =>
              PlayResults.Accepted
          }
      }
    }
  }

  private def handleValidationError(error: SubmitValidationError): Result = error match {
    case e: InvalidBasicAuth =>
      Results.Unauthorised(e.responseMessage)
    case e: InvalidAccept =>
      Results.NotAcceptable(e.responseMessage)
    case InvalidHeaders(headerErrors) =>
      // Pick first error to keep in line with original short-circuiting behaviour
      Results.BadRequest(headerErrors.head.responseMessage)
  }

  private def handleProcessingError(error: IncomingNotificationService.Error): Result = error match {
    case DeclarantNotFound =>
      Results.BadRequest(s"The $X_CLIENT_SUB_ID_HEADER_NAME header is invalid.")
    case InternalServiceError =>
      Results.InternalServerError()
  }

  def blockedCount(): Action[AnyContent] = Action.async { request =>
    implicit val lc: LogContext = LogContext(request.headers)

    getClientId(request.headers) match {
      case Left(MissingClientId) =>
        logger.error(s"$X_CLIENT_ID_HEADER_NAME header missing when calling blocked-count endpoint")
        Future.successful(Results.BadRequest(MissingClientId.responseMessage))
      case Right(clientId) =>
        blockedCsidRepo.getFailedAndBlockedCount(clientId).map {
          case Left(mongoDbError) =>
            logger.error(mongoDbError.message)
            Results.InternalServerError()
          case Right(count) =>
            blockedCountOkResponseFrom(count)
        }
    }
  }

  def deleteBlocked(): Action[AnyContent] = Action.async { request =>
    implicit val lc: LogContext = LogContext(request.headers)

    getClientId(request.headers) match {
      case Left(MissingClientId) =>
        logger.error(s"$X_CLIENT_ID_HEADER_NAME header missing when calling delete blocked-flag endpoint")
        Future.successful(Results.BadRequest(MissingClientId.responseMessage))
      case Right(clientId) =>
        blockedCsidRepo.unblockCsid(clientId).map {
          case Left(mongoDbError) =>
            logger.error(mongoDbError.message)
            Results.InternalServerError()
          case Right(count) =>
            if (count > 0) {
              logger.info(s"$count FailedAndBlocked notifications set to FailedButNotBlocked")
              PlayResults.NoContent
            } else {
              logger.info("No FailedAndBlocked notifications found to unblock")
              Results.NotFound()
            }
        }
    }
  }
}
