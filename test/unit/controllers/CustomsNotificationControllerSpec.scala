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
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.UnauthorizedCode
import uk.gov.hmrc.customs.notification.config.{CustomsNotificationConfig, NotificationConfig, NotificationMetricsConfig, NotificationQueueConfig, UnblockPollerConfig}
import uk.gov.hmrc.customs.notification.controllers.CustomsNotificationController
import uk.gov.hmrc.customs.notification.models.requests.MetaDataRequest
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, BadgeId, CorrelationId, Submitter}
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, HeadersActionFilter}
import uk.gov.hmrc.customs.notification.util.{DateTimeHelper, NotificationLogger, NotificationWorkItemRepo}
import util.TestData._
import util.UnitSpec

import scala.concurrent.Future
import scala.xml.{Elem, NodeSeq}

class CustomsNotificationControllerSpec extends UnitSpec with Matchers with MockitoSugar with BeforeAndAfterEach with ControllerSpecHelper {

  private implicit val ec = Helpers.stubControllerComponents().executionContext
  private val stubConfig = CustomsNotificationConfig(
    maybeBasicAuthToken = Some(validBasicAuthToken),
    notificationQueueConfig = mock[NotificationQueueConfig],
    notificationConfig = mock[NotificationConfig],
    notificationMetricsConfig = mock[NotificationMetricsConfig],
    unblockPollerConfig = mock[UnblockPollerConfig]
  )
  private val mockNotificationLogger = mock[NotificationLogger]
  private val stubHeadersActionFilter = new HeadersActionFilter(stubConfig, mockNotificationLogger)
  private val mockCustomsNotificationService = mock[CustomsNotificationService]
  private val mockRepo = mock[NotificationWorkItemRepo]
  private def controller() = new CustomsNotificationController(
    Helpers.stubControllerComponents(),
    mockNotificationLogger,
    stubHeadersActionFilter,
    mockCustomsNotificationService,
    mockRepo
  )

  private val wrongPayloadErrorResult = ErrorResponse.errorBadRequest("Request body does not contain well-formed XML.").XmlResult

  private val internalServerError = ErrorResponse.errorInternalServerError("Internal Server Error").XmlResult

  private val clientIdMissingResult = ErrorResponse.errorBadRequest("The X-CDS-Client-ID header is missing").XmlResult

  private val conversationIdMissingResult = ErrorResponse.errorBadRequest("The X-Conversation-ID header is missing").XmlResult

  private val unauthorizedResult = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized").XmlResult

//  private val apiSubscriptionFields = ApiSubscriptionFields(clientIdString1, DeclarantCallbackDataOneForPush)

  private val expectedRequestMetaData = MetaDataRequest(
    clientSubscriptionId,
    conversationId,
    notificationId,
    Some(clientId1), Some(BadgeId(badgeId)), Some(Submitter(submitterNumber)), Some(CorrelationId(correlationId)),
    None, None, None, DateTimeHelper.zonedDateTimeUtc)

  private val eventualTrue = Future.successful(true)

  private val eventualFalse = Future.successful(false)

  override protected def beforeEach(): Unit = {
    reset[Any](mockNotificationLogger, mockCustomsNotificationService)
    when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData))(any())).thenReturn(eventualTrue)
  }

  "CustomsNotificationController" should {

    "respond with status 202 for valid request" in {
      testSubmitResult(ValidRequestWithMixedCaseCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }
      
      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(expectedRequestMetaData))(any())
      verifyLog("info","Saved notification", mockNotificationLogger)
    }

    "respond with status 202 for missing Authorization when auth token is not configured" in {
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(maybeBadgeId = None, maybeSubmitterNumber = None)))(any())).thenReturn(eventualTrue)

      testSubmitResult(MissingAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }

      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(maybeBadgeId = None, maybeSubmitterNumber = None)))(any())
    }

    "respond with status 202 for invalid Authorization when auth token is not configured" in {
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(maybeBadgeId = None, maybeSubmitterNumber = None)))(any())).thenReturn(eventualTrue)

      testSubmitResult(InvalidAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }
    }
