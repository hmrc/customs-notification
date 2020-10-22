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

package unit.controllers

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.HeaderNames._
import play.api.mvc.Results._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorAcceptHeaderInvalid, ErrorContentTypeHeaderInvalid, ErrorGenericBadRequest}
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.controllers.HeaderValidator
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import util.RequestHeaders._
import util.TestData.basicAuthTokenValue
import util.UnitSpec

import scala.util.Random

class HeaderValidatorSpec extends UnitSpec with MockitoSugar with TableDrivenPropertyChecks with ControllerSpecHelper {

  private val mockLogger: NotificationLogger = mock[NotificationLogger]

  private val validator = new HeaderValidator {
    override val notificationLogger: NotificationLogger = mockLogger
    override val controllerComponents: ControllerComponents = Helpers.stubControllerComponents()
  }

  private val withAuthTokenConfigured: Action[AnyContent] = validator.validateHeaders(Some(basicAuthTokenValue)) async {
    Ok
  }

  private val authTokenNotConfigured: Action[AnyContent] = validator.validateHeaders(None) async {
    Ok
  }

  private val headersTable =
    Table(
      ("description", "Action with headers validation", "Headers", "Expected response"),
      ("return OK result for valid headers", withAuthTokenConfigured, ValidHeaders, Ok),
      ("return OK result for valid headers (case insensitive)", withAuthTokenConfigured, ValidHeaders + CONTENT_TYPE_HEADER_LOWERCASE, Ok),
      ("return OK result for Authorization header missing when not configured to validate it", authTokenNotConfigured, ValidHeaders - AUTHORIZATION, Ok),
      ("return OK result for Authorization header invalid when not configured to validate it", authTokenNotConfigured, ValidHeaders + BASIC_AUTH_HEADER_INVALID, Ok),
      ("return ErrorContentTypeHeaderInvalid result for content type header missing", withAuthTokenConfigured, ValidHeaders - CONTENT_TYPE, ErrorContentTypeHeaderInvalid.XmlResult),
      ("return ErrorAcceptHeaderInvalid result for accept header missing", withAuthTokenConfigured, ValidHeaders - ACCEPT, ErrorAcceptHeaderInvalid.XmlResult),
      ("return ErrorClientIdMissing result for clientId header missing", withAuthTokenConfigured, ValidHeaders - X_CDS_CLIENT_ID_HEADER_NAME, ErrorCdsClientIdMissing.XmlResult),
      ("return ErrorClientIdInvalid result for clientId header invalid", withAuthTokenConfigured, ValidHeaders + X_CDS_CLIENT_ID_INVALID, ErrorCdsClientIdInvalid.XmlResult),
      ("return ErrorConversationIdMissing result for conversationId header missing", withAuthTokenConfigured, ValidHeaders - X_CONVERSATION_ID_HEADER_NAME, ErrorConversationIdMissing.XmlResult),
      ("return ErrorConversationIdInvalid result for conversationId header invalid", withAuthTokenConfigured, ValidHeaders + X_CONVERSATION_ID_INVALID, ErrorConversationIdInvalid.XmlResult),
      ("return ErrorUnauthorized result for Authorization header missing", withAuthTokenConfigured, ValidHeaders - AUTHORIZATION, ErrorUnauthorized.XmlResult),
      ("return ErrorUnauthorized result for Authorization header invalid", withAuthTokenConfigured, ValidHeaders + BASIC_AUTH_HEADER_INVALID, ErrorUnauthorized.XmlResult),
      ("return ErrorAcceptHeaderInvalid result for all headers missing", withAuthTokenConfigured, NoHeaders, ErrorAcceptHeaderInvalid.XmlResult),
      ("return Bad Request if correlation id header is provided but too long", withAuthTokenConfigured, ValidHeaders + (X_CORRELATION_ID_HEADER_NAME -> Random.nextString(40)), ErrorGenericBadRequest.XmlResult),
      ("return Bad Request if correlation id header is present but empty", withAuthTokenConfigured, ValidHeaders + (X_CORRELATION_ID_HEADER_NAME -> ""), ErrorGenericBadRequest.XmlResult),
      ("return OK if correlation id header is provided and valid", withAuthTokenConfigured, ValidHeaders + (X_CORRELATION_ID_HEADER_NAME -> Random.nextString(36)), Ok),
      ("return OK if there are multiple valid accept headers", withAuthTokenConfigured, ValidHeaders + CONTENT_TYPE_HEADER_LOWERCASE, Ok),
        )

  private def requestWithHeaders(headers: Map[String, String]) =
    FakeRequest().withHeaders(headers.toSeq: _*)

  "HeaderValidatorAction" should {
    forAll(headersTable) { (description, action, headers, response) =>
      s"$description" in {
        await(action.apply(requestWithHeaders(headers))) shouldBe response
      }
    }
  }

}
