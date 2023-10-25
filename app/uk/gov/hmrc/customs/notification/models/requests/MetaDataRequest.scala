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

package uk.gov.hmrc.customs.notification.models.requests

import play.api.mvc.Headers
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.util.HeaderNames.{ISSUE_DATE_TIME_HEADER, X_BADGE_ID_HEADER_NAME, X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME, X_SUBMITTER_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.util.Util

import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.NodeSeq

case class MetaDataRequest(clientSubscriptionId: ClientSubscriptionId,
                           conversationId: ConversationId,
                           notificationId: NotificationId,
                           maybeClientId: Option[ClientId],
                           maybeBadgeId: Option[BadgeId],
                           maybeSubmitterNumber: Option[Submitter],
                           maybeCorrelationId: Option[CorrelationId],
                           maybeFunctionCode: Option[FunctionCode],
                           maybeIssueDateTime: Option[IssueDateTime],
                           maybeMrn: Option[Mrn],
                           startTime: ZonedDateTime) extends HasId
                                                     with HasClientSubscriptionId
                                                     with HasNotificationId
                                                     with HasMaybeClientId
                                                     with HasMaybeBadgeId
                                                     with HasMaybeCorrelationId
                                                     with HasMaybeSubmitter
                                                     with HasMaybeFunctionCode
                                                     with HasMaybeIssueDateTime
                                                     with HasMaybeMrn {
  override def idName: String = "conversationId"
  override def idValue: String = conversationId.toString
  def maybeBadgeIdHeader: Option[Header] = asHeader(X_BADGE_ID_HEADER_NAME, maybeBadgeId)
  def maybeSubmitterHeader: Option[Header] = asHeader(X_SUBMITTER_ID_HEADER_NAME, maybeSubmitterNumber)
  def maybeCorrelationIdHeader: Option[Header] = asHeader(X_CORRELATION_ID_HEADER_NAME, maybeCorrelationId)
  def maybeIssueDateTimeHeader: Option[Header] = asHeader(ISSUE_DATE_TIME_HEADER, maybeIssueDateTime)

  private def asHeader[T](name: String, maybeHeaderValue: Option[T]): Option[Header] = {
    maybeHeaderValue.map(v => Header(name = name, value = v.toString))
  }
}

object MetaDataRequest{
  def buildMetaDataRequest(maybeXml: Option[NodeSeq], headers: Headers, startTime: ZonedDateTime): MetaDataRequest = {
    // headers have been validated so safe to do a naked get except badgeId, submitter and correlation id which are optional
    MetaDataRequest(ClientSubscriptionId(UUID.fromString(headers.get(X_CDS_CLIENT_ID_HEADER_NAME).get)),
      ConversationId(UUID.fromString(headers.get(X_CONVERSATION_ID_HEADER_NAME).get)),
      NotificationId(UUID.randomUUID()), None, headers.get(X_BADGE_ID_HEADER_NAME).map(BadgeId),
      headers.get(X_SUBMITTER_ID_HEADER_NAME).map(Submitter), headers.get(X_CORRELATION_ID_HEADER_NAME).map(CorrelationId),
      Util.extractFunctionCode(maybeXml), Util.extractIssueDateTime(maybeXml, headers.get(ISSUE_DATE_TIME_HEADER)), Util.extractMrn(maybeXml), startTime)
  }
}
