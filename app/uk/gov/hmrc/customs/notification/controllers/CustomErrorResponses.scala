/*
 * Copyright 2024 HM Revenue & Customs
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

import play.mvc.Http.MimeTypes
import play.mvc.Http.Status.UNAUTHORIZED
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.controllers.ErrorResponse.{UnauthorizedCode, errorBadRequest}

object CustomErrorResponses {

  val ErrorCdsClientIdInvalid: ErrorResponse = errorBadRequest(s"The $X_CDS_CLIENT_ID_HEADER_NAME header value is invalid")

  val ErrorCdsClientIdMissing: ErrorResponse = errorBadRequest(s"The $X_CDS_CLIENT_ID_HEADER_NAME header is missing")

  val ErrorClientIdMissing: ErrorResponse = errorBadRequest(s"$X_CLIENT_ID_HEADER_NAME required")

  val ErrorConversationIdInvalid: ErrorResponse = errorBadRequest(s"The $X_CONVERSATION_ID_HEADER_NAME header value is invalid")

  val ErrorConversationIdMissing: ErrorResponse = errorBadRequest(s"The $X_CONVERSATION_ID_HEADER_NAME header is missing")

  val ErrorCdsClientIdNotFound: ErrorResponse = ErrorCdsClientIdInvalid

  val ErrorUnauthorized: ErrorResponse = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized")
}

object CustomHeaderNames {
  val X_CDS_CLIENT_ID_HEADER_NAME: String = "X-CDS-Client-ID"

  val X_CLIENT_ID_HEADER_NAME = "X-Client-ID"

  val SUBSCRIPTION_FIELDS_ID_HEADER_NAME: String = "api-subscription-fields-id"

  val NOTIFICATION_ID_HEADER_NAME: String = "notification-id"

  val X_CONVERSATION_ID_HEADER_NAME: String = "X-Conversation-ID"

  val X_BADGE_ID_HEADER_NAME : String = "X-Badge-Identifier"

  val X_SUBMITTER_ID_HEADER_NAME : String = "X-Submitter-Identifier"

  val X_CORRELATION_ID_HEADER_NAME: String = "X-Correlation-ID"

  val ISSUE_DATE_TIME_HEADER: String = "X-IssueDateTime"
}

object CustomMimeType {
  val XmlCharsetUtf8: String = MimeTypes.XML + "; charset=UTF-8"
}
