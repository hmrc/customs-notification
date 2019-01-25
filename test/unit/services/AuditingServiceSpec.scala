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

package unit.services

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.config.ServicesConfig
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.AuditingService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubNotificationLogger
import util.ExternalServicesConfiguration.{Host, Port}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData
import util.TestData.{conversationId, internalPushNotificationRequest}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class AuditingServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit val patienceConfig = PatienceConfig(timeout = 5 seconds)

  private val stubNotificationLogger = new StubNotificationLogger(mock[CdsLogger])
  private val mockServicesConfig = mock[ServicesConfig]
  private val mockAuditConnector = mock[AuditConnector]

  override def beforeEach(): Unit = {
    org.mockito.Mockito.reset(mockServicesConfig)
    org.mockito.Mockito.reset(mockAuditConnector)
  }

  val auditingService = new AuditingService(stubNotificationLogger, mockServicesConfig, mockAuditConnector)

  "AuditingService" should {

    "call audit connector with correct payload for auditing successful notification" in {

      val mockAuditResult = mock[AuditResult]

      val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

      when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any[HeaderCarrier], any[ExecutionContext])).thenReturn(Future.successful(mockAuditResult))

      auditingService.auditSuccessfulNotification(internalPushNotificationRequest)

      eventually {
        verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])

        val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
        actualExtendedDataEvent.auditSource shouldBe "customs-notification"
        actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationOutboundCall"
        actualExtendedDataEvent.tags("x-conversation-id") shouldBe conversationId.toString
        actualExtendedDataEvent.tags("transactionName") shouldBe "customs-declaration-outbound-call"
        (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
        (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
        (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "SUCCESS"
        actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        actualExtendedDataEvent.generatedAt.toString() should have size 24
      }
    }

    "call audit connector with correct payload for auditing failed notification" in {

      val mockAuditResult = mock[AuditResult]

      val captor = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])

      when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any[HeaderCarrier], any[ExecutionContext])).thenReturn(Future.successful(mockAuditResult))

      auditingService.auditFailedNotification(internalPushNotificationRequest, Some("FailureReasonAbc123"))

      eventually {
        verify(mockAuditConnector).sendExtendedEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])

        val actualExtendedDataEvent: ExtendedDataEvent = captor.getValue
        actualExtendedDataEvent.auditSource shouldBe "customs-notification"
        actualExtendedDataEvent.auditType shouldBe "DeclarationNotificationOutboundCall"
        actualExtendedDataEvent.tags("x-conversation-id") shouldBe conversationId.toString
        actualExtendedDataEvent.tags("transactionName") shouldBe "customs-declaration-outbound-call"
        (actualExtendedDataEvent.detail \ "outboundCallUrl").as[String] shouldBe s"http://$Host:$Port/internal/notify"
        (actualExtendedDataEvent.detail \ "outboundCallAuthToken").as[String] shouldBe TestData.securityToken
        (actualExtendedDataEvent.detail \ "result").as[String] shouldBe "FAILURE"
        (actualExtendedDataEvent.detail \ "failureReason").as[String] shouldBe "FailureReasonAbc123"
        actualExtendedDataEvent.eventId should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
        actualExtendedDataEvent.generatedAt.toString() should have size 24
      }
    }

    "should log error when auditing fails" in {

      val mockNotificationLogger = mock[NotificationLogger]
      val auditingService = new AuditingService(mockNotificationLogger, mockServicesConfig, mockAuditConnector)

      when(mockAuditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any[HeaderCarrier], any[ExecutionContext])).thenReturn(Future.failed(new Exception))

      auditingService.auditFailedNotification(internalPushNotificationRequest, Some("FailureReasonAbc123"))

      eventually {
        PassByNameVerifier(mockNotificationLogger, "error")
          .withByNameParam[String]("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientSubscriptionId=ffff01f9-ec3b-4ede-b263-61b626dde232]failed to audit FAILURE event")
          .withByNameParamMatcher[Throwable](any[Throwable])
          .withParamMatcher(any[HeaderCarrier])
          .verify()
      }
    }
  }
}
