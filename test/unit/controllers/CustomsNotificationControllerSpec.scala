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

package unit.controllers

import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.mvc.{AnyContent, Headers, Results}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.BadRequestCode
import uk.gov.hmrc.customs.notification.config.{AppConfig, BasicAuthTokenConfig}
import uk.gov.hmrc.customs.notification.controllers.CustomsNotificationController
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.{Header, RequestMetadata}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.{DeclarantNotFound, InternalServiceError}
import uk.gov.hmrc.customs.notification.services.{DateTimeService, IncomingNotificationService, UuidService}
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import unit.controllers.CustomsNotificationControllerSpec.TestData

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.concurrent.Future
import scala.xml.NodeSeq

class CustomsNotificationControllerSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with ResetMocksAfterEachAsyncTest {

  private val mockNotificationLogger = mock[NotificationLogger]
  private val incomingNotificationService = mock[IncomingNotificationService]
  private val mockUuidService = new UuidService {
    override def getRandomUuid(): UUID = TestData.NotificationId.id
  }
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TestData.StartTime
  }
  private val mockBasicAuthTokenConfig: BasicAuthTokenConfig = new BasicAuthTokenConfig(mock[AppConfig]) {
    override val token: String = TestData.basicAuthTokenValue
  }
  private val mockWorkItemRepo = mock[NotificationRepo]
  private val controller = new CustomsNotificationController()(
    Helpers.stubControllerComponents(),
    incomingNotificationService,
    mockDateTimeService,
    mockUuidService,
    mockBasicAuthTokenConfig,
    mockWorkItemRepo,
    mockNotificationLogger,
    Helpers.stubControllerComponents().executionContext)

  "CustomsNotificationController" when {

    "processing a request for submit" should {

      "respond with status 202 Accepted for a valid request" in {
        when(incomingNotificationService.process(eqTo(TestData.ValidXml))(eqTo(TestData.RequestMetaData), *))
          .thenReturn(Future.successful(Right(())))
        val actualF = controller.submit().apply(TestData.ValidSubmitRequest)

        actualF.map(_ shouldBe Results.Accepted)
      }

      "respond with status 400 Bad Request for a badly-formed XML payload" in {
        val badlyFormedXml = "<xml><</xml>"
        val invalidRequest = TestData.ValidSubmitRequest.withTextBody(badlyFormedXml)
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "Request body does not contain well-formed XML.").XmlResult)
      }

      "respond with status 400 Bad Request for a non-XML Content-Type header" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(CONTENT_TYPE -> MimeTypes.JSON)
        val actualF = controller.submit().apply(invalidRequest)

        // TODO: Confirm if we should change the message to point out bad Content-Type header
        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "Request body does not contain well-formed XML.").XmlResult)
      }

      "respond with status 401 Unauthorized for invalid Authorization header" in {
        val unauthorizedRequest = TestData.ValidSubmitRequest.withHeaders(AUTHORIZATION -> "INVALID TOKEN")
        val actualF = controller.submit().apply(unauthorizedRequest)
        actualF.map(_ shouldBe ErrorResponse(UNAUTHORIZED, "UNAUTHORIZED", "Basic token is missing or not authorized").XmlResult)
      }

      "respond with status 406 Not Acceptable for a missing Accept request header" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(TestData.ValidHeaders.remove(ACCEPT))
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(NOT_ACCEPTABLE, "ACCEPT_HEADER_INVALID", "The Accept header is missing").XmlResult)
      }

      "respond with status 406 Not Acceptable for an invalid Accept request header" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(ACCEPT -> MimeTypes.JSON)
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(NOT_ACCEPTABLE, "ACCEPT_HEADER_INVALID", "The Accept header is invalid").XmlResult)
      }

      "respond with status 400 Bad Request for a missing X-Conversation-ID header" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(TestData.ValidHeaders.remove(X_CONVERSATION_ID_HEADER_NAME))
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-Conversation-ID header is missing").XmlResult)
      }

      "respond with status 400 Bad Request for a X-Conversation-ID header that is not a valid UUID" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(X_CONVERSATION_ID_HEADER_NAME -> "not-a-uuid")
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-Conversation-ID header is invalid").XmlResult)
      }

      "respond with status 400 Bad Request for a missing X-CDS-Client-ID header" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(TestData.ValidHeaders.remove(X_CLIENT_SUB_ID_HEADER_NAME))
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-CDS-Client-ID header is missing").XmlResult)
      }

      "respond with status 400 Bad Request for a X-CDS-Client-ID header that is not a valid UUID" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(X_CLIENT_SUB_ID_HEADER_NAME -> "not-a-uuid")
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-CDS-Client-ID header is invalid").XmlResult)
      }

      "respond with status 202 Accepted when the X-Correlation-ID header is missing" in {
        val requestMetadataWithoutCorrelationId = TestData.RequestMetaData.copy(maybeCorrelationId = None)
        when(incomingNotificationService.process(eqTo(TestData.ValidXml))(eqTo(requestMetadataWithoutCorrelationId), *))
          .thenReturn(Future.successful(Right(())))
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(TestData.ValidHeaders.remove(X_CORRELATION_ID_HEADER_NAME))
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe Results.Accepted)
      }

      "respond with status 400 Bad Request for a X-Correlation-ID header that is too long" in {
        val invalidRequest = TestData.ValidSubmitRequest.withHeaders(X_CORRELATION_ID_HEADER_NAME -> "1234567890123456789012345678901234567")
        val actualF = controller.submit().apply(invalidRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-Correlation-ID header is invalid").XmlResult)
      }

      "respond with status 400 Bad Request when there is a DeclarantNotFound error from IncomingNotificationService" in {
        when(incomingNotificationService.process(eqTo(TestData.ValidXml))(eqTo(TestData.RequestMetaData), *))
          .thenReturn(Future.successful(Left(DeclarantNotFound)))

        val actualF = controller.submit().apply(TestData.ValidSubmitRequest)

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-CDS-Client-ID header is invalid").XmlResult)
      }

      "respond with status 500 Internal Server Error when there is a InternalServiceError from IncomingNotificationService" in {
        when(incomingNotificationService.process(eqTo(TestData.ValidXml))(eqTo(TestData.RequestMetaData), *))
          .thenReturn(Future.successful(Left(InternalServiceError)))

        val actualF = controller.submit().apply(TestData.ValidSubmitRequest)

        actualF.map(_ shouldBe ErrorResponse(INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Internal server error").XmlResult)
      }
    }

    "processing a request for blockedCount" should {

      "respond with status 200 OK for a valid request" in {
        val expectedBlockedCount = 42
        when(mockWorkItemRepo.blockedCount(eqTo(TestData.ClientId)))
          .thenReturn(Future.successful(Right(expectedBlockedCount)))
        val expectedBody = s"<?xml version='1.0' encoding='UTF-8'?>\n<pushNotificationBlockedCount>$expectedBlockedCount</pushNotificationBlockedCount>"
        val actualF = controller.blockedCount().apply(TestData.ValidBlockedRequest)

        actualF.map { actual =>
          actual.header.status shouldBe OK
          contentAsString(actualF) shouldBe expectedBody
          contentType(actualF) shouldBe Some(MimeTypes.XML)
        }
      }

      "respond with status 400 Bad Request for a missing X-Client-ID header" in {
        val actualF = controller.blockedCount().apply(FakeRequest())

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-Client-ID header is missing").XmlResult)
      }
    }

    "processing a request for deleteBlocked" should {

      "respond with status 204 No Content for a valid request" in {
        val expectedDeletedCount = 42
        when(mockWorkItemRepo.unblockFailedAndBlocked(eqTo(TestData.ClientId)))
          .thenReturn(Future.successful(Right(expectedDeletedCount)))
        val actualF = controller.deleteBlocked().apply(TestData.ValidBlockedRequest)

        actualF.map(_.header.status shouldBe NO_CONTENT)
      }

      "respond with status 400 Bad Request for a missing X-Client-ID header" in {
        val actualF = controller.deleteBlocked().apply(FakeRequest())

        actualF.map(_ shouldBe ErrorResponse(BAD_REQUEST, BadRequestCode, "The X-Client-ID header is missing").XmlResult)
      }
    }
  }
}

