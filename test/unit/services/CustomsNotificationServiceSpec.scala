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
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.MimeTypes
import play.api.mvc.{AnyContent, Headers}
import play.api.test.FakeRequest
import play.api.test.Helpers.{ACCEPT, CONTENT_TYPE}
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, ConnectorName, CustomsNotificationMetricsConnector}
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.repo.NotificationWorkItem
import uk.gov.hmrc.customs.notification.models.{FailedAndBlocked, Header, Notification, PushCallback, RequestMetadata, SuccessfullyCommunicated}
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.Error.{DeclarantNotFound, HttpClientError, MongoDbError, Error, ErrorResponse}
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util.{NotificationLogger, NotificationWorkItemRepo}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.WorkItem
import unit.services.CustomsNotificationServiceSpec.TestData
import uk.gov.hmrc.customs.notification.connectors
import uk.gov.hmrc.customs.notification.models.requests.InternalPushNotificationRequest

import java.net.URL
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.concurrent.Future
import scala.xml.NodeSeq

class CustomsNotificationServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockLogger = mock[NotificationLogger]
  private val mockWorkItemRepo = mock[NotificationWorkItemRepo]
  private val mockPushOrPullService = mock[SendService]
  private val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
  private val mockAuditingService = mock[AuditingService]
  private val mockCustomsNotificationMetricsConnector = mock[CustomsNotificationMetricsConnector]

  private val service: NotificationService =
    new NotificationService(
      mockLogger,
      mockWorkItemRepo,
      mockPushOrPullService,
      mockApiSubscriptionFieldsConnector,
      mockAuditingService,
      mockCustomsNotificationMetricsConnector
    )

  "CustomsNotificationService.handleNotification " when {
    "blocked notifications exist" should {
      def setup(): Unit = {
        when(mockApiSubscriptionFieldsConnector.getApiSubscriptionFields(eqTo(TestData.ClientSubscriptionId))(*, eqTo(TestData.RequestMetaData)))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockWorkItemRepo.failedAndBlockedWithHttp5xxByCsIdExists(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockWorkItemRepo.saveWithLock(eqTo(TestData.NotificationWorkItem), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Right(TestData.MongoNotificationWorkItem)))
      }

      "return Right(Unit)" in {
        setup()
        val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        actualF.map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      behave like notCallPushOrPullService(setup())

      behave like logNotificationSaved(setup())
    }

    "no blocked notifications exist" should {
      def setup(): Unit = {
        when(mockApiSubscriptionFieldsConnector.getApiSubscriptionFields(eqTo(TestData.ClientSubscriptionId))(*, eqTo(TestData.RequestMetaData)))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockWorkItemRepo.failedAndBlockedWithHttp5xxByCsIdExists(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Right(false)))
        when(mockWorkItemRepo.saveWithLock(eqTo(TestData.NotificationWorkItem), eqTo(SuccessfullyCommunicated)))
          .thenReturn(Future.successful(Right(TestData.MongoNotificationWorkItem)))
      }

      "return Right(Unit)" in {
        setup()
        val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        actualF.map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      "call the PushOrPullService" in {
        setup()
        val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        actualF.map { _ =>
          verify(mockPushOrPullService).send(
            eqTo(TestData.MongoNotificationWorkItem),
            eqTo(TestData.ApiSubscriptionFields),
            eqTo(true))
          succeed
        }
      }

      behave like logNotificationSaved(setup())
    }

    "ApiSubscriptionFieldsConnector returns an error" should {
      val error = DeclarantNotFound(TestData.RequestMetaData)

      def setup(error: Error): Unit =
        when(mockApiSubscriptionFieldsConnector.getApiSubscriptionFields(eqTo(TestData.ClientSubscriptionId))(*, eqTo(TestData.RequestMetaData)))
          .thenReturn(Future.successful(Left(error)))

      "return Left with the specific error" in {
        setup(error)
        val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        actualF.map(_ shouldBe Left(error))
      }

      "not send a notification to the Audit Service" in {
        setup(error)
        val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        actualF.map { _ =>
          verify(mockAuditingService, never).auditNotificationReceived(*, *)(*)
          succeed
        }
      }

      behave like notNotifyMetricsService(setup(error))

      behave like notCallPushOrPullService(setup(error))

      behave like notLogNotificationSaved(setup(error))
    }

    "repository check fails for existing failed and blocked notifications" should {
      def setup(): Unit = {
        when(mockApiSubscriptionFieldsConnector.getApiSubscriptionFields(eqTo(TestData.ClientSubscriptionId))(*, eqTo(TestData.RequestMetaData)))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockWorkItemRepo.failedAndBlockedWithHttp5xxByCsIdExists(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Left(MongoDbError(TestData.Exception, TestData.ClientSubscriptionId))))
      }

      "return Left with a MongoDB error" in {
        setup()
        val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        actualF.map(_ shouldBe Left(MongoDbError(TestData.Exception, TestData.ClientSubscriptionId)))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notCallPushOrPullService(setup())

      behave like notLogNotificationSaved(setup())
    }

    "saving work item to repository fails" should {
      def setup(): Unit = {
        when(mockApiSubscriptionFieldsConnector.getApiSubscriptionFields(eqTo(TestData.ClientSubscriptionId))(*, eqTo(TestData.RequestMetaData)))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockWorkItemRepo.failedAndBlockedWithHttp5xxByCsIdExists(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockWorkItemRepo.saveWithLock(eqTo(TestData.NotificationWorkItem), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Left(MongoDbError(TestData.Exception, TestData.NotificationWorkItem))))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notCallPushOrPullService(setup())

      behave like notLogNotificationSaved(setup())
    }
  }

  private def notifyMetricsService(setup: => Unit): Unit = {
    s"send a notification to the Metrics Service" in {
      setup
      val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
      actualF.map { _ =>
        verify(mockCustomsNotificationMetricsConnector).post(TestData.ConversationId, TestData.StartTime)(TestData.EmptyHeaderCarrier)
        succeed
      }
    }
  }

  private def logNotificationSaved(setup: => Unit): Unit = {
    s"not log that a notification has been saved" in {
      setup
      val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
      actualF.map { _ =>
        verify(mockLogger).info(eqTo("Saved notification"))(eqTo(TestData.ClientId), eqTo(TestData.RequestMetaData))
        succeed
      }
    }
  }

  private def notifyAuditService(setup: => Unit): Unit = {
    s"send a notification to the Audit Service" in {
      setup
      val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
      actualF.map { _ =>
        verify(mockAuditingService).auditNotificationReceived(TestData.PushNotificationRequest, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
        succeed
      }
    }
  }

  private def notNotifyMetricsService(setup: => Unit): Unit = {
    s"not send a notification to the Metrics Service" in {
      setup
      val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
      actualF.map { _ =>
        verify(mockCustomsNotificationMetricsConnector, never).post(*, *)(*)
        succeed
      }
    }
  }

  private def notCallPushOrPullService(setup: => Unit): Unit = {
    s"not call the PushOrPullService" in {
      setup
      val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
      actualF.map { _ =>
        verify(mockPushOrPullService, never).send(*, *, *)
        succeed
      }
    }
  }

  private def notLogNotificationSaved(setup: => Unit): Unit = {
    s"not log that a notification has been saved" in {
      setup
      val actualF = service.handleNotification(TestData.ValidXml, TestData.RequestMetaData)(TestData.EmptyHeaderCarrier)
      actualF.map { _ =>
        verify(mockLogger, never).info(eqTo("Saved notification"))(*)
        succeed
      }
    }
  }
}

object CustomsNotificationServiceSpec {
  object TestData {
    val StartTime = ZonedDateTime.of(2023, 12, 25, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:ignore
    val PreviouslyReceivedAt = ZonedDateTime.of(2000, 12, 25, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:ignore
    val ClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-2222-4444-8888-161616161616"))
    val ClientId = models.ClientId("Client1")
    val DeclarantCallbackData = models.DeclarantCallbackData(PushCallback(new URL("http://www.example.com")), "SECURITY_TOKEN")
    val ApiSubscriptionFields = models.ApiSubscriptionFields(ClientId, DeclarantCallbackData)
    val ConversationId = models.ConversationId(UUID.fromString("00000000-4444-4444-AAAA-AAAAAAAAAAAA"))
    val NotificationId = models.NotificationId(UUID.fromString("00000000-9999-4444-9999-444444444444"))
    val BadgeId = "ABCDEF1234"
    val SubmitterId = "IAMSUBMITTER"
    val CorrelationId = "CORRID2234"
    val ValidXml: NodeSeq = <Foo>Bar</Foo>
    val basicAuthTokenValue = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="

    val ValidHeaders: Headers = Headers(
      List(
        X_CLIENT_SUB_ID_HEADER_NAME -> ClientSubscriptionId.toString,
        X_CONVERSATION_ID_HEADER_NAME -> ConversationId.toString,
        CONTENT_TYPE -> (MimeTypes.XML + "; charset=UTF-8"),
        ACCEPT -> MimeTypes.XML,
        AUTHORIZATION -> basicAuthTokenValue,
        X_BADGE_ID_HEADER_NAME -> BadgeId,
        X_SUBMITTER_ID_HEADER_NAME -> SubmitterId,
        X_CORRELATION_ID_HEADER_NAME -> CorrelationId
      ): _*
    )

    val ValidSubmitRequest: FakeRequest[AnyContent] = FakeRequest().withHeaders(ValidHeaders).withXmlBody(ValidXml)

    val RequestMetaData: RequestMetadata = RequestMetadata(ClientSubscriptionId, ConversationId, NotificationId,
      Some(Header(X_BADGE_ID_HEADER_NAME, BadgeId)), Some(Header(X_SUBMITTER_ID_HEADER_NAME, SubmitterId)), Some(Header(X_CORRELATION_ID_HEADER_NAME, CorrelationId)),
      None, None, None, StartTime)

    val NotificationWorkItem: NotificationWorkItem = models.repo.NotificationWorkItem(
      ClientSubscriptionId,
      ClientId,
      StartTime,
      Notification(
        NotificationId,
        ConversationId,
        List(
          Header(X_BADGE_ID_HEADER_NAME, BadgeId),
          Header(X_SUBMITTER_ID_HEADER_NAME, SubmitterId),
          Header(X_CORRELATION_ID_HEADER_NAME, CorrelationId)
        ),
        ValidXml.toString(),
        MimeTypes.XML)
    )

    val PushNotificationRequest: InternalPushNotificationRequest = models.requests.PushNotificationRequest(
      DeclarantCallbackData.callbackUrl,
      DeclarantCallbackData.securityToken,
      ConversationId.toString,
      NotificationWorkItem.notification.headers,
      ValidXml.toString())

    val MongoNotificationWorkItemId = new ObjectId("aaaaaaaaaaaaaaaaaaaaaaaa")

    val MongoNotificationWorkItem: WorkItem[NotificationWorkItem] =
      WorkItem(
        MongoNotificationWorkItemId,
        PreviouslyReceivedAt.toInstant,
        PreviouslyReceivedAt.toInstant,
        StartTime.toInstant,
        ToDo,
        0,
        NotificationWorkItem
      )

    val EmptyHeaderCarrier: HeaderCarrier = HeaderCarrier()
    val ConnectorName: ConnectorName = connectors.ConnectorName("service")
    val Exception: Throwable = new RuntimeException()
  }
}
