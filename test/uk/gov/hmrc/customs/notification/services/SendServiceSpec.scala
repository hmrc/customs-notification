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

package uk.gov.hmrc.customs.notification.services

import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.FutureOutcome
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import uk.gov.hmrc.customs.notification.config.RetryDelayConfig
import uk.gov.hmrc.customs.notification.connectors.SendConnector
import uk.gov.hmrc.customs.notification.connectors.SendConnector.Request.InternalPushDescriptor
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.MongoDbError
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.TestData.Implicits.*

import java.time.ZonedDateTime
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SendServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockSendConnector = mock[SendConnector]
  private val mockRepo = mock[Repository]
  private val mockRetryAvailableAfterConfig = mock[RetryDelayConfig]
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TimeNow
  }
  private val service = new SendService(
    mockSendConnector,
    mockRepo,
    mockRetryAvailableAfterConfig,
    mockDateTimeService)

  private val failedButNotBlockedRetryDelay = 10.minutes
  private val failedButNotBlockedAvailableAt = TimeNow.plusSeconds(failedButNotBlockedRetryDelay.toSeconds)
  private val failedAndBlockedRetryDelay = 150.seconds
  private val failedAndBlockedAvailableAt = TimeNow.plusSeconds(failedAndBlockedRetryDelay.toSeconds)
  private val notificationLogContext = models.LogContext(Notification)
  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    when(mockRetryAvailableAfterConfig.failedButNotBlocked).thenReturn(failedButNotBlockedRetryDelay)
    when(mockRetryAvailableAfterConfig.failedAndBlocked).thenReturn(failedAndBlockedRetryDelay)

    super.withFixture(test)
  }

  private def whenSendConnectorCalled() =
    when(
      mockSendConnector
        .send(
          eqTo(Notification),
          eqTo(PushCallbackData))(eqTo(HeaderCarrier), eqTo(LogContext), eqTo(EmptyAuditContext)))

  "send" when {
    "making send request to the SendService" should {
      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Right(SendConnector.SuccessfullySent(InternalPushDescriptor))))
      }

      "return a Right(Unit)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Right(()))
      }

      behave like setNotificationStatusToSucceeded(setup(), Notification, PushCallbackData)
    }

    "has the SendConnector return a client error" should {
      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Left(SendConnector.ClientError)))
        when(mockRepo.setFailedButNotBlocked(
          id = eqTo(ObjectId),
          availableAt = eqTo(failedButNotBlockedAvailableAt))(eqTo(models.LogContext(Notification))))
          .thenReturn(Future.successful(Right(())))
      }

      "return a Right(Unit)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Right(()))
      }

      "change the status of the notification to FailedButNotBlocked in the repo" in {
        setup()
        service.send(Notification, PushCallbackData).map { _ =>
          verify(mockRepo).setFailedButNotBlocked(
            id = eqTo(ObjectId),
            availableAt = eqTo(failedButNotBlockedAvailableAt))(eqTo(models.LogContext(Notification)))
          succeed
        }
      }
    }

    "has the SendConnector return a server error" should {
      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Left(SendConnector.ServerError)))
        when(mockRepo.setFailedAndBlocked(eqTo(ObjectId), eqTo(failedAndBlockedAvailableAt))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Right(())))
        when(mockRepo.blockAllFailedButNotBlocked(eqTo(TranslatedCsid))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Right(1)))
      }

      "return a Right(())" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Right(()))
      }

      "set the current notification status to FailedAndBlocked in the repo" in {
        setup()
        service.send(Notification, PushCallbackData).map { _ =>
          verify(mockRepo).setFailedAndBlocked(eqTo(ObjectId), eqTo(failedAndBlockedAvailableAt))(eqTo(notificationLogContext))
          succeed
        }
      }

      "set all previous FailedButNotBlocked notifications for the client subscription ID to blocked" in {
        setup()
        service.send(Notification, PushCallbackData).map { _ =>
          verify(mockRepo).blockAllFailedButNotBlocked(eqTo(TranslatedCsid))(eqTo(notificationLogContext))
          succeed
        }
      }
    }

    "has the repo return a MongoDbError when setting the current notification to FailedAndBlocked" should {
      val mongoDbError = MongoDbError("setting FailedAndBlocked for notification", Exception)

      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Left(SendConnector.ServerError)))
        when(mockRepo.setFailedAndBlocked(eqTo(ObjectId), eqTo(failedAndBlockedAvailableAt))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Right(())))
        when(mockRepo.blockAllFailedButNotBlocked(eqTo(TranslatedCsid))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Left(mongoDbError)))
      }

      "return a Left(SendError)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Left(mongoDbError))
      }

      "still block previously FailedButNotBlocked notifications for that client subscription ID" in {
        setup()
        service.send(Notification, PushCallbackData).map { _ =>
          verify(mockRepo).blockAllFailedButNotBlocked(eqTo(TranslatedCsid))(eqTo(notificationLogContext))
          succeed
        }
      }
    }
  }

  private def setNotificationStatusToSucceeded(setup: => Unit, n: models.Notification, c: models.SendData): Unit = {
    "set notification status to Succeeded" in {
      setup
      service.send(n, c).map { _ =>
        verify(mockRepo).setSucceeded(eqTo(ObjectId))(eqTo(LogContext))
        succeed
      }
    }
  }
}