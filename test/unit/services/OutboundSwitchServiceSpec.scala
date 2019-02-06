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

import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.connectors.{ExternalPushConnector, InternalPushConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.customs.notification.services.{AuditingService, OutboundSwitchService}
import uk.gov.hmrc.http.{HttpException, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData

import scala.concurrent.Future

class OutboundSwitchServiceSpec extends UnitSpec with MockitoSugar with Eventually {

  trait SetUp {
    val mockConfigService = mock[ConfigService]
    val mockPushNotificationConfig = mock[PushNotificationConfig]
    val mockExternalConnector = mock[ExternalPushConnector]
    val mockInternalPushService = mock[InternalPushConnector]
    val mockHttpResponse = mock[HttpResponse]
    val mockAuditingService = mock[AuditingService]
    val mockLogger = mock[NotificationLogger]
    implicit val rm = TestData.requestMetaData
    val switcher = new OutboundSwitchService(mockConfigService, mockExternalConnector, mockInternalPushService, mockAuditingService, mockLogger)
  }

  "OutboundSwitchService" should {

    "route internally when config property push.internal.clientIds contains a matching clientId" in new SetUp {
      when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))
      when(mockInternalPushService.send(any[PushNotificationRequest])).thenReturn(Future.successful(Right(mockHttpResponse)))

      private val actual = await(switcher.send(ClientIdOne, pnrOne))

      actual shouldBe Right(mockHttpResponse)
      verifyZeroInteractions(mockExternalConnector)
      verify(mockInternalPushService).send(ameq(pnrOne))
      eventually {
        verify(mockAuditingService).auditSuccessfulNotification(pnrOne)
      }
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push internally")
        .withParamMatcher(any[HasId])
        .verify()
    }

    "audit internal push when config property push.internal.clientIds contains a matching clientId and push fails with HttpException" in new SetUp {
      when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))
      val httpResultError = HttpResultError(BAD_REQUEST, new HttpException("BOOM", BAD_REQUEST))
      when(mockInternalPushService.send(any[PushNotificationRequest])).thenReturn(Left(httpResultError))

      private val actual = await(switcher.send(ClientIdOne, pnrOne))

      actual shouldBe Left(httpResultError)
      verifyZeroInteractions(mockExternalConnector)
      verify(mockInternalPushService).send(ameq(pnrOne))
      eventually {

        verify(mockAuditingService).auditFailedNotification(pnrOne, Some("status: 400 body: BOOM"))
      }
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push internally")
        .withParamMatcher(any[HasId])
        .verify()

    }

    "not audit internal push when config property push.internal.clientIds contains a matching clientId and push fails with NON HttpException" in new SetUp {
      when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))
      val nonHttpError = NonHttpError(new Exception("BOOM"))
      when(mockInternalPushService.send(any[PushNotificationRequest])).thenReturn(Left(nonHttpError))

      private val actual = await(switcher.send(ClientIdOne, pnrOne))

      actual shouldBe Left(nonHttpError)
      verifyZeroInteractions(mockExternalConnector)
      verify(mockInternalPushService).send(ameq(pnrOne))
      eventually {
        verifyZeroInteractions(mockAuditingService)
      }
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push internally")
        .withParamMatcher(any[HasId])
        .verify()

    }

    "route externally when config property push.internal.clientIds does not contains a matching clientId" in new SetUp {
      when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.internalClientIds).thenReturn(Seq.empty)
      when(mockExternalConnector.send(any[PushNotificationRequest])).thenReturn(Future.successful(Right(mockHttpResponse)))

      private val actual = await(switcher.send(ClientIdOne, pnrOne))

      actual shouldBe Right(mockHttpResponse)
      verify(mockExternalConnector).send(ameq(pnrOne))
      verifyZeroInteractions(mockInternalPushService)
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("About to push externally")
        .withParamMatcher(any[HasId])
        .verify()
    }

  }

}
