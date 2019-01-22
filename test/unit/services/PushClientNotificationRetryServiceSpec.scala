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
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.{CustomsNotificationMetricsConnector, GoogleAnalyticsSenderConnector, PushNotificationRetryConnector}
import uk.gov.hmrc.customs.notification.domain.CustomsNotificationsMetricsRequest
import uk.gov.hmrc.customs.notification.services.{DateTimeService, PushClientNotificationRetryService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubNotificationLogger
import util.TestData._

import scala.concurrent.Future

class PushClientNotificationRetryServiceSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterEach {

  private val mockPushNotificationRetryConnector = mock[PushNotificationRetryConnector]
  private val mockGAConnector = mock[GoogleAnalyticsSenderConnector]
  private val notificationLogger = new StubNotificationLogger(mock[CdsLogger])
  private val mockCustomsNotificationsMetricsConnector = mock[CustomsNotificationMetricsConnector]
  private val mockDateTimeService = mock[DateTimeService]
  private implicit val hc = HeaderCarrier()

  private val pushService = new PushClientNotificationRetryService(mockPushNotificationRetryConnector,
    mockGAConnector,notificationLogger, mockCustomsNotificationsMetricsConnector, mockDateTimeService)

  override protected def beforeEach(): Unit = {
    reset(mockPushNotificationRetryConnector, mockCustomsNotificationsMetricsConnector, mockDateTimeService)

    when(mockGAConnector.send(any(), any())(meq(hc))).thenReturn(Future.successful(()))
  }


  "PushClientNotificationRetryService" should {
    "return true and call metrics service when push is successful but no metrics start time exists" in {
      when(mockPushNotificationRetryConnector.send(PushNotificationRequest1)).thenReturn(Future.successful(true))

      val result = await(pushService.send(ApiSubscriptionFieldsOne, NotificationWorkItemWithMetricsTime1))

      verifyMetricsConnector()

      result shouldBe true
      eventually(verify(mockPushNotificationRetryConnector).send(meq(PushNotificationRequest1)))
    }

    "return true and do not call metrics service when push is successful but no metrics start time exists" in {
      when(mockPushNotificationRetryConnector.send(PushNotificationRequest1)).thenReturn(Future.successful(true))

      val result = await(pushService.send(ApiSubscriptionFieldsOne, NotificationWorkItem1))

      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)

      result shouldBe true
      eventually(verify(mockPushNotificationRetryConnector).send(meq(PushNotificationRequest1)))
    }

    "return false when push fails" in {
      when(mockPushNotificationRetryConnector.send(PushNotificationRequest1)).thenReturn(Future.failed(emulatedServiceFailure))

      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)
      val result = await(pushService.send(ApiSubscriptionFieldsOne, NotificationWorkItem1))
      result shouldBe false
    }
  }

  def verifyMetricsConnector(): Unit = {
    val metricsRequestCaptor: ArgumentCaptor[CustomsNotificationsMetricsRequest] = ArgumentCaptor.forClass(classOf[CustomsNotificationsMetricsRequest])
    Eventually.eventually(verify(mockCustomsNotificationsMetricsConnector, times(1)).post(metricsRequestCaptor.capture()))
    val metricsRequest = metricsRequestCaptor.getValue
    metricsRequest.conversationId.toString shouldBe conversationId.id.toString
    ()
  }
}
