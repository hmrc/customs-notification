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

package uk.gov.hmrc.customs.notification.controllers

import play.mvc.Http.MimeTypes
import play.mvc.Http.Status.UNAUTHORIZED
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.UnauthorizedCode
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._

object CustomErrorResponses {

  val ErrorCdsClientIdInvalid: ErrorResponse = ErrorResponse.errorBadRequest(s"The $X_CDS_CLIENT_ID_HEADER_NAME header value is invalid")

  val ErrorCdsClientIdMissing: ErrorResponse = ErrorResponse.errorBadRequest(s"The $X_CDS_CLIENT_ID_HEADER_NAME header is missing")

  val ErrorConversationIdInvalid: ErrorResponse = ErrorResponse.errorBadRequest(s"The $X_CONVERSATION_ID_HEADER_NAME header value is invalid")

  val ErrorConversationIdMissing: ErrorResponse = ErrorResponse.errorBadRequest(s"The $X_CONVERSATION_ID_HEADER_NAME header is missing")

  val ErrorCdsClientIdNotFound: ErrorResponse = ErrorCdsClientIdInvalid

  val ErrorUnauthorized: ErrorResponse = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized")
}

object CustomHeaderNames {
  val X_CDS_CLIENT_ID_HEADER_NAME: String = "X-CDS-Client-ID"

  val SUBSCRIPTION_FIELDS_ID_HEADER_NAME: String = "api-subscription-fields-id"

  val X_CONVERSATION_ID_HEADER_NAME: String = "X-Conversation-ID"

  val X_BADGE_ID_HEADER_NAME : String = "X-Badge-Identifier"

  val X_EORI_ID_HEADER_NAME : String = "X-Eori-Identifier"

  val X_CORRELATION_ID_HEADER_NAME: String = "X-Correlation-ID"
}

object CustomMimeType {
  val XmlCharsetUtf8: String = MimeTypes.XML + "; charset=UTF-8"
}