object CustomsNotificationControllerSpec {
  object TestData {
    val ClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-2222-4444-8888-161616161616"))
    val ConversationId = models.ConversationId(UUID.fromString("00000000-4444-4444-AAAA-AAAAAAAAAAAA"))
    val NotificationId = models.NotificationId(UUID.fromString("00000000-9999-4444-9999-444444444444"))
    val BadgeId = "ABCDEF1234"
    val SubmitterId = "IAMSUBMITTER"
    val CorrelationId = "CORRID2234"
    val ClientId = models.ClientId("Client1")
    val ValidXml: NodeSeq = <Foo>Bar</Foo>
    val StartTime = ZonedDateTime.of(2023, 12, 25, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:ignore
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

    val RequestMetaData: RequestMetadata = RequestMetadata(
      ClientSubscriptionId,
      ConversationId,
      NotificationId,
      Some(Header(X_BADGE_ID_HEADER_NAME, BadgeId)),
      Some(Header(X_SUBMITTER_ID_HEADER_NAME, SubmitterId)),
      Some(Header(X_CORRELATION_ID_HEADER_NAME, CorrelationId)),
      None, None, None, StartTime)

    val ValidBlockedRequest: FakeRequest[AnyContent] = FakeRequest().withHeaders(X_CLIENT_ID_HEADER_NAME -> ClientId.toString)
  }
}
