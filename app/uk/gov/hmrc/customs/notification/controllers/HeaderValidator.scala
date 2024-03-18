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

import play.api.http.HeaderNames._
import play.api.mvc._
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.controllers.ErrorResponse.{ErrorAcceptHeaderInvalid, ErrorContentTypeHeaderInvalid, ErrorGenericBadRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

import scala.concurrent.{ExecutionContext, Future}

trait HeaderValidator {

  val notificationLogger: NotificationLogger
  val controllerComponents: ControllerComponents

  private val uuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

  private val correlationIdRegex = "^.{1,36}$"

  private val basicAuthTokenScheme = "Basic "

  private val validHeaderText = "header passed validation"
  private val headerPresentText = "header is present"

  private val invalidHeaderText = "header failed validation"
  private val headerNotPresentText = "header is not present"

  def validateHeaders(maybeBasicAuthToken: Option[String]): ActionBuilder[Request, AnyContent] = new ActionBuilder[Request, AnyContent] {

    override protected def executionContext: ExecutionContext = controllerComponents.executionContext
    override def parser: BodyParser[AnyContent] = controllerComponents.parsers.defaultBodyParser
    def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
      implicit val headers: Headers = request.headers

      notificationLogger.debugWithHeaders("Received notification", headers.headers)

      def collect(validation: Headers => Option[String], logMessage: String, error: ErrorResponse)(implicit h: Headers): Either[ErrorResponse,String]  = {
        val maybeString = validation(h)
        maybeString match {
          case None =>
            notificationLogger.debugWithPrefixedHeaders(logMessage, headers.headers)
            Left(error)
          case Some(msg) =>
            Right(logMessage + "\n" + msg)
        }
      }

      val logOutput: Either[ErrorResponse,String] = for {
        logAccumulatorA <- collect(hasAccept, "", ErrorAcceptHeaderInvalid)
        logAccumulatorB <- collect(hasContentType, logAccumulatorA, ErrorContentTypeHeaderInvalid)
        logAccumulatorC <- collect(missingClientId, logAccumulatorB, ErrorCdsClientIdMissing)
        logAccumulatorD <- collect(hasValidClientId, logAccumulatorC, ErrorCdsClientIdInvalid)
        logAccumulatorE <- collect(missingConversationId, logAccumulatorD, ErrorConversationIdMissing)
        logAccumulatorF <- collect(hasValidConversationId, logAccumulatorE, ErrorConversationIdInvalid)
        logAccumulatorG <- collect(hasAuth(maybeBasicAuthToken), logAccumulatorF, ErrorUnauthorized)
        logAccumulatorH <- collect(correlationIdIsValidIfPresent, logAccumulatorG, ErrorGenericBadRequest)
      } yield logAccumulatorH

      logOutput match {
        case Right(msg) =>
          notificationLogger.debugWithPrefixedHeaders(msg, headers.headers)
          block(request)
        case Left(error) =>
          Future.successful(error.XmlResult)
      }
    }
  }

  private def hasAccept(h: Headers) = {
    val result = h.get(ACCEPT).fold(false)(_ == MimeTypes.XML)
    logValidationResult(ACCEPT, result)(h)
  }

  private def hasContentType(h: Headers) = {
    val result = h.get(CONTENT_TYPE).fold(false)(_.equalsIgnoreCase(CustomMimeType.XmlCharsetUtf8))
    logValidationResult(CONTENT_TYPE, result)(h)
  }

  private def missingClientId(h: Headers) = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, !result, headerPresentText, headerNotPresentText)(h)
  }

  private def hasValidClientId(h: Headers) = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, result)(h)
  }

  private def missingConversationId(h: Headers) = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, !result, headerPresentText, headerNotPresentText)(h)
  }

  private def hasValidConversationId(h: Headers) = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, result)(h)
  }

  private def correlationIdIsValidIfPresent(h: Headers) = {
    h.get(X_CORRELATION_ID_HEADER_NAME) match {
      case Some(cid) =>
        val correct = cid.matches(correlationIdRegex)
        logValidationResult(X_CORRELATION_ID_HEADER_NAME, correct)(h)
      case None => Some(s"$X_CORRELATION_ID_HEADER_NAME not present")
    }
  }

  private def hasAuth(maybeBasicAuthToken: Option[String])(h: Headers) = {
    val result = maybeBasicAuthToken.fold(ifEmpty = true) {
      basicAuthToken => h.get(AUTHORIZATION).fold(false)(_ == basicAuthTokenScheme + basicAuthToken)
    }
    logValidationResult(AUTHORIZATION, result)(h)
  }

  private def logValidationResult(headerName: => String, validationResult: => Boolean, validText: => String = validHeaderText, invalidText: String = invalidHeaderText)(implicit h: Headers) = {
    if (!validationResult) {
      notificationLogger.errorWithHeaders(s"$headerName $invalidText", h.headers)
      None
    } else {
      Some(s"$headerName $validText")
    }
  }
}

