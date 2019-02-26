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
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationsMetricsRequest, HasId}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationMetricsService, DateTimeService}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationMetricsServiceSpec extends UnitSpec with MockitoSugar {

  trait SetUp {
    private[CustomsNotificationMetricsServiceSpec] val mockLogger = mock[NotificationLogger]
    private[CustomsNotificationMetricsServiceSpec] lazy val mockMetricsConnector = mock[CustomsNotificationMetricsConnector]
    private[CustomsNotificationMetricsServiceSpec] lazy val mockDateTimeService = mock[DateTimeService]
    private[CustomsNotificationMetricsServiceSpec] val service = new CustomsNotificationMetricsService(mockLogger, mockMetricsConnector, mockDateTimeService)
    private[CustomsNotificationMetricsServiceSpec] val metricsRequest = CustomsNotificationsMetricsRequest(
      "NOTIFICATION",
      NotificationWorkItemWithMetricsTime1.notification.conversationId,
      TimeReceivedDateTime.toZonedDateTime,
      MetricsStartTimeZoned
    )
    private[CustomsNotificationMetricsServiceSpec] def verifyMetricsConnector(): Unit = {
      val metricsRequestCaptor: ArgumentCaptor[CustomsNotificationsMetricsRequest] = ArgumentCaptor.forClass(classOf[CustomsNotificationsMetricsRequest])
      Eventually.eventually(verify(mockMetricsConnector, times(1)).post(metricsRequestCaptor.capture()))
      val metricsRequest = metricsRequestCaptor.getValue
      metricsRequest.conversationId.toString shouldBe conversationId.id.toString
      ()
    }
  }

  "CustomsNotificationMetricsService" should {
    "send notification metric when NotificationWorkItem has a metrics time" in new SetUp {
      when(mockMetricsConnector.post(metricsRequest)).thenReturn(Future.successful())
      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(MetricsStartTimeZoned)

      await(service.notificationMetric(NotificationWorkItemWithMetricsTime1))

      verifyMetricsConnector()
    }

    "not send notification metric when NotificationWorkItem has no metrics time" in new SetUp {

      await(service.notificationMetric(NotificationWorkItem1))

      verifyZeroInteractions(mockMetricsConnector)
      verifyZeroInteractions(mockDateTimeService)
    }

    "log error when metrics connector returns failed Future" in new SetUp {
      when(mockMetricsConnector.post(metricsRequest)).thenReturn(Future.failed(emulatedServiceFailure))
      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(MetricsStartTimeZoned)

      await(service.notificationMetric(NotificationWorkItemWithMetricsTime1))

      PassByNameVerifier(mockLogger, "error")
        .withByNameParam("Error calling customs metrics service")
        .withByNameParam(emulatedServiceFailure)
        .withParamMatcher(any[HasId])
        .verify()
    }

  }

}
