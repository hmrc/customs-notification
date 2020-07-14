/*
 * Copyright 2020 HM Revenue & Customs
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
import play.api.mvc.{ActionBuilder, AnyContent, BodyParser, ControllerComponents, Headers, Request, Result}
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorAcceptHeaderInvalid, ErrorContentTypeHeaderInvalid, ErrorGenericBadRequest}
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

trait HeaderValidator {

  val notificationLogger: NotificationLogger
  val controllerComponents: ControllerComponents

  private val uuidRegex = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

  private val correlationIdRegex = "^.{1,36}$"

  private val basicAuthTokenScheme = "Basic "

  def validateHeaders(maybeBasicAuthToken: Option[String]): ActionBuilder[Request, AnyContent] = new ActionBuilder[Request, AnyContent] {

    override protected def executionContext: ExecutionContext = controllerComponents.executionContext
    override def parser: BodyParser[AnyContent] = controllerComponents.parsers.defaultBodyParser
    def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
      implicit val headers: Headers = request.headers

      notificationLogger.debugWithHeaders("Received notification", headers.headers)

      def collect(validation: Headers => Option[String], logMessage: String, error: ErrorResponse)(implicit h: Headers) = {
        val maybeString = validation(h)
        maybeString match {
          // Wrap the Error in an exception - should not be using Error https://stackoverflow.com/questions/912334/differences-between-exception-and-error
          // https://stackoverflow.com/questions/17265022/what-is-a-boxed-error-in-scala
          case None =>
            notificationLogger.debugWithPrefixedHeaders(logMessage, headers.headers)
            Future.failed(new Exception(error))
          case Some(msg) =>
            Future.successful(logMessage + "\n" + msg)
        }
      }

      val logOutput: Future[String] = for {
        a <- collect(hasAccept, "", ErrorAcceptHeaderInvalid)
        b <- collect(hasContentType, a, ErrorContentTypeHeaderInvalid)
        c <- collect(missingClientId, b, ErrorCdsClientIdMissing)
        d <- collect(hasValidClientId, c, ErrorCdsClientIdInvalid)
        e <- collect(missingConversationId, d, ErrorConversationIdMissing)
        f <- collect(hasValidConversationId, e, ErrorConversationIdInvalid)
        g <- collect(hasAuth(maybeBasicAuthToken), f, ErrorUnauthorized)
        h <- collect(correlationIdIsValidIfPresent, g, ErrorGenericBadRequest)
      } yield h

      logOutput.flatMap(
        msg => {
        notificationLogger.debugWithPrefixedHeaders(msg, headers.headers)
        block(request)
      }).recover {
        case e: Exception => e.getCause.asInstanceOf[ErrorResponse].XmlResult
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
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, !result)(h)
  }

  private def hasValidClientId(h: Headers) = {
    val result = h.get(X_CDS_CLIENT_ID_HEADER_NAME).exists(_.matches(uuidRegex))
    logValidationResult(X_CDS_CLIENT_ID_HEADER_NAME, result)(h)
  }

  private def missingConversationId(h: Headers) = {
    val result = h.get(X_CONVERSATION_ID_HEADER_NAME).isEmpty
    logValidationResult(X_CONVERSATION_ID_HEADER_NAME, !result)(h)
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

  private def logValidationResult(headerName: => String, validationResult: => Boolean)(implicit h: Headers) = {
    val resultText = if (validationResult) "passed" else "failed"
    val msg = s"$headerName header $resultText validation"
    if (!validationResult) {
      notificationLogger.errorWithHeaders(msg, h.headers)
      None
    } else {
      Some(msg)
    }
  }
}

