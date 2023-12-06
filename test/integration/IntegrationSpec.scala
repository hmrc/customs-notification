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

package integration

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import integration.IntegrationSpec._
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.WsScalaTestClient
import play.api.http.HeaderNames.ACCEPT
import play.api.http.MimeTypes
import play.api.libs.ws.WSResponse
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.config.RetryAvailableAfterConfig
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedButNotBlocked, Succeeded}
import uk.gov.hmrc.customs.notification.models.{ClientSubscriptionId, ProcessingStatus}
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.Dto
import uk.gov.hmrc.customs.notification.services.RetryService
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.mongo.workitem
import uk.gov.hmrc.mongo.workitem.WorkItem
import util.IntegrationTestData
import util.IntegrationTestData.Stubs._
import util.IntegrationTestData._
import util.TestData._

import scala.concurrent.duration.DurationInt

class IntegrationSpec extends AnyWordSpec
  with Matchers
  with WsScalaTestClient
  with IntegrationSpecBase
  with Logger {

  private val retryService = app.injector.instanceOf[RetryService]
  private val retryAvailableAfterConfig = app.injector.instanceOf[RetryAvailableAfterConfig]

  override protected val clearRepoBeforeEachTest: Boolean = true

  "customs-notification API" when {
    "successfully handling a notify request" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubMetricsAccepted()
        stubExternalPush(ACCEPTED)
      }

      "respond with 202 Accepted" in {
        setup()

        val response = makeValidNotifyRequest()

        response.status shouldBe ACCEPTED
      }

      "send the notification exactly once" in {
        setup()

        makeValidNotifyRequest()

        verify(
          WireMock.exactly(1),
          postRequestedFor(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        )
      }

      "save the succeeded notification in the database" in {
        setup()

        val expectedNotification =
          Repository.domainToRepo(
            notification = Notification,
            status = ProcessingStatus.Succeeded,
            updatedAt = TimeNow.toInstant)

        makeValidNotifyRequest()

        val db = getDatabase()

        db.loneElement shouldBe expectedNotification
      }
    }

    "handling a notify request and receives a 500 error when sending" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)
      }

      "return a 202 Accepted" in {
        setup()

        val response = makeValidNotifyRequest()

        response.status shouldBe ACCEPTED
      }

      "save the notification as FailedAndBlocked in the database" in {
        setup()

        val expectedNotification =
          Repository.domainToRepo(
            notification = Notification,
            status = ProcessingStatus.FailedAndBlocked,
            updatedAt = TimeNow.toInstant)
            .copy(
              failureCount = 1,
              availableAt = TimeNow.plusSeconds(retryAvailableAfterConfig.failedAndBlocked.toSeconds).toInstant)

        makeValidNotifyRequest()
        val db = getDatabase()

        db.loneElement shouldBe expectedNotification
      }

      "set all outstanding notifications for that client subscription ID to FailedAndBlocked" in {
        stubClientDataOk()
        stubMetricsAccepted()
        stubWithScenarios(ExternalPushUrlContext)(
          "Sending first notification" -> BAD_REQUEST,
          "Sending second notification" -> INTERNAL_SERVER_ERROR
        )

        makeValidNotifyRequest()

        mockObjectIdService.nextTimeGive(AnotherObjectId)
        makeValidNotifyRequest()
        val db = getDatabase()

        db should have size 2
        db.head.status shouldBe ProcessingStatus.FailedAndBlocked.legacyStatus
      }

    }

    "handling a notify request and receives a 400 error when sending" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubMetricsAccepted()
        stubExternalPush(BAD_REQUEST)
      }

      "return a 202 Accepted" in {
        setup()

        val response = makeValidNotifyRequest()

        response.status shouldBe ACCEPTED
      }

      "save the notification as FailedButNotBlocked in the database" in {
        setup()

        val expectedNotification =
          Repository.domainToRepo(
            notification = Notification,
            status = ProcessingStatus.FailedButNotBlocked,
            updatedAt = TimeNow.toInstant)
            .copy(
              failureCount = 1,
              availableAt = TimeNow.plusSeconds(retryAvailableAfterConfig.failedButNotBlocked.toSeconds).toInstant
            )

        makeValidNotifyRequest()
        val db = getDatabase()

        db.loneElement shouldBe expectedNotification
      }

    }

    "has an existing FailedAndBlocked notification" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)
        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherObjectId)
      }

      "not attempt to send subsequent notifications for that client subscription ID during blocked state" in {
        setup()
        stubClientDataFor(ClientId, NewClientSubscriptionId, Some(AnotherCallbackUrl))

        makeValidNotifyRequest()

        verify(WireMock.exactly(0), validExternalPushRequestFor(AnotherCallbackUrl))
      }

      "continue sending notifications for other client subscriptions IDs under that client ID during blocked state" in {
        setup()
        stubClientDataFor(ClientId, AnotherClientSubscriptionId, Some(AnotherCallbackUrl))

        makeValidNotifyRequestFor(AnotherClientSubscriptionId)

        verify(WireMock.exactly(1), validExternalPushRequestFor(AnotherCallbackUrl))
      }

      "continue sending notifications for another client ID during blocked state" in {
        setup()
        stubClientDataFor(AnotherClientId, AnotherClientSubscriptionId, Some(AnotherCallbackUrl))

        makeValidNotifyRequestFor(AnotherClientSubscriptionId)

        verify(WireMock.exactly(1), validExternalPushRequestFor(AnotherCallbackUrl))
      }
    }

    "has an existing FailedButNotBlocked notification" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubMetricsAccepted()
        stubExternalPush(BAD_REQUEST)
        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherObjectId)
      }

      "be able to send a subsequent notification for that client subscription ID" in {
        setup()
        stubClientDataFor(ClientId, NewClientSubscriptionId, Some(AnotherCallbackUrl))
        makeValidNotifyRequest()

        verify(WireMock.exactly(1), validExternalPushRequestFor(AnotherCallbackUrl))
      }
    }

    "receiving a notify request with a malformed XML payload" should {
      "respond with status 400 Bad Request" in {
        val badlyFormedXml = "<xml><</xml>"
        val expectedBody =
          "<errorResponse><code>BAD_REQUEST</code><message>Request body does not contain well-formed XML.</message></errorResponse>"

        val response = await(wsUrl(NotifyEndpoint)
          .withHttpHeaders(validControllerRequestHeaders(OldClientSubscriptionId).toList: _*)
          .post(badlyFormedXml))

        response.status shouldBe BAD_REQUEST
        response.body shouldBe expectedBody
      }
    }

    "receiving a notify request with the wrong Content-Type header" should {
      "respond with status 415 Unsupported Media Type" in {
        val expectedBody =
          "<errorResponse><code>UNSUPPORTED_MEDIA_TYPE</code><message>The Content-Type header is missing or invalid.</message></errorResponse>"
        val response = await(wsUrl(NotifyEndpoint)
          .withHttpHeaders(
            (validControllerRequestHeaders(OldClientSubscriptionId) +
              (CONTENT_TYPE -> MimeTypes.JSON)
              ).toList: _*)
          .post(ValidXml))

        response.status shouldBe UNSUPPORTED_MEDIA_TYPE
        response.body shouldBe expectedBody
      }
    }

    "receiving a notify request with an invalid Authorization header" should {
      "respond with status 401 Unauthorized" in {
        val expectedBody =
          "<errorResponse><code>UNAUTHORIZED</code><message>Basic token is missing or not authorized.</message></errorResponse>"
        val response = await(wsUrl(NotifyEndpoint)
          .withHttpHeaders(
            (validControllerRequestHeaders(OldClientSubscriptionId) +
              (AUTHORIZATION -> "invalidToken")
              ).toList: _*)
          .post(ValidXml))

        response.status shouldBe UNAUTHORIZED
        response.body shouldBe expectedBody
      }
    }

    "receiving a notify request without a valid Accept request header" should {
      "respond with status 406 Not Acceptable" in {
        val expectedBody =
          "<errorResponse><code>ACCEPT_HEADER_INVALID</code><message>The Accept header is invalid.</message></errorResponse>"
        val response = await(wsUrl(NotifyEndpoint)
          .withHttpHeaders(
            (validControllerRequestHeaders(OldClientSubscriptionId) +
              (ACCEPT -> "invalid accept value")
              ).toList: _*)
          .post(ValidXml))

        response.status shouldBe NOT_ACCEPTABLE
        response.body shouldBe expectedBody
      }
    }

    "receiving a notify request with a missing X-Conversation-ID header" should {
      "respond with status 400 Bad Request" in {
        val expectedBody =
          "<errorResponse><code>BAD_REQUEST</code><message>The X-Conversation-ID header is missing.</message></errorResponse>"
        val response = makeValidNotifyRequestWithout(X_CONVERSATION_ID_HEADER_NAME)

        response.status shouldBe BAD_REQUEST
        response.body shouldBe expectedBody
      }
    }

    "receiving a notify request without a valid X-CDS-Client-ID header" should {
      "respond with status 400 Bad Request if it is missing" in {
        val expectedBody =
          "<errorResponse><code>BAD_REQUEST</code><message>The X-CDS-Client-ID header is missing.</message></errorResponse>"
        val response = makeValidNotifyRequestWithout(X_CLIENT_SUB_ID_HEADER_NAME)

        response.status shouldBe BAD_REQUEST
        response.body shouldBe expectedBody
      }

      "respond with status 400 Bad Request if it is not a valid UUID" in {
        val expectedBody =
          "<errorResponse><code>BAD_REQUEST</code><message>The X-CDS-Client-ID header is invalid.</message></errorResponse>"
        val response = makeValidNotifyRequestAdding(X_CLIENT_SUB_ID_HEADER_NAME -> "invalid csid")

        response.status shouldBe BAD_REQUEST
        response.body shouldBe expectedBody
      }
    }

    "receiving a notify request with a missing X-Correlation-ID header" should {
      "respond with status 202 Accepted if it is missing" in {
        val response = makeValidNotifyRequestWithout(X_CORRELATION_ID_HEADER_NAME)

        response.status shouldBe ACCEPTED
      }

      "respond with status 400 Accepted if it is too long" in {
        val expectedBody =
          "<errorResponse><code>BAD_REQUEST</code><message>The X-Correlation-ID header is invalid.</message></errorResponse>"
        val response = makeValidNotifyRequestAdding(X_CORRELATION_ID_HEADER_NAME -> "1234567890123456789012345678901234567")

        response.status shouldBe BAD_REQUEST
        response.body shouldBe expectedBody
      }
    }
  }

  "RetryService" when {
    "has two outstanding and one undue FailedButNotBlocked notifications" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubClientDataFor(
          ClientId,
          AnotherClientSubscriptionId,
          Some(ClientCallbackUrl)
        )
        stubMetricsAccepted()
        stubWithScenarios(ExternalPushUrlContext)(
          "Initialising first notification" -> BAD_REQUEST,
          "Initialising second notification" -> BAD_REQUEST,
          "Initialising third notification (for another client subscription ID)" -> BAD_REQUEST
        )
        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherObjectId)
        makeValidNotifyRequest()

        mockDateTimeService.travelForwardsInTime(retryAvailableAfterConfig.failedButNotBlocked + 1.second)
        mockObjectIdService.nextTimeGive(ThirdObjectId)
        makeValidNotifyRequestFor(AnotherClientSubscriptionId)
        WireMock.resetAllScenarios()
        WireMock.resetAllRequests()

        stubWithScenarios(ExternalPushUrlContext)(
          "First notification retry" -> ACCEPTED,
          "Second notification retry" -> ACCEPTED,
          "Third notification retry" -> ACCEPTED
        )
      }

      "retry only outstanding notifications and cache ClientData (only one ClientData request for each unique client subscription ID)" in {
        setup()
        val numberOfUniqueCsids = 2
        val expectedStatusesByCsid = Map(
          NewClientSubscriptionId -> Set(Succeeded.legacyStatus),
          AnotherClientSubscriptionId -> Set(FailedButNotBlocked.legacyStatus)
        )

        await(retryService.retryFailedButNotBlocked())

        val db = getDatabase()
        db should have size 3
        verify(
          WireMock.exactly(1),
          getRequestedFor(urlMatching(s"$ApiSubsFieldsUrlContext/${NewClientSubscriptionId.toString}"))
        )
        verify(
          WireMock.exactly(numberOfUniqueCsids),
          validExternalPushRequestFor(ClientCallbackUrl)
        )
        val actualStatusesByCsid = getLegacyStatusesByCsidFromDb()
        actualStatusesByCsid shouldBe expectedStatusesByCsid
      }
    }

    "has two client subscription IDs with two FailedAndBlocked notifications for each one" should {
      def setup(): Unit = {
        stubClientDataOk()
        stubClientDataFor(
          ClientId,
          AnotherClientSubscriptionId,
          Some(ClientCallbackUrl)
        )
        stubMetricsAccepted()
        stubWithScenarios(ExternalPushUrlContext)(
          "Initialising first notification for first csid" -> INTERNAL_SERVER_ERROR,
          "Initialising first notification for second csid" -> INTERNAL_SERVER_ERROR,
          "Retrying a notification for first csid" -> ACCEPTED,
          "Retrying a notification for second csid" -> ACCEPTED
        )
        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherObjectId)
        makeValidNotifyRequest()

        mockDateTimeService.travelForwardsInTime(1.day)

        mockObjectIdService.nextTimeGive(ThirdObjectId)
        makeValidNotifyRequestFor(AnotherClientSubscriptionId)
        mockObjectIdService.nextTimeGive(FourthObjectId)
        makeValidNotifyRequestFor(AnotherClientSubscriptionId)
      }

      "retry only one notification per client subscription ID and set the rest to FailedButNotBlocked" in {
        setup()
        mockDateTimeService.travelForwardsInTime(retryAvailableAfterConfig.failedAndBlocked + 1.day)
        val expectedStatusesByCsid = Map(
          NewClientSubscriptionId -> Set(
            ProcessingStatus.Succeeded.legacyStatus,
            ProcessingStatus.FailedButNotBlocked.legacyStatus
          ),
          AnotherClientSubscriptionId -> Set(
            ProcessingStatus.Succeeded.legacyStatus,
            ProcessingStatus.FailedButNotBlocked.legacyStatus
          )
        )

        await(retryService.retryFailedAndBlocked())

        val actualStatusesByCsid = getLegacyStatusesByCsidFromDb()
        actualStatusesByCsid shouldBe expectedStatusesByCsid
      }

      "not retry any notifications that are not due to be retried" in {
        setup()

        val expectedStatusesByCsid = Map(
          NewClientSubscriptionId -> Set(
            ProcessingStatus.Succeeded.legacyStatus,
            ProcessingStatus.FailedButNotBlocked.legacyStatus
          ),
          AnotherClientSubscriptionId -> Set(
            ProcessingStatus.FailedAndBlocked.legacyStatus,
            ProcessingStatus.FailedAndBlocked.legacyStatus
          )
        )

        await(retryService.retryFailedAndBlocked())

        val actualStatusesByCsid = getLegacyStatusesByCsidFromDb()
        actualStatusesByCsid shouldBe expectedStatusesByCsid
      }
    }
  }

  private def makeValidNotifyRequestAdding(newHeader: (String, String)): WSResponse =
    await(wsUrl(NotifyEndpoint)
      .withHttpHeaders((validControllerRequestHeaders(OldClientSubscriptionId) + newHeader).toList: _*)
      .post(ValidXml))

  private def makeValidNotifyRequestWithout(headerName: String): WSResponse =
    await(wsUrl(NotifyEndpoint)
      .withHttpHeaders(validControllerRequestHeaders(OldClientSubscriptionId).removed(headerName).toList: _*)
      .post(ValidXml))

  private def makeValidNotifyRequest(): WSResponse =
    makeValidNotifyRequestFor(OldClientSubscriptionId)

  private def makeValidNotifyRequestFor(csid: ClientSubscriptionId): WSResponse =
    await(wsUrl(NotifyEndpoint)
      .withHttpHeaders(validControllerRequestHeaders(csid).toList: _*)
      .post(ValidXml))

  private def getDatabase(): Seq[WorkItem[Dto.NotificationWorkItem]] =
    await(repo.collection.find().toFuture())

  private def getLegacyStatusesByCsidFromDb(): Map[ClientSubscriptionId, Set[workitem.ProcessingStatus]] = {
    getDatabase()
      .groupBy(_.item._id).view
      .mapValues(_
        .map(_.status)
        .toSet
      )
      .toMap
  }
}

object IntegrationSpec {
  private val NotifyEndpoint: String = "/customs-notification/notify"

  private def validControllerRequestHeaders(csid: ClientSubscriptionId): Map[String, String] = Map(
    X_CLIENT_SUB_ID_HEADER_NAME -> csid.toString,
    X_CONVERSATION_ID_HEADER_NAME -> ConversationId.toString,
    CONTENT_TYPE -> MimeTypes.XML,
    ACCEPT -> MimeTypes.XML,
    AUTHORIZATION -> BasicAuthTokenValue,
    X_BADGE_ID_HEADER_NAME -> BadgeId,
    X_SUBMITTER_ID_HEADER_NAME -> SubmitterId,
    X_CORRELATION_ID_HEADER_NAME -> CorrelationId,
    ISSUE_DATE_TIME_HEADER_NAME -> IssueDateTime.toString)
}