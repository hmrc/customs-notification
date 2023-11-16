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
import uk.gov.hmrc.customs.notification.config.{ApiSubscriptionFieldsUrlConfig, AppConfig, MetricsUrlConfig, SendNotificationConfig}
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
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

class SendServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  //class SendService @Inject()(implicit
  //                            connector: HttpConnector,
  //                            repo: NotificationRepo,
  //                            config: SendNotificationConfig,
  //                            dateTimeService: DateTimeService,
  //                            logger: NotificationLogger,
  //                            auditingService: AuditingService,
  //                            ec: ExecutionContext)

  private val mockHttpConnector = mock[HttpConnector]
  private val mockRepo = mock[NotificationRepo]
  private val mockConfig = mock[SendNotificationConfig]
  private val mockDateTimeService = mock[DateTimeService]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockAuditingService = mock[AuditingService]

  "send" when {
    "given declarant URL with client ID that matches with internal client IDs from config" should {
      "return a Right(())" in {
        Future.successful(succeed)
      }

      "POST a InternalPushNotificationRequest" in {
        Future.successful(succeed)
      }

      "set notification status to Succeeded" in {
        Future.successful(succeed)
      }

      "send a success notification to AuditService" in {
        Future.successful(succeed)
      }
    }

    "declarant URL that doesn't match to internal client ID from config" should {
      "return a Right(())" in {
        Future.successful(succeed)
      }

      "POST a ExternalPushNotificationRequest" in {
        Future.successful(succeed)
      }

      "set notification status to Succeeded" in {
        Future.successful(succeed)
      }

      "send a success notification to AuditService" in {
        Future.successful(succeed)
      }
    }

    "no declarant client ID or URL" should {
      "return a Right(())" in {
        Future.successful(succeed)
      }

      "POST a ExternalPushNotificationRequest" in {
        Future.successful(succeed)
      }

      "set notification status to Succeeded" in {
        Future.successful(succeed)
      }

      "send a success notification to AuditService" in {
        Future.successful(succeed)
      }
    }

    "HTTP connector returns a client error" in {

    }
  }
}

object SendServiceSpec {
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