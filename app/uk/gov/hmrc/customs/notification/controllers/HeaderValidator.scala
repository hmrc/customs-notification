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

import play.api.http.HeaderNames._
import play.api.mvc.{ActionBuilder, Headers, Request, Result}
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorAcceptHeaderInvalid, ErrorContentTypeHeaderInvalid, ErrorGenericBadRequest}
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

import scala.concurrent.Future

trait HeaderValidator {

  val notificationLogger: NotificationLogger

  private val uuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

  private val correlationIdRegex = "^.{1,36}$"

  private val basicAuthTokenScheme = "Basic "

  def validateHeaders(maybeBasicAuthToken: Option[String]): ActionBuilder[Request] = new ActionBuilder[Request] {

    def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
      implicit val headers: Headers = request.headers
      val logMessage = "Received notification"
      notificationLogger.debug(logMessage, headers.headers)

      if (!hasAccept) {
        Future.successful(ErrorAcceptHeaderInvalid.XmlResult)
      } else if (!hasContentType) {
        Future.successful(ErrorContentTypeHeaderInvalid.XmlResult)
      } else if (missingClientId) {
        Future.successful(ErrorCdsClientIdMissing.XmlResult)
      } else if (!hasValidClientId) {
        Future.successful(ErrorCdsClientIdInvalid.XmlResult)
      } else if (missingConversationId) {
        Future.successful(ErrorConversationIdMissing.XmlResult)
      } else if (!hasValidConversationId) {
        Future.successful(ErrorConversationIdInvalid.XmlResult)
      } else if (!hasAuth(maybeBasicAuthToken)) {
        Future.successful(ErrorUnauthorized.XmlResult)
      } else if(!correlationIdIsValidIfPresent) {
        Future.successful(ErrorGenericBadRequest.XmlResult)
      }
      else {
        block(request)
      }
    }
  }

  private def hasAccept(implicit h: Headers) = {
    val result = h.get(ACCEPT).fold(false)(_ == MimeTypes.XML)
    logValidationResult(ACCEPT, result)
    result
  }

  private def hasContentType(implicit h: Headers) = {
    val result = h.get(CONTENT_TYPE).fold(false)(_.equalsIgnoreCase(CustomMimeType.XmlCharsetUtf8))
    logValidationResult(CONTENT_TYPE, result)
    result
  }

  private def missingClientId(implicit h: Headers) = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, !result)
    result
  }

  private def hasValidClientId(implicit h: Headers) = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, result)
    result
  }

  private def missingConversationId(implicit h: Headers) = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, !result)
    result
  }

  private def hasValidConversationId(implicit h: Headers) = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, result)
    result
  }


  private def correlationIdIsValidIfPresent(implicit h: Headers) = {
    val result = h.get(X_CORRELATION_ID_HEADER_NAME).forall { cid =>
      val correct = cid.matches(correlationIdRegex)
      logValidationResult(X_CORRELATION_ID_HEADER_NAME, correct)
      correct
    }

    result
  }

  private def hasAuth(maybeBasicAuthToken: Option[String])(implicit h: Headers) = {
    val result = maybeBasicAuthToken.fold(ifEmpty = true) {
      basicAuthToken => h.get(AUTHORIZATION).fold(false)(_ == basicAuthTokenScheme + basicAuthToken)
    }
    logValidationResult(AUTHORIZATION, result)
    result
  }

  private def logValidationResult(headerName: => String, validationResult: => Boolean)(implicit h: Headers): Unit = {
    val resultText = if (validationResult) "passed" else "failed"
    val msg = s"$headerName header $resultText validation"
    notificationLogger.debug(msg, h.headers)
    if (!validationResult) notificationLogger.error(msg, h.headers)
  }
}

