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

import cats.implicits._
import play.api.http.ContentTypes
import play.api.http.HeaderNames.{ACCEPT, AUTHORIZATION}
import play.api.mvc.Results.Ok
import play.api.mvc._
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse._
import uk.gov.hmrc.customs.notification.config.BasicAuthTokenConfig
import uk.gov.hmrc.customs.notification.controllers.CustomsNotificationController._
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableHeaders
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.errors.ControllerError._
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, InternalServiceError}
import uk.gov.hmrc.customs.notification.services.{DateTimeService, IncomingNotificationService, UuidService}
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.repo.NotificationRepo.MongoDbError
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.ZonedDateTime
import java.util.{Locale, UUID}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationController @Inject()()(implicit
                                              cc: ControllerComponents,
                                              incomingNotificationService: IncomingNotificationService,
                                              dateTimeService: DateTimeService,
                                              uuidService: UuidService,
                                              basicAuthTokenConfig: BasicAuthTokenConfig,
                                              workItemRepo: NotificationRepo,
                                              logger: NotificationLogger,
                                              ec: ExecutionContext) extends BackendController(cc) {
  override val controllerComponents: ControllerComponents = cc

  def submit(): Action[AnyContent] = Action.async { request =>
    implicit val h: Headers = request.headers
    (for {
      _ <- authorize(basicAuthTokenConfig).toFutureCdsResult
      _ <- validateAcceptHeader.toFutureCdsResult
      _ <- validateContentTypeHeader.toFutureCdsResult
      xml <- validateXml(request).toFutureCdsResult
      startTime = dateTimeService.now()
      notificationId = NotificationId(uuidService.getRandomUuid())
      requestMetadata <- validateRequestMetadata(xml, notificationId, startTime).toFutureCdsResult
      _ <- FutureCdsResult(incomingNotificationService.process(xml)(requestMetadata, hc(request)))
    } yield ()).value.map({
      case Right(_) =>
        Results.Accepted
      case Left(error) =>
        handleSubmitError(error)
    })
  }

  private def handleSubmitError(error: CdsError)(implicit headers: Headers): Result = error match {
    case e: InvalidBasicAuth =>
      ErrorResponse(UNAUTHORIZED, UnauthorizedCode, e.responseMessage).XmlResult
    case e: InvalidContentType =>
      ErrorResponse(UNSUPPORTED_MEDIA_TYPE, UnsupportedMediaTypeCode, e.responseMessage).XmlResult
    case e: InvalidAccept =>
      ErrorResponse(NOT_ACCEPTABLE, NotAcceptableCode, e.responseMessage).XmlResult
    case InvalidHeaders(headerErrors) =>
      val responseMessage = headerErrors.map(_.responseMessage).toList.mkString("\n")
      ErrorResponse(BAD_REQUEST, BadRequestCode, responseMessage).XmlResult
    case BadlyFormedXml =>
      ErrorResponse(BAD_REQUEST, BadRequestCode, BadlyFormedXml.message).XmlResult
    case DeclarantNotFound =>
      ErrorResponse(BAD_REQUEST, BadRequestCode, s"The $X_CLIENT_SUB_ID_HEADER_NAME header is invalid").XmlResult
    case InternalServiceError =>
      ErrorInternalServerError.XmlResult
    case e =>
      logger.error(s"Unhandled error: $e", headers)
      ErrorInternalServerError.XmlResult
  }

  def blockedCount(): Action[AnyContent] = Action.async { request =>
    (for {
      clientId <- getClientId(request.headers).toFutureCdsResult
      count <- FutureCdsResult(workItemRepo.blockedCount(clientId))
    } yield count).value.map {
      case Right(count) =>
        blockedCountOkResponseFrom(count)
      case Left(MissingClientId) =>
        logger.error(s"$X_CLIENT_ID_HEADER_NAME header missing when calling blocked-count endpoint", request.headers)
        ErrorResponse(BAD_REQUEST, BadRequestCode, MissingClientId.responseMessage).XmlResult
      case Left(mongoDbError: MongoDbError) =>
        logger.error(mongoDbError.message, request.headers)
        ErrorInternalServerError.XmlResult
    }
  }

  def deleteBlocked(): Action[AnyContent] = Action.async { request =>
    getClientId(request.headers) match {
      case Left(MissingClientId) =>
        logger.error(s"$X_CLIENT_ID_HEADER_NAME header missing when calling delete blocked-flag endpoint", request.headers)
        Future.successful(ErrorResponse(BAD_REQUEST, BadRequestCode, MissingClientId.responseMessage).XmlResult)
      case Right(clientId) =>
        workItemRepo.unblockFailedAndBlocked(clientId).map {
          case Left(mongoDbError) =>
            logger.error(mongoDbError.message, request.headers)
            ErrorInternalServerError.XmlResult
          case Right(count) =>
            logger.info(s"$count blocked flags deleted", request.headers)
            Results.NoContent
        }
    }
  }
}

private object CustomsNotificationController {

  private def validateMandatory[E, A](headerName: String, predicate: String => Boolean)
                                     (mapValue: String => A)
                                     (mapError: HeaderErrorType => E)
                                     (implicit headers: Headers): Either[E, A] =
    headers.get(headerName) match {
      case Some(value) =>
        if (predicate(value)) {
          Right(mapValue(value))
        } else {
          Left(mapError(InvalidHeaderValue))
        }
      case None =>
        Left(mapError(MissingHeaderValue))
    }

