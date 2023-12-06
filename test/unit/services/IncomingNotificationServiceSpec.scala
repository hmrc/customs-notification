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
import uk.gov.hmrc.customs.notification.connectors.{ClientDataConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedAndBlocked, SavedToBeSent}
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.MongoDbError
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, InternalServiceError}
import uk.gov.hmrc.customs.notification.services._
import util.TestData
import util.TestData._
import util.TestData.Implicits._

import scala.concurrent.Future

class IncomingNotificationServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockNotificationRepo = mock[Repository]
  private val mockSendService = mock[SendService]
  private val mockApiSubsFieldsConnector = mock[ClientDataConnector]
  private val mockAuditService = mock[AuditService]
  private val mockMetricsConnector = mock[MetricsConnector]
  private val mockObjectIdService = new ObjectIdService{
    override def newId(): ObjectId = TestData.ObjectId
  }
  private val service: IncomingNotificationService =
    new IncomingNotificationService(
      mockNotificationRepo,
      mockSendService,
      mockApiSubsFieldsConnector,
      mockAuditService,
      mockMetricsConnector,
      mockObjectIdService)(Helpers.stubControllerComponents().executionContext)

  "process" when {
    "blocked notifications exist" should {

      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(NewClientSubscriptionId))(eqTo(HeaderCarrier), eqTo(LogContext)))
          .thenReturn(Future.successful(Right(ClientDataConnector.Success(ClientData))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(NewClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockNotificationRepo.insert(eqTo(Notification), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Right(())))
        when(mockSendService.send(eqTo(Notification), eqTo(PushCallbackData))(
          eqTo(HeaderCarrier), eqTo(LogContext), eqTo(AuditContext)))
          .thenReturn(Future.successful(Right(())))
      }

      "return Right(Unit)" in {
        setup()
        service.process(ValidXml, RequestMetadata).map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      behave like notSendNotification(setup())

      behave like notLogNotificationSaved(setup())
    }

    "no blocked notifications exist" should {
      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(NewClientSubscriptionId))(eqTo(HeaderCarrier), eqTo(LogContext)))
          .thenReturn(Future.successful(Right(ClientDataConnector.Success(ClientData))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(NewClientSubscriptionId)))
          .thenReturn(Future.successful(Right(false)))
        when(mockNotificationRepo.insert(eqTo(Notification), eqTo(SavedToBeSent)))
          .thenReturn(Future.successful(Right(())))
        when(mockSendService.send(eqTo(Notification), eqTo(PushCallbackData))(*, *, *))
          .thenReturn(Future.successful(Right(())))
      }

      "return Right(Unit)" in {
        setup()
        service.process(ValidXml, RequestMetadata).map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      "call the SendService" in {
        setup()

        service.process(ValidXml, RequestMetadata).map { _ =>
          verify(mockSendService).send(
            eqTo(Notification),
            eqTo(PushCallbackData))(eqTo(HeaderCarrier), eqTo(LogContext), eqTo(AuditContext))
          succeed
        }
      }

      behave like logNotificationSaved(setup())
    }

    "ClientDataConnector returns DeclarantNotFound" should {
      def setup(): Unit =
        when(mockApiSubsFieldsConnector.get(eqTo(NewClientSubscriptionId))(eqTo(HeaderCarrier), eqTo(LogContext)))
          .thenReturn(Future.successful(Left(ClientDataConnector.DeclarantNotFound)))

      "return Left with DeclarantNotFound" in {
        setup()
        service.process(ValidXml, RequestMetadata).map(_ shouldBe Left(DeclarantNotFound))
      }

      "not notify the Audit Service" in {
        setup()
        service.process(ValidXml, RequestMetadata).map { _ =>
          verify(mockAuditService, never).sendIncomingNotificationEvent(*, *)(*, *, *)
          succeed
        }
      }

      behave like notNotifyMetricsService(setup())

      behave like notSendNotification(setup())

      behave like notLogNotificationSaved(setup())
    }

    "repository check for existing FailedAndBlocked notifications fails" should {
      val error = MongoDbError(s"checking if FailedAndBlocked notifications exist for client subscription ID $NewClientSubscriptionId", Exception)

      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(NewClientSubscriptionId))(eqTo(HeaderCarrier), eqTo(LogContext)))
          .thenReturn(Future.successful(Right(ClientDataConnector.Success(ClientData))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(NewClientSubscriptionId)))
          .thenReturn(Future.successful(
            Left(error)
          ))
      }

      "return Left with a MongoDB error" in {
        setup()
        service.process(ValidXml, RequestMetadata).map(_ shouldBe Left(InternalServiceError))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notSendNotification(setup())

      behave like notLogNotificationSaved(setup())
    }

    "saving notification to repository fails" should {
      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(NewClientSubscriptionId))(eqTo(HeaderCarrier), eqTo(LogContext)))
          .thenReturn(Future.successful(Right(ClientDataConnector.Success(ClientData))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(NewClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockNotificationRepo.insert(eqTo(Notification), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Left(MongoDbError("saving notification", Exception))))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notSendNotification(setup())

      behave like notLogNotificationSaved(setup())
    }
  }

  private def notifyMetricsService(setup: => Unit): Unit = {
    s"send a notification to the Metrics Service" in {
      setup
      service.process(ValidXml, RequestMetadata).map { _ =>
        verify(mockMetricsConnector).send(eqTo(Notification))(eqTo(HeaderCarrier))
        succeed
      }
    }
  }

  private def logNotificationSaved(setup: => Unit): Unit = {
    s"log that a notification has been saved" in {
      setup
      service.process(ValidXml, RequestMetadata).map { _ =>
        verify(service).info(eqTo("Saved notification"))(LogContext)
        succeed
      }
    }
  }

  private def notifyAuditService(setup: => Unit): Unit = {
    s"send a notification to the Audit Service" in {
      setup
      service.process(ValidXml, RequestMetadata).map { _ =>
        verify(mockAuditService).sendIncomingNotificationEvent(
          eqTo(PushCallbackData),
          eqTo(Payload))(
          eqTo(HeaderCarrier), eqTo(LogContext), eqTo(AuditContext))
        succeed
      }
    }
  }

  private def notNotifyMetricsService(setup: => Unit): Unit = {
    s"not send a notification to the Metrics Service" in {
      setup
      service.process(ValidXml, RequestMetadata).map { _ =>
        verify(mockMetricsConnector, never).send(*)(*)
        succeed
      }
    }
  }

  private def notSendNotification(setup: => Unit): Unit = {
    s"not send the notification" in {
      setup
      service.process(ValidXml, RequestMetadata).map { _ =>
        verify(mockSendService, never).send(*, *)(*, *, *)
        succeed
      }
    }
  }

  private def notLogNotificationSaved(setup: => Unit): Unit = {
    s"not log that a notification has been saved" in {
      setup
      service.process(ValidXml, RequestMetadata).map { _ =>
        verify(service, never).info(eqTo("Saved notification"))(*)
        succeed
      }
    }
  }
}
