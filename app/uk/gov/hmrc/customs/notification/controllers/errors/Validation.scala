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

package uk.gov.hmrc.customs.notification.controllers.errors

import cats.implicits.*
import play.api.http.HeaderNames.{ACCEPT, AUTHORIZATION}
import play.api.mvc.*
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.notification.controllers.errors.ValidationError.*
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.services.*
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.Helpers.ignoreResult
import uk.gov.hmrc.http.Authorization

import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.NodeSeq

trait Validation {

  // scalastyle:off method.length
  def validateRequestMetadata(xml: NodeSeq,
                              notificationId: NotificationId,
                              startTime: ZonedDateTime)
                             (implicit headers: Headers,
                              lc: LogContext,
                              csidTranslationHotfixService: CsidTranslationHotfixService): Either[InvalidHeaders, RequestMetadata] = {
    val UuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    val CorrelationIdRegex = "^.{1,36}$"

    val validateCsid =
      validateMandatory(
        headerName = X_CLIENT_SUB_ID_HEADER_NAME,
        predicate = _.matches(UuidRegex),
        mapError = InvalidClientSubId,
        mapValue = { str =>
          () => csidTranslationHotfixService.translate(ClientSubscriptionId(UUID.fromString(str)))
        }
      )

    val validateConversationId =
      validateMandatory(
        headerName = X_CONVERSATION_ID_HEADER_NAME,
        predicate = _.matches(UuidRegex),
        mapError = InvalidConversationId,
        mapValue = id => ConversationId(UUID.fromString(id)))

    val validateCorrelationId =
      validateOptional(
        headerName = X_CORRELATION_ID_HEADER_NAME,
        predicate = _.matches(CorrelationIdRegex),
        returnError = InvalidCorrelationId,
        mapValue = Header.forCorrelationId)

    val maybeBadgeId = headers.get(X_BADGE_ID_HEADER_NAME).map(Header.forBadgeId)
    val maybeSubmitterId = headers.get(X_SUBMITTER_ID_HEADER_NAME).map(Header.forSubmitterId)
    val maybeFunctionCode = extractValues(xml \ "Response" \ "FunctionCode").map(FunctionCode)
    val maybeIssueDateTime = extractValues(xml \ "Response" \ "IssueDateTime" \ "DateTimeString")
      .fold(headers.get(ISSUE_DATE_TIME_HEADER_NAME))(Some.apply)
      .map(Header.forIssueDateTime)
    val maybeMrn = extractValues(xml \ "Response" \ "Declaration" \ "ID").map(Mrn)

    (validateCsid.toValidatedNel,
      validateConversationId.toValidatedNel,
      validateCorrelationId.toValidatedNel)
      .mapN { (getClientSubscriptionId, conversationId, maybeCorrelationId) =>
        RequestMetadata(
          getClientSubscriptionId(),
          conversationId,
          notificationId,
          maybeBadgeId,
          maybeSubmitterId,
          maybeCorrelationId,
          maybeIssueDateTime,
          maybeFunctionCode,
          maybeMrn,
          startTime)
      }
      .toEither
      .leftMap(InvalidHeaders)
  }

  def getClientId(implicit headers: Headers): Either[MissingClientId.type, ClientId] =
    validateMandatory(
      headerName = X_CLIENT_ID_HEADER_NAME,
      predicate = _.nonEmpty,
      mapError = _ => MissingClientId,
      mapValue = ClientId.apply
    )

  def authorise(authToken: Authorization)
               (implicit headers: Headers): Either[InvalidBasicAuth, Unit] =
    validateMandatory(
      headerName = AUTHORIZATION,
      predicate = _.contains(authToken.value),
      mapError = InvalidBasicAuth,
      mapValue = ignoreResult
    )

  def validateAcceptHeader(implicit h: Headers): Either[InvalidAccept, Unit] =
    validateMandatory(
      headerName = ACCEPT,
      predicate = _.contains(MimeTypes.XML),
      mapError = InvalidAccept,
      mapValue = ignoreResult
    )

  private def validateMandatory[E, A](headerName: String,
                                      predicate: String => Boolean,
                                      mapError: HeaderErrorType => E,
                                      mapValue: String => A)
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
                                     returnError: => E,
                                     mapValue: String => A)(implicit headers: Headers): Either[E, Option[A]] =
    validateMandatory(headerName, predicate, identity, mapValue) match {
      case Right(value) => Right(Some(value))
      case Left(MissingHeaderValue) => Right(None)
      case Left(InvalidHeaderValue) => Left(returnError)
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
