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

package uk.gov.hmrc.customs.notification.controllers.errors

import cats.data.NonEmptyList
import play.api.http.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.HeaderNames.{X_CLIENT_ID_HEADER_NAME, X_CLIENT_SUB_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}

sealed trait SubmitValidationError {
  def errorMessage: String
}

object ValidationError {

  sealed trait HeaderError {
    def headerName: String

    def errorType: HeaderErrorType

    def responseMessage: String = errorType match {
      case MissingHeaderValue => s"The $headerName header is missing."
      case InvalidHeaderValue => s"The $headerName header is invalid."
    }
  }

  case class InvalidBasicAuth(errorType: HeaderErrorType) extends HeaderError with SubmitValidationError {
    val headerName: String = AUTHORIZATION
    override val responseMessage: String = "Basic token is missing or not authorized."
    val errorMessage: String = responseMessage
  }

  case class InvalidAccept(errorType: HeaderErrorType) extends HeaderError with SubmitValidationError {
    val headerName: String = ACCEPT
    val errorMessage: String = responseMessage
  }

  case class InvalidHeaders(errors: NonEmptyList[CdsHeaderError]) extends SubmitValidationError {
    val errorMessage: String =
      errors
        .toList
        .map(_.responseMessage)
        .mkString(";\n")
  }

  sealed trait CdsHeaderError extends HeaderError

  case class InvalidClientSubId(errorType: HeaderErrorType) extends CdsHeaderError {
    val headerName: String = X_CLIENT_SUB_ID_HEADER_NAME
  }

  case class InvalidConversationId(errorType: HeaderErrorType) extends CdsHeaderError {
    val headerName: String = X_CONVERSATION_ID_HEADER_NAME
  }

  case object InvalidCorrelationId extends CdsHeaderError {
    val errorType: HeaderErrorType = InvalidHeaderValue
    val headerName: String = X_CORRELATION_ID_HEADER_NAME
  }

  case object MissingClientId extends CdsHeaderError {
    val errorType: HeaderErrorType = MissingHeaderValue
    val headerName: String = X_CLIENT_ID_HEADER_NAME
  }

  sealed trait HeaderErrorType

  case object MissingHeaderValue extends HeaderErrorType

  case object InvalidHeaderValue extends HeaderErrorType
}
