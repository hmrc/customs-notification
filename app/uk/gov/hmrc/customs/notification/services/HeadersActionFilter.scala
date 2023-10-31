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

package uk.gov.hmrc.customs.notification.services

import play.api.Configuration
import play.api.http.HeaderNames._
import play.api.mvc._
import play.mvc.Http.MimeTypes
import play.mvc.Http.Status.UNAUTHORIZED
import uk.gov.hmrc.customs.api.common.config.ConfigValidatedNelAdaptor
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse._
import uk.gov.hmrc.customs.notification.config.{AppConfig, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.util.HeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.util.NotificationLogger

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HeadersActionFilter @Inject()(appConfig: AppConfig,
                                    notificationLogger: NotificationLogger)
                                   (implicit ec: ExecutionContext) extends ActionFilter[Request] {
  private val uuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
  private val correlationIdRegex = "^.{1,36}$"
  private val basicAuthTokenScheme = "Basic "
  private val validHeaderText = "header passed validation"
  private val headerPresentText = "header is present"
  private val invalidHeaderText = "header failed validation"
  private val headerNotPresentText = "header is not present"

  override def executionContext: ExecutionContext = ec
  override def filter[A](request: Request[A]): Future[Option[Result]] = {
    implicit val headers: Headers = request.headers
    val maybeBasicAuthToken = appConfig.maybeBasicAuthToken

    notificationLogger.debugWithHeaders("Received notification", headers.headers)

    def collect(validation: Headers => Option[String], logMessage: String, error: ErrorResponse)(implicit h: Headers): Either[ErrorResponse, String] = {
      val maybeString = validation(h)
      maybeString match {
        case None =>
          notificationLogger.debugWithPrefixedHeaders(logMessage, headers.headers)
          Left(error)
        case Some(msg) =>
          Right(logMessage + "\n" + msg)
      }
    }

    val logOutput: Either[ErrorResponse, String] = for {
      logAccumulatorA <- collect(hasAccept, "", ErrorAcceptHeaderInvalid)
      logAccumulatorB <- collect(hasContentType, logAccumulatorA, ErrorContentTypeHeaderInvalid)
      logAccumulatorC <- collect(missingClientId, logAccumulatorB, errorBadRequest(s"The $X_CDS_CLIENT_ID_HEADER_NAME header is missing"))
      logAccumulatorD <- collect(hasValidClientId, logAccumulatorC, errorBadRequest(s"The $X_CDS_CLIENT_ID_HEADER_NAME header value is invalid"))
      logAccumulatorE <- collect(missingConversationId, logAccumulatorD, errorBadRequest(s"The $X_CONVERSATION_ID_HEADER_NAME header is missing"))
      logAccumulatorF <- collect(hasValidConversationId, logAccumulatorE, errorBadRequest(s"The $X_CONVERSATION_ID_HEADER_NAME header value is invalid"))
      logAccumulatorG <- collect(hasAuth(maybeBasicAuthToken), logAccumulatorF, ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized"))
      logAccumulatorH <- collect(correlationIdIsValidIfPresent, logAccumulatorG, ErrorGenericBadRequest)
    } yield logAccumulatorH

    logOutput match {
      case Right(msg) =>
        notificationLogger.debugWithPrefixedHeaders(msg, headers.headers)
        Future.successful(None)
      case Left(error) =>
        Future.successful(Some(error.XmlResult))
    }
  }

  //  private def validateHeaderForDeleteBlocked(headers: Headers, endpointName: String): Either[ErrorResponse, ClientId] = {
  //    headers.get(X_CLIENT_ID_HEADER_NAME).fold[Either[ErrorResponse, ClientId]] {
  //      notificationLogger.errorWithHeaders(s"missing $X_CLIENT_ID_HEADER_NAME header when calling $endpointName endpoint", headers.headers)
  //      Left(errorBadRequest(s"$X_CLIENT_ID_HEADER_NAME required"))
  //    } { clientId =>
  //      notificationLogger.debugWithHeaders(s"called $endpointName", headers.headers)
  //      Right(ClientId(clientId))
  //    }
  //  }

  private def hasAccept(h: Headers): Option[String] = {
    val result = h.get(ACCEPT).fold(false)(_ == MimeTypes.XML)
    logValidationResult(ACCEPT, result)(h)
  }

  private def hasContentType(h: Headers): Option[String] = {
    val result = h.get(CONTENT_TYPE).fold(false)(_.equalsIgnoreCase(MimeTypes.XML + "; charset=UTF-8"))
    logValidationResult(CONTENT_TYPE, result)(h)
  }

  private def missingClientId(h: Headers): Option[String] = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, !result, headerPresentText, headerNotPresentText)(h)
  }

  private def hasValidClientId(h: Headers): Option[String] = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, result)(h)
  }

  private def missingConversationId(h: Headers): Option[String] = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, !result, headerPresentText, headerNotPresentText)(h)
  }

  private def hasValidConversationId(h: Headers): Option[String] = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, result)(h)
  }

  private def correlationIdIsValidIfPresent(h: Headers): Option[String] = {
    h.get(X_CORRELATION_ID_HEADER_NAME) match {
      case Some(cid) =>
        val correct = cid.matches(correlationIdRegex)
        logValidationResult(X_CORRELATION_ID_HEADER_NAME, correct)(h)
      case None => Some(s"$X_CORRELATION_ID_HEADER_NAME not present")
    }
  }

  private def hasAuth(maybeBasicAuthToken: Option[String])(headers: Headers): Option[String] = {
    //TODO either just have a 'basicAuthToken' in the config and throw an exception here or get rid
    val result: Boolean = maybeBasicAuthToken.fold(true)(
      basicAuthToken => headers.get(AUTHORIZATION).fold(false)(_ == basicAuthTokenScheme + basicAuthToken)
    )
    logValidationResult(AUTHORIZATION, result)(headers)
  }

  private def logValidationResult(headerName: => String, validationResult: => Boolean, validText: => String = validHeaderText, invalidText: String = invalidHeaderText)(implicit h: Headers): Option[String] = {
    if (!validationResult) {
      notificationLogger.errorWithHeaders(s"$headerName $invalidText", h.headers)
      None
    } else {
      Some(s"$headerName $validText")
    }
  }
}

