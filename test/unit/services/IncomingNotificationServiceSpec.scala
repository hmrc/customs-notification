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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.CustomProcessingStatus.{FailedAndBlocked, SavedToBeSent}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.repo.NotificationRepo.MongoDbError
import uk.gov.hmrc.customs.notification.services.AuditService.SuccessAuditEventDetail
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, InternalServiceError}
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import util.TestData

import scala.concurrent.Future

class IncomingNotificationServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockNotificationRepo = mock[NotificationRepo]
  private val mockSendNotificationService = mock[SendNotificationService]
  private val mockApiSubsFieldsConnector = mock[ApiSubscriptionFieldsConnector]
  private val mockAuditService = mock[AuditService]
  private val mockMetricsConnector = mock[MetricsConnector]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val service: IncomingNotificationService =
    new IncomingNotificationService(
      mockNotificationRepo,
      mockSendNotificationService,
      mockApiSubsFieldsConnector,
      mockAuditService,
      mockMetricsConnector,
      mockNotificationLogger,
      () => TestData.ObjectId)(Helpers.stubControllerComponents().executionContext)

  private def testWithValidPayload(): Future[Either[IncomingNotificationService.Error, Unit]] =
    service.process(TestData.ValidXml)(TestData.RequestMetadata, TestData.EmptyHeaderCarrier)

  "process" when {
    "blocked notifications exist" should {

      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(TestData.OldClientSubscriptionId))(eqTo(TestData.EmptyHeaderCarrier)))
          .thenReturn(Future.successful(Right(ApiSubscriptionFieldsConnector.Success(TestData.ApiSubscriptionFields))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.OldClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockNotificationRepo.saveWithLock(eqTo(TestData.Notification), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Right(())))
//        when(.post(eqTo(TestData.MetricsRequest))(*, *))
//          .thenReturn(Future.successful(Right(())))
        when(mockSendNotificationService.send(eqTo(TestData.Notification), eqTo(TestData.RequestMetadata), eqTo(TestData.PushCallbackData))(*, *, *))
          .thenReturn(Future.successful(Right(())))
      }

      "return Right(Unit)" in {
        setup()
        testWithValidPayload().map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      behave like notCallSendNotificationService(setup())

      behave like notLogNotificationSaved(setup())
    }

    "no blocked notifications exist" should {
      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(TestData.OldClientSubscriptionId))(eqTo(TestData.EmptyHeaderCarrier)))
          .thenReturn(Future.successful(Right(ApiSubscriptionFieldsConnector.Success(TestData.ApiSubscriptionFields))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.OldClientSubscriptionId)))
          .thenReturn(Future.successful(Right(false)))
        when(mockNotificationRepo.saveWithLock(eqTo(TestData.Notification), eqTo(SavedToBeSent)))
          .thenReturn(Future.successful(Right(())))
        when(mockSendNotificationService.send(eqTo(TestData.Notification), eqTo(TestData.RequestMetadata), eqTo(TestData.PushCallbackData))(*, *, *))
          .thenReturn(Future.successful(Right(())))
      }

      "return Right(Unit)" in {
        setup()
        testWithValidPayload().map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      "call the SendNotificationService" in {
        setup()

        testWithValidPayload().map { _ =>
          verify(mockSendNotificationService).send(
            eqTo(TestData.Notification),
            eqTo(TestData.RequestMetadata),
            eqTo(TestData.PushCallbackData))(*, *, eqTo(TestData.EmptyHeaderCarrier))
          succeed
        }
      }

      behave like logNotificationSaved(setup())
    }

    "ApiSubscriptionFieldsConnector returns DeclarantNotFound" should {
      def setup(): Unit =
        when(mockApiSubsFieldsConnector.get(eqTo(TestData.OldClientSubscriptionId))(eqTo(TestData.EmptyHeaderCarrier)))
          .thenReturn(Future.successful(Left(ApiSubscriptionFieldsConnector.DeclarantNotFound)))

      "return Left with DeclarantNotFound" in {
        setup()
        testWithValidPayload().map(_ shouldBe Left(DeclarantNotFound))
      }

      "not notify the Audit Service" in {
        setup()
        testWithValidPayload().map { _ =>
          verify(mockAuditService, never).sendIncomingNotificationEvent(*, *, *)(*)
          succeed
        }
      }

      behave like notNotifyMetricsService(setup())

      behave like notCallSendNotificationService(setup())

      behave like notLogNotificationSaved(setup())
    }

    "repository check for existing failed and blocked notifications fails" should {
      val error = MongoDbError(s"checking if failed and blocked notifications exist for client subscription ID ${TestData.OldClientSubscriptionId}", TestData.Exception)

      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(TestData.OldClientSubscriptionId))(eqTo(TestData.EmptyHeaderCarrier)))
          .thenReturn(Future.successful(Right(ApiSubscriptionFieldsConnector.Success(TestData.ApiSubscriptionFields))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.OldClientSubscriptionId)))
          .thenReturn(Future.successful(
            Left(error)
          ))
      }

      "return Left with a MongoDB error" in {
        setup()
        testWithValidPayload().map(_ shouldBe Left(InternalServiceError))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notCallSendNotificationService(setup())

      behave like notLogNotificationSaved(setup())
    }

    "saving notification to repository fails" should {
      def setup(): Unit = {
        when(mockApiSubsFieldsConnector.get(eqTo(TestData.OldClientSubscriptionId))(eqTo(TestData.EmptyHeaderCarrier)))
          .thenReturn(Future.successful(Right(ApiSubscriptionFieldsConnector.Success(TestData.ApiSubscriptionFields))))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.OldClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockNotificationRepo.saveWithLock(eqTo(TestData.Notification), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Left(MongoDbError("saving notification", TestData.Exception))))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notCallSendNotificationService(setup())

      behave like notLogNotificationSaved(setup())
    }
  }

  private def notifyMetricsService(setup: => Unit): Unit = {
    s"send a notification to the Metrics Service" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockMetricsConnector).send(eqTo(TestData.Notification))(eqTo(TestData.EmptyHeaderCarrier))
        succeed
      }
    }
  }

  private def logNotificationSaved(setup: => Unit): Unit = {
    s"log that a notification has been saved" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockNotificationLogger).info(eqTo("Saved notification"), eqTo(TestData.Notification))(*)
        succeed
      }
    }
  }

  private def notifyAuditService(setup: => Unit): Unit = {
    s"send a notification to the Audit Service" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockAuditService).sendIncomingNotificationEvent(
          eqTo(TestData.PushCallbackData),
          eqTo(TestData.ValidXml.toString),
          eqTo(TestData.RequestMetadata))(eqTo(TestData.EmptyHeaderCarrier))
        succeed
      }
    }
  }

  private def notNotifyMetricsService(setup: => Unit): Unit = {
    s"not send a notification to the Metrics Service" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockMetricsConnector, never).send(*)(*)
        succeed
      }
    }
  }

  private def notCallSendNotificationService(setup: => Unit): Unit = {
    s"not call the PushOrPullService" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockSendNotificationService, never).send(*, *, *)(*, *, *)
        succeed
      }
    }
  }

  private def notLogNotificationSaved(setup: => Unit): Unit = {
    s"not log that a notification has been saved" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockNotificationLogger, never).info(eqTo("Saved notification"), *)(*)
        succeed
      }
    }
  }
}