  private def validateOptional[E, A](headerName: String,
                                     predicate: String => Boolean)
                                    (mapValue: String => A)
                                    (error: => E)(implicit headers: Headers): Either[E, Option[A]] =
    validateMandatory(headerName, predicate)(mapValue)(identity) match {
      case Right(value) => Right(Some(value))
      case Left(MissingHeaderValue) => Right(None)
      case Left(InvalidHeaderValue) => Left(error)
    }

  private def extractValues(xmlNode: NodeSeq): Option[String] = {
    xmlNode.theSeq match {
      case Seq() => None
      case xs => Some {
        xs.collect { case node if node.nonEmpty && xmlNode.text.trim.nonEmpty => node.text.trim }
          .mkString("|")
      }
    }
  }

  private val UuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
  private val CorrelationIdRegex = "^.{1,36}$"

  private def validateRequestMetadata(xml: NodeSeq,
                                      notificationId: NotificationId,
                                      startTime: ZonedDateTime)
                                     (implicit h: Headers,
                                      logger: NotificationLogger): Either[InvalidHeaders, RequestMetadata] = {
    val validateClientSubId = validateMandatory(X_CLIENT_SUB_ID_HEADER_NAME, _.matches(UuidRegex))(
      id => ClientSubscriptionId(UUID.fromString(id))
    )(InvalidClientSubId)

    val validateConversationId = validateMandatory(X_CONVERSATION_ID_HEADER_NAME, _.matches(UuidRegex))(
      id => ConversationId(UUID.fromString(id))
    )(InvalidConversationId)

    val validateCorrelationId = validateOptional(X_CORRELATION_ID_HEADER_NAME, _.matches(CorrelationIdRegex))(
      id => Header(X_CORRELATION_ID_HEADER_NAME, id)
    )(InvalidCorrelationId)

    val maybeBadgeId = h.get(X_BADGE_ID_HEADER_NAME).map(v => Header(X_BADGE_ID_HEADER_NAME, v))
    val maybeSubmitter = h.get(X_SUBMITTER_ID_HEADER_NAME).map(v => Header(X_SUBMITTER_ID_HEADER_NAME, v))
    val maybeFunctionCode = extractValues(xml \ "Response" \ "FunctionCode").map(FunctionCode)
    val maybeIssueDateTime = extractValues(xml \ "Response" \ "IssueDateTime" \ "DateTimeString")
      .fold(h.get(ISSUE_DATE_TIME_HEADER_NAME))(Some.apply)
      .map(value => Header(ISSUE_DATE_TIME_HEADER_NAME, value))
    val maybeMrn = extractValues(xml \ "Response" \ "Declaration" \ "ID").map(Mrn)

    (validateClientSubId.toValidatedNel,
      validateConversationId.toValidatedNel,
      validateCorrelationId.toValidatedNel)
      .mapN { (clientSubscriptionId, conversationId, maybeCorrelationId) =>
        RequestMetadata(
          clientSubscriptionId,
          conversationId,
          notificationId,
          maybeBadgeId,
          maybeSubmitter,
          maybeCorrelationId,
          maybeIssueDateTime,
          maybeFunctionCode,
          maybeMrn,
          startTime)
      }.toEither.leftMap { errors =>
      errors.toList.foreach(e => logger.error(e.responseMessage, h))
      InvalidHeaders(errors)
    }
  }

  private def getClientId(headers: Headers): Either[MissingClientId.type, ClientId] =
    validateMandatory(X_CLIENT_ID_HEADER_NAME, _.nonEmpty)(ClientId.apply)(_ => MissingClientId)(headers)

  private def authorize(basicAuthTokenConfig: BasicAuthTokenConfig)(implicit h: Headers, logger: NotificationLogger): Either[InvalidBasicAuth, Unit] =
    validateMandatory(AUTHORIZATION, _.contains(basicAuthTokenConfig.token))(_ => ()) { errorType =>
      val error = InvalidBasicAuth(errorType)
      logger.error(error.responseMessage, h)
      error
    }

  private def validateAcceptHeader(implicit h: Headers, logger: NotificationLogger): Either[InvalidAccept, Unit] =
    validateMandatory(ACCEPT, _.contains(MimeTypes.XML))(_ => ()) { errorType =>
      val error = InvalidAccept(errorType)
      logger.error(error.responseMessage, h)
      error
    }

  private def validateContentTypeHeader(implicit h: Headers, logger: NotificationLogger): Either[InvalidContentType, Unit] = {
    val isValidContentType: String => Boolean = value => {
      val tl = value.toLowerCase(Locale.ENGLISH)
      tl.startsWith("text/xml") || tl.startsWith("application/xml")
    }

    validateMandatory(ACCEPT, isValidContentType)(_ => ()) { errorType =>
      val error = InvalidContentType(errorType)
      logger.error(error.responseMessage, h)
      error
    }
  }

  private def validateXml(request: Request[AnyContent])
                         (implicit headers: Headers, logger: NotificationLogger): Either[BadlyFormedXml.type, NodeSeq] = {
    val isValidContentType = request.contentType.exists { t =>
      val tl = t.toLowerCase(Locale.ENGLISH)
      tl.startsWith("text/xml") || tl.startsWith("application/xml")
    }

    if (isValidContentType) {
      request.body.asXml match {
        case Some(xml) => Right(xml)
        case None => Left(BadlyFormedXml)
      }
    } else {
      logger.error(BadlyFormedXml.message, headers)
      Left(BadlyFormedXml)
    }
  }

  private def blockedCountOkResponseFrom(count: Int): Result = {
    // @formatter:off
    val countXml = <pushNotificationBlockedCount>{count}</pushNotificationBlockedCount>
    // @formatter:on
    Ok(s"<?xml version='1.0' encoding='UTF-8'?>\n$countXml").as(ContentTypes.XML)
  }
}