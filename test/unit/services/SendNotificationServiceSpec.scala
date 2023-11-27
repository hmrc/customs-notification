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

import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.FutureOutcome
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.connectors.{SendNotificationConnector => SendCon}
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models.{ClientSendData, Notification}
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.repo.NotificationRepo.MongoDbError
import uk.gov.hmrc.customs.notification.services.SendNotificationService.SendNotificationError
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Succeeded
import util.TestData

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.Future

class SendNotificationServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockSendNotificationConnector = mock[SendCon]
  private val mockRepo = mock[NotificationRepo]
  private val mockConfig = mock[AppConfig]
  private val mockDateTimeService = new DateTimeService{
    override def now(): ZonedDateTime = TestData.TimeNow
  }
  private val mockNotificationLogger = mock[NotificationLogger]
  private val service = new SendNotificationService(
    mockSendNotificationConnector,
    mockRepo,
    mockConfig,
    mockDateTimeService,
    mockNotificationLogger)(Helpers.stubControllerComponents().executionContext)
  implicit private val hc: HeaderCarrier = TestData.HeaderCarrier

  private val retryDelay = 10
  private val retryOn = ZonedDateTime.of(2023, 12, 25, 0, 10, 1, 0, ZoneId.of("UTC")) // scalastyle:off magic.number

  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    when(mockConfig.failedAndNotBlockedAvailableAfterMinutes).thenReturn(retryDelay)
    super.withFixture(test)
  }


  "send" when {
    "called with overload" should {
      "be equivalent to other overload and passing in Notification as toAudit argument" in {
        when(mockSendNotificationConnector
          .send(
            eqTo(TestData.Notification),
            eqTo(TestData.PushCallbackData),
            eqTo(TestData.Notification))(*, *, eqTo(TestData.HeaderCarrier)))
          .thenReturn(Future.successful(Right(SendCon.SuccessfullySent(SendCon.ExternalPush(TestData.PushCallbackData)))))
        when(mockRepo.setStatus(eqTo(TestData.ObjectId), eqTo(ProcessingStatus.Succeeded)))
          .thenReturn(Future.successful(Right(())))
        for {
          first <- service.send(TestData.Notification, TestData.PushCallbackData)
          second <- service.send(TestData.Notification, TestData.Notification, TestData.PushCallbackData)
        } yield {
          first shouldBe second
        }
      }
    }

    "making send request to the SendNotificationService" should {
      def setup(): Unit = {
        when(mockSendNotificationConnector
          .send(
            eqTo(TestData.Notification),
            eqTo(TestData.PushCallbackData),
            eqTo(TestData.Notification))(*, *, eqTo(TestData.HeaderCarrier)))
          .thenReturn(Future.successful(Right(SendCon.SuccessfullySent(SendCon.InternalPush(TestData.PushCallbackData)))))
      }

      "return a Right(Unit)" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData)
          .map(_ shouldBe Right(()))
      }

      behave like setNotificationStatusToSucceeded(setup(), TestData.Notification, TestData.PushCallbackData)
    }

    "has the SendNotificationConnector return a client error" should {
      def setup(): Unit = {
        when(mockSendNotificationConnector
          .send(
            eqTo(TestData.Notification),
            eqTo(TestData.PushCallbackData),
            eqTo(TestData.Notification))(*, *, eqTo(TestData.HeaderCarrier)))
          .thenReturn(Future.successful(Left(SendCon.ClientSendError(None))))
        when(mockRepo.incrementFailureCount(eqTo(TestData.ObjectId)))
          .thenReturn(Future.successful(Right(())))
        when(mockRepo.setFailedButNotBlocked(eqTo(TestData.ObjectId), eqTo(None), eqTo(retryOn)))
          .thenReturn(Future.successful(Right(())))
      }

      "return a Right(Unit)" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData)
          .map(_ shouldBe Right(()))
      }

      "log an error" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockNotificationLogger).error(
            eqTo("Sending notification failed. Setting availableAt to 2023-12-25T00:10:01Z[UTC]"),
            eqTo(TestData.Notification))(*)
          succeed
        }
      }

      "increment the failure count in the repo" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockRepo).incrementFailureCount(eqTo(TestData.ObjectId))
          succeed
        }
      }

      "change the status of the notification to FailedButNotBlocked in the repo" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockRepo).setFailedButNotBlocked(
            id = eqTo(TestData.ObjectId),
            maybeHttpStatus = eqTo(None),
            availableAt = eqTo(retryOn))
          succeed
        }
      }
    }

    "has the SendNotificationConnector return a server error" should {
      def setup(): Unit = {
        when(mockSendNotificationConnector
          .send(
            eqTo(TestData.Notification),
            eqTo(TestData.PushCallbackData),
            eqTo(TestData.Notification))(*, *, eqTo(TestData.HeaderCarrier)))
          .thenReturn(Future.successful(Left(SendCon.ServerSendError(INTERNAL_SERVER_ERROR))))
        when(mockRepo.incrementFailureCount(eqTo(TestData.ObjectId)))
          .thenReturn(Future.successful(Right(())))
        when(mockRepo.setFailedAndBlocked(eqTo(TestData.ObjectId), eqTo(Some(INTERNAL_SERVER_ERROR))))
          .thenReturn(Future.successful(Right(())))
        when(mockRepo.blockAllFailedButNotBlocked(eqTo(TestData.NewClientSubscriptionId)))
          .thenReturn(Future.successful(Right(1)))
      }

      "return a Right(())" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData)
          .map(_ shouldBe Right(()))
      }

      "log an error" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockNotificationLogger).error(
            eqTo("Sending notification failed. Blocking notifications for client subscription ID"),
            eqTo(TestData.Notification))(*)
          succeed
        }
      }

      "set the current notification status to FailedAndBlocked in the repo" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockRepo).setFailedAndBlocked(
            eqTo(TestData.ObjectId),
            eqTo(Some(INTERNAL_SERVER_ERROR)))
          succeed
        }
      }

      "set all previous FailedButNotBlocked notifications for the client subscription ID to blocked" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockRepo).blockAllFailedButNotBlocked(eqTo(TestData.NewClientSubscriptionId))
          succeed
        }
      }
    }

    "has the repo return a MongoDbError when setting the current notification to FailedAndBlocked" should {
      def setup(): Unit = {
        when(mockSendNotificationConnector
          .send(
            eqTo(TestData.Notification),
            eqTo(TestData.PushCallbackData),
            eqTo(TestData.Notification))(*, *, eqTo(TestData.HeaderCarrier)))
          .thenReturn(Future.successful(Left(SendCon.ServerSendError(INTERNAL_SERVER_ERROR))))
        when(mockRepo.incrementFailureCount(eqTo(TestData.ObjectId)))
          .thenReturn(Future.successful(Right(())))
        when(mockRepo.blockAllFailedButNotBlocked(eqTo(TestData.NewClientSubscriptionId)))
          .thenReturn(Future.successful(Left(MongoDbError("setting failed and blocked for notification", TestData.Exception))))
      }

      "return a Left(SendNotificationError)" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData)
          .map(_ shouldBe Left(SendNotificationError))
      }

      "still block previously FailedButNotBlocked notifications for that client subscription ID" in {
        setup()
        service.send(TestData.Notification, TestData.PushCallbackData).map { _ =>
          verify(mockRepo).blockAllFailedButNotBlocked(eqTo(TestData.NewClientSubscriptionId))
          succeed
        }
      }
    }
  }

  private def setNotificationStatusToSucceeded(setup: => Unit, n: Notification, c: ClientSendData): Unit = {
    "set notification status to Succeeded" in {
      setup
      service.send(n, c).map { _ =>
        verify(mockRepo).setStatus(eqTo(TestData.ObjectId), eqTo(Succeeded))
        succeed
      }
    }
  }
}