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
import play.api.http.HeaderNames.{ACCEPT, AUTHORIZATION}
import play.api.mvc._
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableHeaders
import uk.gov.hmrc.customs.notification.models._
import ValidationError._
import play.api.{Configuration, Environment, OptionalSourceMapper}
import play.api.http.DefaultHttpErrorHandler
import play.api.routing.Router
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util.Helpers.ignore
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.Authorization

import java.time.ZonedDateTime
import java.util.{Locale, UUID}
import javax.inject.{Inject, Provider}
import scala.concurrent.Future
import scala.xml.NodeSeq

private object Validation {
  //scalastyle:off method.length
  def validateRequestMetadata(xml: NodeSeq,
                              notificationId: NotificationId,
                              startTime: ZonedDateTime)
                             (implicit h: Headers,
                              csidTranslationHotfixService: CsidTranslationHotfixService,
                              logger: Logger): Either[InvalidHeaders, RequestMetadata] = {
    val UuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    val CorrelationIdRegex = "^.{1,36}$"

    val validateClientSubId =
      validateMandatory(
        X_CLIENT_SUB_ID_HEADER_NAME, _.matches(UuidRegex),
        str => csidTranslationHotfixService.translate(ClientSubscriptionId(UUID.fromString(str))),
        InvalidClientSubId)

    val validateConversationId =
      validateMandatory(
        X_CONVERSATION_ID_HEADER_NAME, _.matches(UuidRegex),
        id => ConversationId(UUID.fromString(id)),
        InvalidConversationId)

    val validateCorrelationId =
      validateOptional(
        X_CORRELATION_ID_HEADER_NAME, _.matches(CorrelationIdRegex),
        Header.forCorrelationId,
        InvalidCorrelationId)

    val maybeBadgeId = h.get(X_BADGE_ID_HEADER_NAME).map(Header.forBadgeId)
    val maybeSubmitterId = h.get(X_SUBMITTER_ID_HEADER_NAME).map(Header.forSubmitterId)
    val maybeFunctionCode = extractValues(xml \ "Response" \ "FunctionCode").map(FunctionCode)
    val maybeIssueDateTime = extractValues(xml \ "Response" \ "IssueDateTime" \ "DateTimeString")
      .fold(h.get(ISSUE_DATE_TIME_HEADER_NAME))(Some.apply)
      .map(Header.forIssueDateTime)
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
          maybeSubmitterId,
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

  def getClientId(headers: Headers): Either[MissingClientId.type, ClientId] =
    validateMandatory(X_CLIENT_ID_HEADER_NAME, _.nonEmpty, ClientId.apply, _ => MissingClientId)(headers)

  def authorize(authToken: Authorization)(implicit h: Headers, logger: Logger): Either[InvalidBasicAuth, Unit] =
    validateMandatory(AUTHORIZATION, _.contains(authToken.value), ignore, { errorType =>
      val error = InvalidBasicAuth(errorType)
      logger.error(error.responseMessage, h)
      error
    })

  def validateAcceptHeader(implicit h: Headers, logger: Logger): Either[InvalidAccept, Unit] =
    validateMandatory(ACCEPT, _.contains(MimeTypes.XML), ignore, { errorType =>
      val error = InvalidAccept(errorType)
      logger.error(error.responseMessage, h)
      error
    })

  def validateContentTypeHeader(implicit h: Headers, logger: Logger): Either[InvalidContentType, Unit] = {
    val isValidContentType: String => Boolean = value => {
      val tl = value.toLowerCase(Locale.ENGLISH)
      tl.startsWith("text/xml") ||
        tl.startsWith("application/xml") ||
        tl.matches("^application/vnd\\.hmrc\\..*\\+xml$")
    }

    validateMandatory(ACCEPT, isValidContentType, ignore, { errorType =>
      val error = InvalidContentType(errorType)
      logger.error(error.responseMessage, h)
      error
    })
  }

  def validateXml(request: Request[AnyContent])
                 (implicit headers: Headers, logger: Logger): Either[BadlyFormedXml.type, NodeSeq] = {
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

  private def validateMandatory[E, A](headerName: String, predicate: String => Boolean,
                                      mapValue: String => A,
                                      mapError: HeaderErrorType => E)
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
                                     predicate: String => Boolean,
                                     mapValue: String => A,
                                     error: => E)(implicit headers: Headers): Either[E, Option[A]] =
    validateMandatory(headerName, predicate, mapValue, identity) match {
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
}

private class ErrorHandler @Inject()(environment: Environment, configuration: Configuration,
                                                   sourceMapper: OptionalSourceMapper, router: Provider[Router])
  extends DefaultHttpErrorHandler(environment, configuration, sourceMapper, router){
  override protected def onBadRequest(request: RequestHeader, error: String): Future[Result] =
    Future.successful(Responses.BadRequest())

  override protected def onNotFound(request: RequestHeader, message: String): Future[Result] =
    Future.successful(Responses.NotFound())
}