//
//    "respond with 400 when declarant callback data not found by ApiSubscriptionFields service" in {
////      when(mockCallbackDetailsConnector.getApiSubscriptionFields(meq(validFieldsId))(any())).thenReturn(Future.successful(None))
//
//      testSubmitResult(ValidRequestWithMixedCaseCorrelationId) { result =>
//        status(result) shouldBe BAD_REQUEST
//        await(result) shouldBe errorBadRequest("The X-CDS-Client-ID header value is invalid").XmlResult
//      }
//    }

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
//      when(mockCallbackDetailsConnector.getApiSubscriptionFields(meq(validFieldsId))(any()))
//        .thenReturn(Future.failed(emulatedServiceFailure))


      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
        await(result) shouldBe internalServerError
      }
    }

    "respond with status 500 when handle notification fails" in {
      when(mockCustomsNotificationService.handleNotification(any(), any())(any()))
        .thenReturn(eventualFalse)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "respond with status 500 when handle notification fails unexpectedly" in {
      when(mockCustomsNotificationService.handleNotification(any(), any())(any()))
        .thenThrow(emulatedServiceFailure)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }
//
//    "extract FunctionCode from xml payload when present" in {
//      controller().extractFunctionCode(elementToOptionalNodeSeq(ValidXML)) shouldBe None
//
//      val functionCodeXml = <Too><Response><FunctionCode>Bar</FunctionCode></Response></Too>
//      controller().extractFunctionCode(elementToOptionalNodeSeq(functionCodeXml)) shouldBe Some(FunctionCode("Bar"))
//    }
//
//    "extract IssueDateTime from xml payload when present" in {
//      controller().extractFunctionCode(elementToOptionalNodeSeq(ValidXML)) shouldBe None
//
//      val functionCodeXml = <Too><Response><IssueDateTime><DateTimeString>20000101</DateTimeString></IssueDateTime></Response></Too>
//      controller().extractIssueDateTime(elementToOptionalNodeSeq(functionCodeXml), None) shouldBe Some(IssueDateTime("20000101"))
//    }
//
//    "extract IssueDateTime from header when present and no issueDateTime in xml" in {
//      val functionCodeXml = <Too><Response></Response></Too>
//      val issueDateTimeHeader = Some(issueDateTime)
//      controller().extractIssueDateTime(elementToOptionalNodeSeq(functionCodeXml), issueDateTimeHeader) shouldBe Some(IssueDateTime(issueDateTime))
//    }
//
//    "extract IssueDateTime from xml and not from header" in {
//      val functionCodeXml = <Too><Response><IssueDateTime><DateTimeString>20000101</DateTimeString></IssueDateTime></Response></Too>
//      val issueDateTimeHeader = Some(issueDateTime)
//      controller().extractIssueDateTime(elementToOptionalNodeSeq(functionCodeXml), issueDateTimeHeader) shouldBe Some(IssueDateTime("20000101"))
//    }
//
//    "extract Mrn from xml payload when present" in {
//      controller().extractFunctionCode(elementToOptionalNodeSeq(ValidXML)) shouldBe None
//
//      val functionCodeXml = <Too><Response><Declaration><ID>123456</ID></Declaration></Response></Too>
//      controller().extractMrn(elementToOptionalNodeSeq(functionCodeXml)) shouldBe Some(Mrn("123456"))
//    }
  }
//
//  "CustomsNotificationController" should {
//    "when blocked-count endpoint is called" should {
//      "respond with status 200 for valid request" in {
//        when(mockRepo.blockedCount(clientId1)).thenReturn(Future.successful(2))
//
//        testSubmitResult(ValidBlockedCountRequest, controller.()) { result =>
//          status(result) shouldBe OK
//          contentAsString(result) shouldBe "<?xml version='1.0' encoding='UTF-8'?>\n<pushNotificationBlockedCount>2</pushNotificationBlockedCount>"
//          contentType(result) shouldBe Some(MimeTypes.XML)
//        }
//        verifyLog("info", "blocked count of 2 returned", mockLogger)
//      }
//
//      "respond with status 400 for missing client id header" in {
//        when(mockRepo.blockedCount(clientId1)).thenReturn(Future.successful(2))
//
//        testSubmitResult(InvalidBlockedCountRequest, controller.blockedCount()) { result =>
//          status(result) shouldBe 400
//          await(result) shouldBe errorBadRequest("X-Client-ID required").XmlResult
//        }
//        verifyLogWithHeaders("errorWithHeaders", "missing X-Client-ID header when calling blocked-count endpoint", mockLogger)
//      }
//
//      "respond with 500 when unexpected failure happens" in {
//        when(mockRepo.blockedCount(clientId1)).thenReturn(Future.failed(emulatedServiceFailure))
//
//        testSubmitResult(ValidBlockedCountRequest, controller.blockedCount()) { result =>
//          status(result) shouldBe INTERNAL_SERVER_ERROR
//          await(result) shouldBe ErrorInternalServerError.XmlResult
//        }
//        verifyLog("error", "unable to get blocked count due to java.lang.UnsupportedOperationException: Emulated service failure.", mockLogger)
//      }
//    }

  private def testSubmitResult(request: Request[AnyContent])(test: Future[Result] => Unit): Unit = {
    val result = controller().submit().apply(request)
    test(result)
  }

  private def elementToOptionalNodeSeq(el: Elem): Option[NodeSeq] = Some(el)
}
