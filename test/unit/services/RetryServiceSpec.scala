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

import org.bson.types.ObjectId
import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import unit.services.RetryServiceSpec.anotherNotification
import util.TestData

import scala.concurrent.Future

class RetryServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {


  private val mockNotificationRepo = mock[NotificationRepo]
  private val mockSendNotificationService = mock[SendNotificationService]
  private val mockApiSubsFieldsConnector = mock[ApiSubscriptionFieldsConnector]
  private val mockNewHcService = new HeaderCarrierService() {
    override def newHc(): HeaderCarrier = TestData.HeaderCarrier
  }
  private val mockMetricsConnector = mock[MetricsConnector]
  private val mockNotificationLogger = mock[NotificationLogger]

  private val service: RetryService =
    new RetryService(
      mockNotificationRepo,
      mockSendNotificationService,
      mockApiSubsFieldsConnector,
      mockNewHcService,
      mockMetricsConnector,
      mockNotificationLogger)(Helpers.stubControllerComponents().executionContext)

  "RetryService.processFailedAndNotBlocked" when {
    "called" should {
      "keep retrying until no more outstanding notifications exist" in {
        when(mockNotificationRepo.getSingleOutstanding())
          .thenReturn(
            Future.successful(Right(Some(TestData.Notification))),
            Future.successful(Right(Some(anotherNotification))),
            Future.successful(Right(None)))

        when(mockApiSubsFieldsConnector.get(eqTo(TestData.NewClientSubscriptionId))(eqTo(TestData.HeaderCarrier)))
          .thenReturn(
            Future.successful(Right(ApiSubscriptionFieldsConnector.Success(TestData.ApiSubscriptionFields))),
            Future.successful(Right(ApiSubscriptionFieldsConnector.Success(TestData.ApiSubscriptionFields)))
          )

        when(mockSendNotificationService.send(eqTo(TestData.Notification), eqTo(TestData.PushCallbackData))(eqTo(TestData.HeaderCarrier), *, *))
          .thenReturn(Future.successful(Right(())))

        when(mockSendNotificationService.send(eqTo(anotherNotification), eqTo(TestData.PushCallbackData))(eqTo(TestData.HeaderCarrier), *, *))
          .thenReturn(Future.successful(Right(())))

        service.retryFailedAndNotBlocked().map { _ =>
          verify(mockSendNotificationService).send(eqTo(TestData.Notification), eqTo(TestData.PushCallbackData))(eqTo(TestData.HeaderCarrier), *, *)
          verify(mockSendNotificationService).send(eqTo(anotherNotification), eqTo(TestData.PushCallbackData))(eqTo(TestData.HeaderCarrier), *, *)
          succeed
        }
      }
    }
  }
}

object RetryServiceSpec {
  val anotherNotificationId = new ObjectId("bbbbbbbbbbbbbbbbbbbbbbbb")
  val anotherNotification = TestData.Notification.copy(id = anotherNotificationId)
}
