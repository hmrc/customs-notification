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

package unit.services

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.AuditingService
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent
import util.ExternalServicesConfiguration.{Host, Port}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData.{NotificationWorkItem1, conversationId, internalPushNotificationRequest}
import util.{TestData, UnitSpec}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

class AuditingServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5 seconds)

  private val mockLogger = mock[NotificationLogger]
  private val mockAuditConnector = mock[AuditConnector]
  private implicit val rm: RequestMetaData = TestData.requestMetaData
  implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId("ABC")))

  override def beforeEach(): Unit = {
    reset(mockAuditConnector)
  }

  val auditingService = new AuditingService(mockLogger, mockAuditConnector)

  "AuditingService" should {

    "call audit connector with correct payload for auditing notification received" in {

      val mockAuditResult = mock[AuditResult]

      val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

      when(mockAuditConnector.sendExtendedEvent(any())(any(), any())).thenReturn(Future.successful(mockAuditResult))

      auditingService.auditNotificationReceived(internalPushNotificationRequest)(NotificationWorkItem1, hc)

      eventually {
        verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any(), any())

        val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
        actualExtendedDataEvent.auditSource shouldBe "customs-notification"
        actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationInboundCall"

        actualExtendedDataEvent.tags.size shouldBe 5
        actualExtendedDataEvent.tags shouldBe Map("clientId" -> "ClientId",
          "notificationId" -> "58373a04-2c45-4f43-9ea2-74e56be2c6d7",
          "fieldsId" -> "eaca01f9-ec3b-4ede-b263-61b626dde232",
          "x-conversation-id" -> conversationId.toString,
          "transactionName" -> "customs-declaration-outbound-call")

        (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
        (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
        (actualExtendedDataEvent.detail \ "payload").as[String] shouldBe "<Foo>Bar</Foo>"
        (actualExtendedDataEvent.detail \ "payloadHeaders").as[String] should include("X-Request-ID,ABC") // Ignore request change
        (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "SUCCESS"
        actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        actualExtendedDataEvent.generatedAt.toString() should have size 24
      }
    }

    "call audit connector with correct payload for auditing successful notification for retries" in {

      val mockAuditResult = mock[AuditResult]

      val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

      when(mockAuditConnector.sendExtendedEvent(any())(any(), any())).thenReturn(Future.successful(mockAuditResult))

      auditingService.auditSuccessfulNotification(internalPushNotificationRequest)(NotificationWorkItem1, hc)

      eventually {
        verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any(), any())

        val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
        actualExtendedDataEvent.auditSource shouldBe "customs-notification"
        actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationOutboundCall"

        actualExtendedDataEvent.tags.size shouldBe 5
        actualExtendedDataEvent.tags shouldBe Map("clientId" -> "ClientId",
          "notificationId" -> "58373a04-2c45-4f43-9ea2-74e56be2c6d7",
          "fieldsId" -> "eaca01f9-ec3b-4ede-b263-61b626dde232",
          "x-conversation-id" -> conversationId.toString,
          "transactionName" -> "customs-declaration-outbound-call")

        (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
        (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
        (actualExtendedDataEvent.detail \ "payload").as[String] shouldBe ""
        (actualExtendedDataEvent.detail \ "payloadHeaders").as[String] should include("X-Request-ID,ABC") // Ignore request change
        (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "SUCCESS"
        actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        actualExtendedDataEvent.generatedAt.toString() should have size 24
      }
    }

    "call audit connector with correct payload for auditing successful notification" in {

      val mockAuditResult = mock[AuditResult]

      val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

      when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any(), any())).thenReturn(Future.successful(mockAuditResult))

      auditingService.auditSuccessfulNotification(internalPushNotificationRequest)

      eventually {
        verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any(), any())

        val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
        actualExtendedDataEvent.auditSource shouldBe "customs-notification"
        actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationOutboundCall"

        actualExtendedDataEvent.tags.size shouldBe 8
        actualExtendedDataEvent.tags shouldBe Map("clientId" -> "ClientId",
          "notificationId" -> "58373a04-2c45-4f43-9ea2-74e56be2c6d7",
          "fieldsId" -> "eaca01f9-ec3b-4ede-b263-61b626dde232",
          "functionCode" -> "01",
          "x-conversation-id" -> conversationId.toString,
          "issueDate" -> "20190925104103Z",
          "mrn" -> "19GB3955NQ36213969",
          "transactionName" -> "customs-declaration-outbound-call")

        (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
        (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
        (actualExtendedDataEvent.detail \ "payloadHeaders").as[String] should include("X-Request-ID,ABC") // Ignore request change
        (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "SUCCESS"
        actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        actualExtendedDataEvent.generatedAt.toString() should have size 24
      }
    }

    "call audit connector with correct payload for auditing failed notification for retries" in {

      val mockAuditResult = mock[AuditResult]

      val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

      when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any(), any())).thenReturn(Future.successful(mockAuditResult))

      auditingService.auditFailedNotification(internalPushNotificationRequest, Some("FailureReasonAbc123"))(NotificationWorkItem1, hc)

      eventually {
        verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any(), any())

        val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
        actualExtendedDataEvent.auditSource shouldBe "customs-notification"
        actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationOutboundCall"
        actualExtendedDataEvent.tags.size shouldBe 5
        actualExtendedDataEvent.tags shouldBe Map("clientId" -> "ClientId",
          "notificationId" -> "58373a04-2c45-4f43-9ea2-74e56be2c6d7",
          "fieldsId" -> "eaca01f9-ec3b-4ede-b263-61b626dde232",
          "x-conversation-id" -> conversationId.toString,
          "transactionName" -> "customs-declaration-outbound-call")
        (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
        (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
        (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "FAILURE"
        (actualExtendedDataEvent.detail \ "failureReason").as[String] shouldBe "FailureReasonAbc123"
        actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        actualExtendedDataEvent.generatedAt.toString() should have size 24
      }
    }
      "call audit connector with correct payload for auditing failed notification" in {

        val mockAuditResult = mock[AuditResult]

        val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

        when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any(), any())).thenReturn(Future.successful(mockAuditResult))

        auditingService.auditFailedNotification(internalPushNotificationRequest, Some("FailureReasonAbc123"))

        eventually {
          verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any(), any())

          val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
          actualExtendedDataEvent.auditSource shouldBe "customs-notification"
          actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationOutboundCall"
          actualExtendedDataEvent.tags.size shouldBe 8
          actualExtendedDataEvent.tags shouldBe Map("clientId" -> "ClientId",
            "notificationId" -> "58373a04-2c45-4f43-9ea2-74e56be2c6d7",
            "fieldsId" -> "eaca01f9-ec3b-4ede-b263-61b626dde232",
            "functionCode" -> "01",
            "x-conversation-id" -> conversationId.toString,
            "issueDate" -> "20190925104103Z",
            "mrn" -> "19GB3955NQ36213969",
            "transactionName" -> "customs-declaration-outbound-call")
          (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
          (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
          (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "FAILURE"
          (actualExtendedDataEvent.detail \ "failureReason").as[String] shouldBe "FailureReasonAbc123"
          actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
          actualExtendedDataEvent.generatedAt.toString() should have size 24
        }
    }

    "should log error when auditing fails" in {

      val auditingService = new AuditingService(mockLogger, mockAuditConnector)

      when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any(), any())).thenReturn(Future.failed(new Exception))

      auditingService.auditFailedNotification(internalPushNotificationRequest, Some("FailureReasonAbc123"))

      eventually {
        PassByNameVerifier(mockLogger, "error")
          .withByNameParam[String]("failed to audit FAILURE event")
          .withByNameParamMatcher[Throwable](any[Throwable])
          .withParamMatcher(any[HasId])
          .verify()
      }
    }
  }
}
