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

package unit.controllers

import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsObject, JsString}
import play.api.mvc._
import play.api.test.Helpers
import play.api.test.Helpers._
import play.mvc.Http.Status.{BAD_REQUEST, NOT_ACCEPTABLE, UNAUTHORIZED, UNSUPPORTED_MEDIA_TYPE}
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.ErrorResponse.{UnauthorizedCode, errorBadRequest}
import uk.gov.hmrc.customs.notification.controllers.{CustomsNotificationController, ErrorResponse, RequestMetaData}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, DateTimeService, UuidService}
import util.TestData._
import util.UnitSpec

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, NodeSeq}

class CustomsNotificationControllerSpec extends UnitSpec with Matchers with MockitoSugar with BeforeAndAfterEach with ControllerSpecHelper {

  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockCustomsNotificationService = mock[CustomsNotificationService]
  private val mockConfigService = mock[ConfigService]
  private val mockCallbackDetailsConnector = mock[ApiSubscriptionFieldsConnector]
  private val mockDateTimeService = mock[DateTimeService]
  private val mockUuidService = mock[UuidService]

  private def controller() = new CustomsNotificationController(
    mockCustomsNotificationService,
    mockCallbackDetailsConnector,
    mockConfigService,
    mockDateTimeService,
    mockUuidService,
    Helpers.stubControllerComponents(),
    mockNotificationLogger
  )

  private val wrongPayloadErrorResult = ErrorResponse.errorBadRequest("Request body does not contain well-formed XML.").XmlResult

  private val internalServerError = ErrorResponse.errorInternalServerError("Internal Server Error").XmlResult

  private val clientIdMissingResult = ErrorResponse.errorBadRequest("The X-CDS-Client-ID header is missing").XmlResult

  private val conversationIdMissingResult = ErrorResponse.errorBadRequest("The X-Conversation-ID header is missing").XmlResult

  private val unauthorizedResult = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized").XmlResult

  private val apiSubscriptionFields = ApiSubscriptionFields(clientIdString1, DeclarantCallbackDataOneForPush)

  private val expectedRequestMetaData = RequestMetaData(clientSubscriptionId, conversationId, notificationId,
    Some(clientId1), Some(BadgeId(badgeId)), Some(Submitter(submitterNumber)), Some(CorrelationId(correlationId)),
    None, None, None, mockDateTimeService.zonedDateTimeUtc)

  private val eventualTrue = Future.successful(true)

  private val eventualFalse = Future.successful(false)

  override protected def beforeEach(): Unit = {
    reset[Any](mockNotificationLogger, mockCustomsNotificationService, mockCallbackDetailsConnector, mockConfigService, mockDateTimeService)
    val notificationConfig = mock[NotificationConfig]
    when(notificationConfig.hotFixTranslates).thenReturn(Seq("old:new"))
    when(mockConfigService.maybeBasicAuthToken).thenReturn(Some(basicAuthTokenValue))
    when(mockConfigService.notificationConfig).thenReturn(notificationConfig)
    when(mockUuidService.uuid()).thenReturn(UUID.fromString(validNotificationId))
    when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData), meq(apiSubscriptionFields))(any())).thenReturn(eventualTrue)
  }

  "CustomsNotificationController" should {

    "respond with status 202 for valid request" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()

      testSubmitResult(ValidRequestWithMixedCaseCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }
      
      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(expectedRequestMetaData), meq(apiSubscriptionFields))(any())
      verifyLog("info","Processed notification", mockNotificationLogger)
    }

    "respond with status 202 for missing Authorization when auth token is not configured" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(maybeBadgeId = None, maybeSubmitterNumber = None)), meq(apiSubscriptionFields))(any())).thenReturn(eventualTrue)

      testSubmitResult(MissingAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }

      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(maybeBadgeId = None, maybeSubmitterNumber = None)), meq(apiSubscriptionFields))(any())
    }

    "respond with status 202 for invalid Authorization when auth token is not configured" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(maybeBadgeId = None, maybeSubmitterNumber = None)), meq(apiSubscriptionFields))(any())).thenReturn(eventualTrue)

      testSubmitResult(InvalidAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }
    }

    "respond with 400 when declarant callback data not found by ApiSubscriptionFields service" in {
      when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any())).thenReturn(Future.successful(None))

      testSubmitResult(ValidRequestWithMixedCaseCorrelationId) { result =>
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
      testSubmitResult(MissingAuthorizationHeaderRequestWithCorrelationId) { result =>
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
      when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any()))
        .thenReturn(Future.failed(emulatedServiceFailure))


      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
        await(result) shouldBe internalServerError
      }
    }

    "respond with status 500 when handle notification fails" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockCustomsNotificationService.handleNotification(any(), any(), any())(any()))
        .thenReturn(eventualFalse)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "respond with status 500 when handle notification fails unexpectedly" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockCustomsNotificationService.handleNotification(any(), any(), any())(any()))
        .thenThrow(emulatedServiceFailure)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "extract FunctionCode from xml payload when present" in {
      controller().extractFunctionCode(elementToOptionalNodeSeq(ValidXML)) shouldBe None

      val functionCodeXml = <Too><Response><FunctionCode>Bar</FunctionCode></Response></Too>
      controller().extractFunctionCode(elementToOptionalNodeSeq(functionCodeXml)) shouldBe Some(FunctionCode("Bar"))
    }

    "extract IssueDateTime from xml payload when present" in {
      controller().extractFunctionCode(elementToOptionalNodeSeq(ValidXML)) shouldBe None

      val functionCodeXml = <Too><Response><IssueDateTime><DateTimeString>20000101</DateTimeString></IssueDateTime></Response></Too>
      controller().extractIssueDateTime(elementToOptionalNodeSeq(functionCodeXml), None) shouldBe Some(IssueDateTime("20000101"))
    }

    "extract IssueDateTime from header when present and no issueDateTime in xml" in {
      val functionCodeXml = <Too><Response></Response></Too>
      val issueDateTimeHeader = Some(issueDateTime)
      controller().extractIssueDateTime(elementToOptionalNodeSeq(functionCodeXml), issueDateTimeHeader) shouldBe Some(IssueDateTime(issueDateTime))
    }

    "extract IssueDateTime from xml and not from header" in {
      val functionCodeXml = <Too><Response><IssueDateTime><DateTimeString>20000101</DateTimeString></IssueDateTime></Response></Too>
      val issueDateTimeHeader = Some(issueDateTime)
      controller().extractIssueDateTime(elementToOptionalNodeSeq(functionCodeXml), issueDateTimeHeader) shouldBe Some(IssueDateTime("20000101"))
    }

    "extract Mrn from xml payload when present" in {
      controller().extractFunctionCode(elementToOptionalNodeSeq(ValidXML)) shouldBe None

      val functionCodeXml = <Too><Response><Declaration><ID>123456</ID></Declaration></Response></Too>
      controller().extractMrn(elementToOptionalNodeSeq(functionCodeXml)) shouldBe Some(Mrn("123456"))
    }
  }

  private def returnMockedCallbackDetailsForTheClientIdInRequest() = {
    when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any())).
      thenReturn(Future.successful(Some(apiSubscriptionFields)))
  }

  private def testSubmitResult(request: Request[AnyContent])(test: Future[Result] => Unit): Unit = {
    val result = controller().submit().apply(request)
    test(result)
  }

  private def elementToOptionalNodeSeq(el: Elem): Option[NodeSeq] = Some(el)
}
