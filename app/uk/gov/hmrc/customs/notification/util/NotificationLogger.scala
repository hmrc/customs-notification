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

package uk.gov.hmrc.customs.notification.util

import com.google.inject.Inject
import play.api.http.HeaderNames.AUTHORIZATION
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.util.HeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME}

import javax.inject.Singleton

@Singleton
class NotificationLogger @Inject()(logger: CdsLogger) {

  def debug(msg: => String)(implicit rm: HasId): Unit = {
    logger.debug(format(msg, rm))
  }

  def debug(msg: => String, url: => String)(implicit rm: HasId): Unit = {
    logger.debug(formatDebug(msg, Some(url)))
  }

  def debug(msg: => String, url: => String, payload: => String)(implicit rm: HasId): Unit = {
    logger.debug(formatDebug(msg, Some(url), Some(payload)))
  }

  def debugWithHeaders(msg: => String, headers: => Seq[(String, String)]): Unit = logger.debug(formatWithHeaders(msg, headers))

  def debugWithPrefixedHeaders(msg: => String, headers: => Seq[(String, String)]): Unit = logger.debug(formatWithoutHeaders(msg, headers))

  def info(msg: => String)(implicit rm: HasId): Unit = {
    logger.info(format(msg, rm))
  }

  def warn(msg: => String)(implicit rm: HasId): Unit = {
    logger.warn(format(msg, rm))
  }

  def errorWithHeaders(msg: => String, headers: => Seq[(String, String)]): Unit = logger.error(formatWithHeaders(msg, headers))

  def error(msg: => String)(implicit rm: HasId): Unit = {
    logger.error(format(msg, rm))
  }

  def error(msg: => String, t: => Throwable)(implicit rm: HasId): Unit = {
    logger.error(format(msg, rm), t)
  }

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
    s"${formatLogPrefix(rm)} $msg$urlPart$payloadPart"
  }

  def formatWithHeaders(msg: String, headers: Seq[(String, String)]): String = {
    s"${formatLogPrefixWithHeaders(headers)} $msg\nheaders=${overwriteHeaderValues(headers, headersToOverwrite - AUTHORIZATION)}"
  }

  def formatWithoutHeaders(msg: String, headers: Seq[(String, String)]): String = {
    s"${formatLogPrefixWithHeaders(headers)} $msg"
  }

  private def formatLogPrefixWithHeaders(headers: Seq[(String, String)]): String = {
    val maybeClientId = findHeaderValue(X_CLIENT_ID_HEADER_NAME, headers)
    val maybeFieldsId = findHeaderValue(X_CDS_CLIENT_ID_HEADER_NAME, headers)
    val maybeConversationId = findHeaderValue(X_CONVERSATION_ID_HEADER_NAME, headers)

    maybeConversationId.fold("")(conversationId => s"[conversationId=$conversationId]") +
      maybeFieldsId.fold("")(maybeFieldsId => s"[fieldsId=$maybeFieldsId]") +
      maybeClientId.fold("")(maybeClientId => s"[clientId=$maybeClientId]")
  }

  //TODO clean up below
  private def formatLogPrefix(rm: HasId): String = {
    def fieldsId = rm match {
      case has: HasClientSubscriptionId =>
        s"[fieldsId=${has.clientSubscriptionId}]"
      case _ => ""
    }

    def notificationId = rm match {
      case has: HasNotificationId =>
        s"[notificationId=${has.notificationId}]"
      case _ => ""
    }

    def clientId = rm match {
      case has: HasMaybeClientId =>
        formatOptional("clientId", has.maybeClientId)
      case _ => ""
    }

    def correlationId = rm match {
      case has: HasMaybeCorrelationId =>
        formatOptional("correlationId", has.maybeCorrelationId)
      case _ => ""
    }

    def badgeId = rm match {
      case has: HasMaybeBadgeId =>
        formatOptional("badgeId", has.maybeBadgeId)
      case _ => ""
    }

    def submitter = rm match {
      case has: HasMaybeSubmitter =>
        formatOptional("submitterIdentifier", has.maybeSubmitterNumber)
      case _ => ""
    }

    def functionCode = rm match {
      case has: HasMaybeFunctionCode =>
        formatOptional("functionCode", has.maybeFunctionCode)
      case _ => ""
    }

    def issueDateTime = rm match {
      case has: HasMaybeIssueDateTime =>
        formatOptional("issueDateTime", has.maybeIssueDateTime)
      case _ => ""
    }

    def mrn = rm match {
      case has: HasMaybeMrn =>
        formatOptional("mrn", has.maybeMrn)
      case _ => ""
    }

    s"[${rm.idName}=${rm.idValue}]$clientId$fieldsId$notificationId$badgeId$submitter$correlationId$functionCode$issueDateTime$mrn"
  }

  private def formatOptional[T](name: String, maybeValue: Option[T]) = {
    maybeValue.fold("")(h => s"[$name=${h.toString}]")
  }

  private def findHeaderValue(headerName: String, headers: Seq[(String, String)]): Option[String] = {
    headers.collectFirst {
      case header if header._1.equalsIgnoreCase(headerName) => header._2
    }
  }

  private def overwriteHeaderValues(headers: Seq[(String, String)], overwrittenHeaderNames: Set[String]): Seq[(String, String)] = {
    headers map {
      case (rewriteHeader, _) if overwrittenHeaderNames.contains(rewriteHeader) => rewriteHeader -> headerOverwriteValue
      case header => header
    }
  }

}
