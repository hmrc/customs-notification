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
import integration.ControllerSpec._
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.WsScalaTestClient
import play.api.http.HeaderNames.ACCEPT
import play.api.http.MimeTypes
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.models.{ClientId, ClientSubscriptionId, ProcessingStatus}
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import util.IntegrationTestData._
import util.IntegrationTestData.Stubs._
import util.{IntegrationTestData, TestData}

import java.net.URL
import java.util.UUID

class ControllerSpec extends AnyWordSpec
  with Matchers
  with WsScalaTestClient
  with IntegrationBaseSpec {
  implicit val wsClient: WSClient = app.injector.instanceOf[WSClient]

  private def makeValidNotifyRequest(): WSResponse =
    makeValidNotifyRequestFor(TestData.OldClientSubscriptionId)

  private def makeValidNotifyRequestFor(clientSubscriptionId: ClientSubscriptionId): WSResponse =
    await(wsUrl(s"/customs-notification/notify")
      .withHttpHeaders(validControllerRequestHeaders(clientSubscriptionId).toList: _*)
      .post(TestData.ValidXml))

  override protected val clearRepoBeforeEachTest: Boolean = true

  "customs-notification API" when {
    "handling a notify request" should {
      "respond with 202 Accepted" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(ACCEPTED)

        val response = makeValidNotifyRequest()

        response.status shouldBe ACCEPTED
      }

      "send the notification exactly once" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(ACCEPTED)

        makeValidNotifyRequest()

        verify(
          WireMock.exactly(1),
          postRequestedFor(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        )
      }

      "save the succeeded notification in the database" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(ACCEPTED)

        val expectedNotification =
          Repository.domainToRepo(
            notification = TestData.Notification,
            status = ProcessingStatus.Succeeded,
            mostRecentPushPullHttpStatus = None,
            availableAt = TestData.TimeNow.toInstant)

        makeValidNotifyRequest()

        val db = await(mockRepo.collection.find().toFuture())

        db.loneElement shouldBe expectedNotification
      }
    }

    "handling a notify request but receives a 500 error when sending" should {
      "return a 202 Accepted" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)

        val response = makeValidNotifyRequest()

        response.status shouldBe ACCEPTED
      }

      "save the notification as FailedAndBlocked in the database" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)

        val expectedNotification =
          Repository.domainToRepo(
            notification = TestData.Notification,
            status = ProcessingStatus.FailedAndBlocked,
            mostRecentPushPullHttpStatus = Some(INTERNAL_SERVER_ERROR),
            availableAt = TestData.TimeNow.toInstant)
            .copy(failureCount = 1)

        makeValidNotifyRequest()
        val db = await(mockRepo.collection.find().toFuture())

        db.loneElement shouldBe expectedNotification
      }

      "not attempt to send subsequent notifications for that client subscription ID during blocked state" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)

        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherNotificationObjectId)
        stubApiSubscriptionFieldsOkFor(TestData.ClientId, TestData.NewClientSubscriptionId, AnotherCallbackUrl)
        makeValidNotifyRequest()

        verify(WireMock.exactly(1), validExternalPushRequestFor(TestData.ClientCallbackUrl))
        verify(WireMock.exactly(0), validExternalPushRequestFor(AnotherCallbackUrl))
      }

      "continue sending notifications for other client subscriptions IDs under that client ID during blocked state" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)

        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherNotificationObjectId)
        stubApiSubscriptionFieldsOkFor(TestData.ClientId, AnotherClientSubscriptionId, AnotherCallbackUrl)
        makeValidNotifyRequestFor(AnotherClientSubscriptionId)

        verify(WireMock.exactly(1), validExternalPushRequestFor(TestData.ClientCallbackUrl))
        verify(WireMock.exactly(1), validExternalPushRequestFor(AnotherCallbackUrl))
      }

      "continue sending notifications for another client ID during blocked state" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)

        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherNotificationObjectId)
        stubApiSubscriptionFieldsOkFor(AnotherClientId, AnotherClientSubscriptionId, AnotherCallbackUrl)
        makeValidNotifyRequestFor(AnotherClientSubscriptionId)

        verify(WireMock.exactly(1), validExternalPushRequestFor(TestData.ClientCallbackUrl))
        verify(WireMock.exactly(1), validExternalPushRequestFor(AnotherCallbackUrl))
      }
    }

    "handling a notify request but receives a 400 error when sending" should {
      "return a 202 Accepted" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(BAD_REQUEST)

        val response = makeValidNotifyRequest()

        response.status shouldBe ACCEPTED
      }

      "save the notification as FailedButNotBlocked in the database" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(INTERNAL_SERVER_ERROR)

        val expectedNotification =
          Repository.domainToRepo(
            notification = TestData.Notification,
            status = ProcessingStatus.FailedAndBlocked,
            mostRecentPushPullHttpStatus = Some(INTERNAL_SERVER_ERROR),
            availableAt = TestData.TimeNow.toInstant)
            .copy(failureCount = 1)

        makeValidNotifyRequest()
        val db = await(mockRepo.collection.find().toFuture())

        db.loneElement shouldBe expectedNotification
      }

      "send subsequent notifications for that client subscription ID" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubExternalPush(BAD_REQUEST)

        makeValidNotifyRequest()
        mockObjectIdService.nextTimeGive(AnotherNotificationObjectId)
        stubApiSubscriptionFieldsOkFor(TestData.ClientId, TestData.NewClientSubscriptionId, AnotherCallbackUrl)
        makeValidNotifyRequest()

        verify(WireMock.exactly(1), validExternalPushRequestFor(TestData.ClientCallbackUrl))
        verify(WireMock.exactly(1), validExternalPushRequestFor(AnotherCallbackUrl))
      }
    }
  }
}

object ControllerSpec {
  private def validControllerRequestHeaders(clientSubscriptionId: ClientSubscriptionId) = Map(
    X_CLIENT_SUB_ID_HEADER_NAME -> clientSubscriptionId.toString,
    X_CONVERSATION_ID_HEADER_NAME -> TestData.ConversationId.toString,
    CONTENT_TYPE -> MimeTypes.XML,
    ACCEPT -> MimeTypes.XML,
    AUTHORIZATION -> TestData.BasicAuthTokenValue,
    X_BADGE_ID_HEADER_NAME -> TestData.BadgeId,
    X_SUBMITTER_ID_HEADER_NAME -> TestData.SubmitterId,
    X_CORRELATION_ID_HEADER_NAME -> TestData.CorrelationId,
    ISSUE_DATE_TIME_HEADER_NAME -> TestData.IssueDateTime.toString)
}