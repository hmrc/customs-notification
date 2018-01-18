/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.model.SeqOfHeader
import uk.gov.hmrc.http.HeaderCarrier

object LoggingHelper {

  private val headerOverwriteValue = "value-not-logged"
  private val headersToOverwrite = Set(AUTHORIZATION)

  def formatError(msg: String)(implicit hc: HeaderCarrier): String = {
    formatInfo(msg)
  }

  def formatWarn(msg: String)(implicit hc: HeaderCarrier): String = {
    formatInfo(msg)
  }

  def formatInfo(msg: String)(implicit hc: HeaderCarrier): String = {
    val headers = hc.headers
    formatInfo(msg, headers)
  }

  def formatInfo(msg: String, headers: SeqOfHeader): String = {
    s"${formatLogPrefix(headers)} $msg"
  }

  def formatDebug(msg: String, headers: SeqOfHeader): String = {
    s"${formatLogPrefix(headers)} $msg\nheaders=${overwriteHeaderValues(headers,headersToOverwrite - AUTHORIZATION)}"
  }

  def formatDebug(msg: String, maybeUrl: Option[String] = None, maybePayload: Option[String] = None)(implicit hc: HeaderCarrier): String = {
    val headers = hc.headers
    val urlPart = maybeUrl.fold("")(url => s" url=$url")
    val payloadPart = maybePayload.fold("")(payload => s"\npayload=\n$payload")
    s"${formatLogPrefix(headers)} $msg$urlPart\nheaders=${overwriteHeaderValues(headers,headersToOverwrite - AUTHORIZATION)}$payloadPart"
  }

  private def formatLogPrefix(headers: SeqOfHeader): String = {
    val maybeFieldsId = findHeaderValue(CustomHeaderNames.X_CDS_CLIENT_ID_HEADER_NAME, headers)
    val maybeConversationId = findHeaderValue(CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME, headers)

    maybeConversationId.fold("")(conversationId => s"[conversationId=$conversationId]") +
    maybeFieldsId.fold("")(maybeFieldsId => s"[fieldsId=$maybeFieldsId]")
  }

  private def findHeaderValue(headerName: String, headers: SeqOfHeader): Option[String] = {
    headers.collectFirst{
      case (`headerName`, headerValue) => headerValue
    }
  }

  private def overwriteHeaderValues(headers: SeqOfHeader, overwrittenHeaderNames: Set[String]): SeqOfHeader = {
    headers map {
      case (rewriteHeader, _) if overwrittenHeaderNames.contains(rewriteHeader) => rewriteHeader -> headerOverwriteValue
      case header => header
    }
  }
}
