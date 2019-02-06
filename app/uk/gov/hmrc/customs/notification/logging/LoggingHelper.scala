/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.logging

import play.api.http.HeaderNames.AUTHORIZATION
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.model.SeqOfHeader

object LoggingHelper {

  private val headerOverwriteValue = "value-not-logged"
  private val headersToOverwrite = Set(AUTHORIZATION)

  def logMsgPrefix(clientSubscriptionId: ClientSubscriptionId, conversationId: ConversationId): String =
    s"[conversationId=$conversationId][clientSubscriptionId=$clientSubscriptionId]"


  def logMsgPrefix(clientNotification: ClientNotification): String =
    s"[conversationId=${clientNotification.notification.conversationId}][clientSubscriptionId=${clientNotification.csid}]"

  def format(msg: String, rm: HasId): String = {
    s"${formatLogPrefix(rm)} $msg"
  }

  def formatDebug(msg: String, maybeUrl: Option[String] = None, maybePayload: Option[String] = None)(implicit rm: HasId): String = {
    val urlPart = maybeUrl.fold("")(url => s" url=$url")
    val payloadPart = maybePayload.fold("")(payload => s"\npayload=\n$payload")
    s"${formatLogPrefix(rm)} $msg$urlPart\n$payloadPart"
  }

  def formatWithHeaders(msg: String, headers: SeqOfHeader): String = {
    s"${formatLogPrefixWithHeaders(headers)} $msg\nheaders=${overwriteHeaderValues(headers,headersToOverwrite - AUTHORIZATION)}"
  }
  private def formatLogPrefixWithHeaders(headers: SeqOfHeader): String = {
    val maybeFieldsId = findHeaderValue(CustomHeaderNames.X_CDS_CLIENT_ID_HEADER_NAME, headers)
    val maybeConversationId = findHeaderValue(CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME, headers)

    maybeConversationId.fold("")(conversationId => s"[conversationId=$conversationId]") +
      maybeFieldsId.fold("")(maybeFieldsId => s"[fieldsId=$maybeFieldsId]")
  }

  private def formatLogPrefix(rm: HasId): String = {
    def fieldsId = rm match {
      case has: HasClientSubscriptionId =>
        s"[fieldsId=${has.clientSubscriptionId}]"
      case _ => ""
    }
    def correlationId = rm match {
      case has: HasMaybeCorrelationId =>
        formatOptional("correlationId", has.maybeCorrelationId)
      case _ => ""
    }
    def badgeId = rm match {
      case has: HasMaybeBadgeId =>
        formatOptional("badgeId", has.mayBeBadgeId)
      case _ => ""
    }
    def eori = rm match {
      case has: HasMaybeEori =>
        formatOptional("eoriIdentifier", has.mayBeEoriNumber)
      case _ => ""
    }

    s"[${rm.idName}=${rm.idValue}]$fieldsId$badgeId$eori$correlationId"
  }

  private def formatOptional[T](name: String, maybeValue: Option[T]) = {
    maybeValue.fold("")(h => s"[$name=${h.toString}]")
  }

  private def findHeaderValue(headerName: String, headers: SeqOfHeader): Option[String] = {
    headers.collectFirst{
        case header if header._1.equalsIgnoreCase(headerName) => header._2
    }
  }

  private def overwriteHeaderValues(headers: SeqOfHeader, overwrittenHeaderNames: Set[String]): SeqOfHeader = {
    headers map {
      case (rewriteHeader, _) if overwrittenHeaderNames.contains(rewriteHeader) => rewriteHeader -> headerOverwriteValue
      case header => header
    }
  }
}
