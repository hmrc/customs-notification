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
import org.mockito.captor._
import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.MimeTypes
import play.api.http.Status.NOT_FOUND
import play.api.mvc.{AnyContent, Headers}
import play.api.test.Helpers.{ACCEPT, CONTENT_TYPE}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.customs.notification.config.{ApiSubscriptionFieldsUrlConfig, AppConfig, MetricsUrlConfig}
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.requests.ApiSubscriptionFieldsRequest
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.repo.NotificationRepo.MongoDbError
import uk.gov.hmrc.customs.notification.services.AuditingService.AuditType
import uk.gov.hmrc.customs.notification.services.HttpConnector.ErrorResponse
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, IncomingNotificationServiceError, InternalServiceError}
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HttpResponse}
import unit.services.IncomingNotificationServiceSpec.TestData

import java.net.URL
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.concurrent.Future
import scala.xml.NodeSeq

class IncomingNotificationServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockNotificationRepo = mock[NotificationRepo]
  private val mockSendService = mock[SendService]
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TestData.TimeNow
  }
  private val mockApiSubsFieldsUrlConfig: ApiSubscriptionFieldsUrlConfig = new ApiSubscriptionFieldsUrlConfig(mock[AppConfig]) {
    override val url: URL = TestData.ApiSubsFieldsUrl
  }
  private val mockMetricsUrlConfig: MetricsUrlConfig = new MetricsUrlConfig(mock[AppConfig]) {
    override val url: URL = TestData.MetricsUrl
  }
  private val mockHttpConnector = mock[HttpConnector]
  private val mockAuditingService = mock[AuditingService]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockObjectIdService: ObjectIdService = new ObjectIdService() {
    override def newId(): ObjectId = TestData.ObjectId
  }
  private val service: IncomingNotificationService =
    new IncomingNotificationService(
      mockNotificationRepo,
      mockSendService,
      mockDateTimeService,
      mockApiSubsFieldsUrlConfig,
      mockMetricsUrlConfig,
      mockHttpConnector,
      mockAuditingService,
      mockNotificationLogger,
      mockObjectIdService)(Helpers.stubControllerComponents().executionContext)

  private def testWithValidPayload(): Future[Either[IncomingNotificationServiceError, Unit]] =
    service.process(TestData.ValidXml)(TestData.RequestMetadata, TestData.EmptyHeaderCarrier)

  val captor = ArgCaptor[Notification]

  "process" when {
    "blocked notifications exist" should {
      val notCaptor = ArgCaptor[Notification]
      val statusCaptor = ArgCaptor[CustomProcessingStatus]

      def setup(): Unit = {
        when(mockHttpConnector.get[ApiSubscriptionFieldsRequest, ApiSubscriptionFields](eqTo(TestData.ApiSubsFieldsRequest))(*, *, *))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockNotificationRepo.saveWithLock(eqTo(TestData.Notification), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Right(())))
        when(mockHttpConnector.post(eqTo(TestData.MetricsRequest))(*, *))
          .thenReturn(Future.successful(Right(())))
        when(mockSendService.send(eqTo(TestData.Notification), eqTo(TestData.RequestMetadata), eqTo(TestData.ApiSubscriptionFields))(*, *, *))
          .thenReturn(Future.successful(Right(())))
      }

      "return Right(Unit)" in {
        setup()
        testWithValidPayload().map { r =>
          verify(mockNotificationRepo).saveWithLock(notCaptor, statusCaptor)
          r shouldBe Right(())
        }
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      behave like notCallSendService(setup())

      behave like notLogNotificationSaved(setup())
    }

    "no blocked notifications exist" should {
      def setup(): Unit = {
        when(mockHttpConnector.get[ApiSubscriptionFieldsRequest, ApiSubscriptionFields](eqTo(TestData.ApiSubsFieldsRequest))(*, *, *))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Right(false)))
        when(mockNotificationRepo.saveWithLock(eqTo(TestData.Notification), eqTo(SavedToBeSent)))
          .thenReturn(Future.successful(Right(())))
        when(mockHttpConnector.post(eqTo(TestData.MetricsRequest))(*, *))
          .thenReturn(Future.successful(Right(())))
        when(mockSendService.send(eqTo(TestData.Notification), eqTo(TestData.RequestMetadata), eqTo(TestData.ApiSubscriptionFields))(*, *, *))
          .thenReturn(Future.successful(Right(())))
      }

      "return Right(Unit)" in {
        setup()
        testWithValidPayload().map(_ shouldBe Right(()))
      }

      behave like notifyAuditService(setup())

      behave like notifyMetricsService(setup())

      "call the SendService" in {
        setup()

        testWithValidPayload().map { _ =>
          verify(mockSendService).send(
            eqTo(TestData.Notification),
            eqTo(TestData.RequestMetadata),
            eqTo(TestData.ApiSubscriptionFields))(eqTo(TestData.EmptyHeaderCarrier), *, *)
          succeed
        }
      }

      behave like logNotificationSaved(setup())
    }

    "getting a ApiSubscriptionFields returns 404 Not Found" should {

      def setupApiSubscriptionFieldsToReturnNotFound(): Unit =
        when(mockHttpConnector.get[ApiSubscriptionFieldsRequest, ApiSubscriptionFields](eqTo(TestData.ApiSubsFieldsRequest))(*, *, *))
          .thenReturn(Future.successful(Left(ErrorResponse(TestData.ApiSubsFieldsRequest, HttpResponse(NOT_FOUND, "")))))

      "return Left with DeclarantNotFound" in {
        setupApiSubscriptionFieldsToReturnNotFound()
        testWithValidPayload().map(_ shouldBe Left(DeclarantNotFound))
      }

      "not send a notification to the Audit Service" in {
        setupApiSubscriptionFieldsToReturnNotFound()
        testWithValidPayload().map { _ =>
          verify(mockAuditingService, never).auditSuccess(*, *, *, *)(*, *, *)
          succeed
        }
      }

      behave like notNotifyMetricsService(setupApiSubscriptionFieldsToReturnNotFound())

      behave like notCallSendService(setupApiSubscriptionFieldsToReturnNotFound())

      behave like notLogNotificationSaved(setupApiSubscriptionFieldsToReturnNotFound())
    }

    "repository check for existing failed and blocked notifications" should {
      val error = MongoDbError(s"checking if failed and blocked notifications exist for client subscription ID ${TestData.ClientSubscriptionId}", TestData.Exception)

      def setupMongoError(): Unit = {
        when(mockHttpConnector.get[ApiSubscriptionFieldsRequest, ApiSubscriptionFields](eqTo(TestData.ApiSubsFieldsRequest))(*, *, *))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(
            Left(error)
          ))
      }

      "return Left with a MongoDB error" in {
        setupMongoError()
        testWithValidPayload().map(_ shouldBe Left(InternalServiceError))
      }

      behave like notifyAuditService(setupMongoError())

      behave like notNotifyMetricsService(setupMongoError())

      behave like notCallSendService(setupMongoError())

      behave like notLogNotificationSaved(setupMongoError())
    }

    "saving notification to repository fails" should {
      def setup(): Unit = {
        when(mockHttpConnector.get[ApiSubscriptionFieldsRequest, ApiSubscriptionFields](eqTo(TestData.ApiSubsFieldsRequest))(*, *, *))
          .thenReturn(Future.successful(Right(TestData.ApiSubscriptionFields)))
        when(mockNotificationRepo.checkFailedAndBlockedExist(eqTo(TestData.ClientSubscriptionId)))
          .thenReturn(Future.successful(Right(true)))
        when(mockNotificationRepo.saveWithLock(eqTo(TestData.Notification), eqTo(FailedAndBlocked)))
          .thenReturn(Future.successful(Left(MongoDbError("saving notification", TestData.Exception))))
      }

      behave like notifyAuditService(setup())

      behave like notNotifyMetricsService(setup())

      behave like notCallSendService(setup())

      behave like notLogNotificationSaved(setup())
    }
  }

  private def notifyMetricsService(setup: => Unit): Unit = {
    s"send a notification to the Metrics Service" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockHttpConnector).post(eqTo(TestData.MetricsRequest))(*, *)
        succeed
      }
    }
  }

  private def logNotificationSaved(setup: => Unit): Unit = {
    s"log that a notification has been saved" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockNotificationLogger).info(eqTo("Saved notification"), captor.capture)(*)
        info(captor.value.toString)
        info(TestData.Notification.toString)
        succeed
      }
    }
  }

  private def notifyAuditService(setup: => Unit): Unit = {
    s"send a notification to the Audit Service" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockAuditingService).auditSuccess(
          eqTo(TestData.PushCallbackData),
          eqTo(TestData.ValidXml.toString),
          eqTo(TestData.RequestMetadata),
          eqTo(AuditType.Inbound))(*, *, *)
        succeed
      }
    }
  }

  private def notNotifyMetricsService(setup: => Unit): Unit = {
    s"not send a notification to the Metrics Service" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockHttpConnector, never).post(*)(*, *)
        succeed
      }
    }
  }

  private def notCallSendService(setup: => Unit): Unit = {
    s"not call the PushOrPullService" in {
      setup
      testWithValidPayload().map { _ =>
        verify(mockSendService, never).send(*, *, *)(*, *, *)
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

object IncomingNotificationServiceSpec {
  object TestData {
    val TimeNow = ZonedDateTime.of(2023, 12, 25, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:ignore
    val IssueDateTime = ZonedDateTime.of(2023, 1, 1, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:ignore
    val ClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-2222-4444-8888-161616161616"))
    val ClientId = models.ClientId("Client1")
    val ClientPushUrl = new URL("http://www.example.net")
    val PushCallbackData = models.PushCallbackData(Some(ClientPushUrl), Authorization("SECURITY_TOKEN"))
    val ApiSubscriptionFields = models.ApiSubscriptionFields(ClientId, PushCallbackData)
    val ConversationId = models.ConversationId(UUID.fromString("00000000-4444-4444-AAAA-AAAAAAAAAAAA"))
    val NotificationId = models.NotificationId(UUID.fromString("00000000-9999-4444-9999-444444444444"))
    val BadgeId = "ABCDEF1234"
    val SubmitterId = "IAMSUBMITTER"
    val CorrelationId = "CORRID2234"
    val ValidXml: NodeSeq = <Foo>Bar</Foo>
    val BasicAuthTokenValue = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="
    val ApiSubsFieldsUrl = new URL("http://www.example.com")
    val MetricsUrl = new URL("http://www.example.org")
    val ValidHeaders: Headers = Headers(
      List(
        X_CLIENT_SUB_ID_HEADER_NAME -> ClientSubscriptionId.toString,
        X_CONVERSATION_ID_HEADER_NAME -> ConversationId.toString,
        CONTENT_TYPE -> (MimeTypes.XML + "; charset=UTF-8"),
        ACCEPT -> MimeTypes.XML,
        AUTHORIZATION -> BasicAuthTokenValue,
        X_BADGE_ID_HEADER_NAME -> BadgeId,
        X_SUBMITTER_ID_HEADER_NAME -> SubmitterId,
        X_CORRELATION_ID_HEADER_NAME -> CorrelationId
      ): _*
    )

    val ValidSubmitRequest: FakeRequest[AnyContent] = FakeRequest().withHeaders(ValidHeaders).withXmlBody(ValidXml)

    val RequestMetadata: RequestMetadata = models.RequestMetadata(ClientSubscriptionId, ConversationId, NotificationId,
      Some(Header(X_BADGE_ID_HEADER_NAME, BadgeId)), Some(Header(X_SUBMITTER_ID_HEADER_NAME, SubmitterId)), Some(Header(X_CORRELATION_ID_HEADER_NAME, CorrelationId)),
      Some(Header(ISSUE_DATE_TIME_HEADER_NAME, IssueDateTime.toString)), None, None, TimeNow)

    val ApiSubsFieldsRequest = ApiSubscriptionFieldsRequest(ClientSubscriptionId, ApiSubsFieldsUrl)

    val MetricsRequest = requests.MetricsRequest(
      ConversationId,
      TimeNow,
      TimeNow,
      MetricsUrl)

    val ObjectId = new ObjectId("aaaaaaaaaaaaaaaaaaaaaaaa")

    val Notification: Notification = {
      val headers = (RequestMetadata.maybeBadgeId ++
        RequestMetadata.maybeSubmitterNumber ++
        RequestMetadata.maybeCorrelationId ++
        RequestMetadata.maybeIssueDateTime).toSeq

      models.Notification(
        ObjectId,
        ClientSubscriptionId,
        ClientId,
        NotificationId,
        ConversationId,
        headers,
        ValidXml.toString,
        TimeNow)
    }

    val EmptyHeaderCarrier: HeaderCarrier = HeaderCarrier()
    val Exception: Throwable = new RuntimeException()
  }
}
