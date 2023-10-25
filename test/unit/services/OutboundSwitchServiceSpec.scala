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

package unit.services

import java.net.URL
import java.util.UUID
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.config.{AppConfig, NotificationConfig}
import uk.gov.hmrc.customs.notification.connectors.{ExternalPushConnector, InternalPushConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.error.{HttpResultError, NonHttpError}
import uk.gov.hmrc.customs.notification.models.requests.{Header, PushNotificationRequest, PushNotificationRequestBody}
import uk.gov.hmrc.customs.notification.models.{CallbackUrl, ConversationId, HasId}
import uk.gov.hmrc.customs.notification.services.{AuditingService, OutboundSwitchService}
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._
import util.UnitSpec

import scala.concurrent.Future

class OutboundSwitchServiceSpec extends UnitSpec with MockitoSugar with Eventually {

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  
  trait SetUp {
    val mockConfigService = mock[AppConfig]
    val mockNotificationConfig = mock[NotificationConfig]
    val mockExternalConnector = mock[ExternalPushConnector]
    val mockInternalPushService = mock[InternalPushConnector]
    val mockHttpResponse = mock[HttpResponse]
    val mockAuditingService = mock[AuditingService]
    val mockLogger = mock[NotificationLogger]
    implicit val rm = requestMetaData
    implicit val ec = Helpers.stubControllerComponents().executionContext
    val switcher = new OutboundSwitchService(mockConfigService, mockExternalConnector, mockInternalPushService, mockAuditingService, mockLogger)
  }

  private val Headers = Seq(Header("h1", "v1"))
  private val PayloadOne = "PAYLOAD_ONE"
  private val ConversationIdOne = ConversationId(UUID.fromString("caca01f9-ec3b-4ede-b263-61b626dde231"))
  private val pnrOne = PushNotificationRequest(CsidOne.id.toString, PushNotificationRequestBody(CallbackUrl(Some(new URL("http://URL"))), "SECURITY_TOKEN", ConversationIdOne.id.toString, Headers, PayloadOne))

  "OutboundSwitchService" should {

    "route internally when config property internal.clientIds contains a matching clientId" in new SetUp {
      when(mockConfigService.notificationConfig).thenReturn(mockNotificationConfig)
      when(mockNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))
      when(mockInternalPushService.post(any[PushNotificationRequest])(any())).thenReturn(Future.successful(Right(mockHttpResponse)))

      private val actual = await(switcher.sendPushNotificationRequest(ClientIdOne, pnrOne))

      actual shouldBe Right(mockHttpResponse)
      verifyNoInteractions(mockExternalConnector)
      verify(mockInternalPushService).post(ameq(pnrOne))(any())
      eventually {
        verify(mockAuditingService).auditSuccessfulNotification(pnrOne)
      }
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push internally")
        .withParamMatcher(any[HasId])
        .verify()
    }

    "audit internal push when config property internal.clientIds contains a matching clientId and push fails with HttpException" in new SetUp {
      when(mockConfigService.notificationConfig).thenReturn(mockNotificationConfig)
      when(mockNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))
      val httpResultError = HttpResultError(BAD_REQUEST, new HttpException("BOOM", BAD_REQUEST))
      when(mockInternalPushService.post(any[PushNotificationRequest])(any())).thenReturn(Left(httpResultError))

      private val actual = await(switcher.sendPushNotificationRequest(ClientIdOne, pnrOne))

      actual shouldBe Left(httpResultError)
      verifyNoInteractions(mockExternalConnector)
      verify(mockInternalPushService).post(ameq(pnrOne))(any())
      eventually {

        verify(mockAuditingService).auditFailedNotification(pnrOne, Some("status: 400 body: BOOM"))
      }
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push internally")
        .withParamMatcher(any[HasId])
        .verify()

    }

    "not audit internal push when config property internal.clientIds contains a matching clientId and push fails with NON HttpException" in new SetUp {
      when(mockConfigService.notificationConfig).thenReturn(mockNotificationConfig)
      when(mockNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))
      val nonHttpError = NonHttpError(new Exception("BOOM"))
      when(mockInternalPushService.post(any[PushNotificationRequest])(any())).thenReturn(Left(nonHttpError))

      private val actual = await(switcher.sendPushNotificationRequest(ClientIdOne, pnrOne))

      actual shouldBe Left(nonHttpError)
      verifyNoInteractions(mockExternalConnector)
      verify(mockInternalPushService).post(ameq(pnrOne))(any())
      eventually {
        verifyNoInteractions(mockAuditingService)
      }
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push internally")
        .withParamMatcher(any[HasId])
        .verify()

    }

    "route externally when config property internal.clientIds does not contains a matching clientId" in new SetUp {
      when(mockConfigService.notificationConfig).thenReturn(mockNotificationConfig)
      when(mockNotificationConfig.internalClientIds).thenReturn(Seq.empty)
      when(mockExternalConnector.post(any[PushNotificationRequest])(any(), any[HasId])).thenReturn(Future.successful(Right(mockHttpResponse)))

      private val actual = await(switcher.sendPushNotificationRequest(ClientIdOne, pnrOne))

      actual shouldBe Right(mockHttpResponse)
      verify(mockExternalConnector).post(ameq(pnrOne))(any(), any[HasId])
      verifyNoInteractions(mockInternalPushService)
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push externally")
        .withParamMatcher(any[HasId])
        .verify()
    }

  }

}
