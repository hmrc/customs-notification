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
import uk.gov.hmrc.customs.notification.repositories.utils.Errors.MongoDbError
import uk.gov.hmrc.customs.notification.repositories.{BlockedCsidRepository, NotificationRepository}
import uk.gov.hmrc.customs.notification.services.SendService.{ClientError, ServerError, Success}
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.TestData.Implicits.{EmptyAuditContext, HeaderCarrier}

import java.time.ZonedDateTime
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SendServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockSendConnector = mock[SendConnector]
  private val mockNotificationRepo = mock[NotificationRepository]
  private val mockBlockedCsidRepo = mock[BlockedCsidRepository]
  private val mockRetryAvailableAfterConfig = mock[RetryDelayConfig]
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TimeNow
  }
  private val service = new SendService(
    mockSendConnector,
    mockNotificationRepo,
    mockBlockedCsidRepo,
    mockRetryAvailableAfterConfig,
    mockDateTimeService)

  private val failedButNotBlockedRetryDelay = 10.minutes
  private val failedButNotBlockedAvailableAt = TimeNow.plusSeconds(failedButNotBlockedRetryDelay.toSeconds)
  private val failedAndBlockedRetryDelay = 150.seconds
  private implicit val notificationLogContext = models.LogContext(Notification)

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
          eqTo(PushCallbackData))(eqTo(HeaderCarrier), eqTo(notificationLogContext), eqTo(EmptyAuditContext)))

  "send" when {
    "making send request to the SendService" should {
      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Right(SendConnector.SuccessfullySent(InternalPushDescriptor))))
      }

      "return a Right(Success)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Right(Success))
      }

      behave like setNotificationStatusToSucceeded(setup(), Notification, PushCallbackData)
    }

    "the SendConnector returns a client error" should {
      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Left(SendConnector.ClientError)))
        when(mockNotificationRepo.setFailedButNotBlocked(
          id = eqTo(ObjectId),
          availableAt = eqTo(failedButNotBlockedAvailableAt))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Right(())))
      }

      "return a Right(ClientError)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Right(ClientError))
      }

      "change the status of the notification to FailedButNotBlocked in the repo" in {
        setup()
        service.send(Notification, PushCallbackData).map { _ =>
          verify(mockNotificationRepo).setFailedButNotBlocked(
            id = eqTo(ObjectId),
            availableAt = eqTo(failedButNotBlockedAvailableAt))(eqTo(notificationLogContext))
          succeed
        }
      }
    }

    "the SendConnector returns a server error" should {
      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Left(SendConnector.ServerError)))
        when(mockNotificationRepo.setFailedAndBlocked(eqTo(ObjectId))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Right(())))
      }

      "return a Right(ServerError)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Right(ServerError))
      }

      "set the current notification status to FailedAndBlocked in the repo" in {
        setup()
        service.send(Notification, PushCallbackData).map { _ =>
          verify(mockNotificationRepo).setFailedAndBlocked(eqTo(ObjectId))(eqTo(notificationLogContext))
          succeed
        }
      }
    }

    "the repo returns a MongoDbError when setting the current notification to FailedAndBlocked" should {
      val mongoDbError = MongoDbError("setting FailedAndBlocked for notification", Exception)

      def setup(): Unit = {
        whenSendConnectorCalled()
          .thenReturn(Future.successful(Left(SendConnector.ServerError)))
        when(mockNotificationRepo.setFailedAndBlocked(eqTo(ObjectId))(eqTo(notificationLogContext)))
          .thenReturn(Future.successful(Left(mongoDbError)))
      }

      "return a Left(SendError)" in {
        setup()
        service.send(Notification, PushCallbackData)
          .map(_ shouldBe Left(mongoDbError))
      }
    }
  }

  private def setNotificationStatusToSucceeded(setup: => Unit, n: models.Notification, c: models.SendData): Unit = {
    "deletes the notification" in {
      setup
      service.send(n, c).map { _ =>
        verify(mockNotificationRepo).setSucceeded(eqTo(ObjectId))(eqTo(notificationLogContext))
        succeed
      }
    }
  }
}