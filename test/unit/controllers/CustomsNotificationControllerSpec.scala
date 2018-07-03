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

package unit.controllers

import java.util.UUID

import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, Matchers}
import play.api.libs.json.{JsObject, JsString}
import play.api.mvc._
import play.api.test.Helpers._
import play.mvc.Http.Status.{BAD_REQUEST, NOT_ACCEPTABLE, UNAUTHORIZED, UNSUPPORTED_MEDIA_TYPE}
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{UnauthorizedCode, errorBadRequest}
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.{CustomsNotificationController, RequestMetaData}
import uk.gov.hmrc.customs.notification.domain.DeclarantCallbackData
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.CustomsNotificationService
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationControllerSpec extends UnitSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockCustomsNotificationService = mock[CustomsNotificationService]
  private val mockConfigService = mock[ConfigService]
  private val mockCallbackDetailsConnector = mock[ApiSubscriptionFieldsConnector]
  private val mockCallbackDetails = mock[DeclarantCallbackData]


  private def controller() = new CustomsNotificationController(
    mockNotificationLogger,
    mockCustomsNotificationService,
    mockCallbackDetailsConnector,
    mockConfigService
  )

  private val wrongPayloadErrorResult = ErrorResponse.errorBadRequest("Request body does not contain well-formed XML.").XmlResult

  private val internalServerError = ErrorResponse.errorInternalServerError("Internal Server Error").XmlResult

  private def internalServerError(msg: String) = ErrorResponse.errorInternalServerError(msg).XmlResult

  private val clientIdMissingResult = ErrorResponse.errorBadRequest("The X-CDS-Client-ID header is missing").XmlResult

  private val conversationIdMissingResult = ErrorResponse.errorBadRequest("The X-Conversation-ID header is missing").XmlResult

  private val unauthorizedResult = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized").XmlResult

  private val emulatedServiceFailureMessage = "Emulated service failure"

  private val expectedRequestMetaData = RequestMetaData(validFieldsId, UUID.fromString(validConversationId), Some(badgeId))

  private val rightReturned = Right("crash test dummy")

  override protected def beforeEach() {
    reset(mockNotificationLogger, mockCustomsNotificationService, mockCallbackDetailsConnector, mockConfigService)
    when(mockConfigService.maybeBasicAuthToken).thenReturn(Some(basicAuthTokenValue))
    when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(mockCallbackDetails), meq(expectedRequestMetaData))(any[HeaderCarrier])).thenReturn(rightReturned)
  }

  "CustomsNotificationController" should {

    "respond with status 202 for valid request" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe ACCEPTED
      }

      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(mockCallbackDetails), meq(expectedRequestMetaData))(any[HeaderCarrier])
    }

    "respond with status 202 for missing Authorization when auth token is not configured" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(mockCallbackDetails), meq(expectedRequestMetaData.copy(mayBeBadgeId = None)))(any[HeaderCarrier])).thenReturn(rightReturned)

      testSubmitResult(MissingAuthorizationHeaderRequest) { result =>
        status(result) shouldBe ACCEPTED
      }

      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(mockCallbackDetails), meq(expectedRequestMetaData.copy(mayBeBadgeId = None)))(any[HeaderCarrier])
    }

    "respond with status 202 for invalid Authorization when auth token is not configured" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(mockCallbackDetails), meq(expectedRequestMetaData.copy(mayBeBadgeId = None)))(any[HeaderCarrier])).thenReturn(rightReturned)

      testSubmitResult(InvalidAuthorizationHeaderRequest) { result =>
        status(result) shouldBe ACCEPTED
      }
    }

    "respond with 400 when declarant callback data not found by ApiSubscriptionFields service" in {
      when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).thenReturn(Future.successful(None))

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe errorBadRequest("The X-CDS-Client-ID header value is invalid").XmlResult
      }
    }

    "respond with status 415 for an invalid content type request header" in {
      testSubmitResult(InvalidContentTypeHeaderRequest) { result =>
        status(result) shouldBe UNSUPPORTED_MEDIA_TYPE
        await(result) shouldBe ErrorResponse.ErrorContentTypeHeaderInvalid.XmlResult
      }
    }

    "respond with status 406 for an invalid accept request header" in {
      testSubmitResult(InvalidAcceptHeaderRequest) { result =>
        status(result) shouldBe NOT_ACCEPTABLE
        await(result) shouldBe ErrorResponse.ErrorAcceptHeaderInvalid.XmlResult
      }
    }

    "respond with status 400 for missing X-Conversation-ID" in {
      testSubmitResult(MissingConversationIdHeaderRequest) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe conversationIdMissingResult
      }
    }

    "respond with status 400 for missing X-CDS-Client-ID" in {
      testSubmitResult(MissingClientIdHeaderRequest) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe clientIdMissingResult
      }
    }

    "respond with status 401 for missing Authorization" in {
      testSubmitResult(MissingAuthorizationHeaderRequest) { result =>
        status(result) shouldBe UNAUTHORIZED
        await(result) shouldBe unauthorizedResult
      }
    }

    "respond with status 401 for invalid Authorization" in {
      testSubmitResult(InvalidAuthorizationHeaderRequest) { result =>
        status(result) shouldBe UNAUTHORIZED
        await(result) shouldBe unauthorizedResult
      }
    }

    "respond with status 400 for a non well-formed XML payload" in {
      testSubmitResult(ValidRequest.withTextBody("<xml><non_well_formed></xml>")) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe wrongPayloadErrorResult
      }
    }

    "respond with status 400 for a non XML payload" in {
      testSubmitResult(ValidRequest.withJsonBody(JsObject(Seq("something" -> JsString("I am a json"))))) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe wrongPayloadErrorResult
      }
    }

    "respond with 500 when unexpected failure happens" in {
      when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier]))
        .thenReturn(Future.failed(emulatedServiceFailure))


      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
        await(result) shouldBe internalServerError
      }
    }

    "respond with status 500 when handle notification fails" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockCustomsNotificationService.handleNotification(any(), any(), any())(any()))
        .thenReturn(Left(emulatedServiceFailureMessage))

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
        await(result) shouldBe internalServerError(emulatedServiceFailureMessage)
      }
    }

  }

  private def returnMockedCallbackDetailsForTheClientIdInRequest() = {
    when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).
      thenReturn(Future.successful(Some(mockCallbackDetails)))
  }

  private def testSubmitResult(request: Request[AnyContent])(test: Future[Result] => Unit) {
    val result = controller().submit().apply(request)
    test(result)
  }
}
