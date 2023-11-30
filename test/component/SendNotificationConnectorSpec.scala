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

package component

import com.github.tomakehurst.wiremock.client.WireMock._
import integration.IntegrationBaseSpec
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, OK}
import play.api.http.{HeaderNames, MimeTypes}
import play.api.libs.json.Json
import play.api.test.Helpers.{ACCEPT, AUTHORIZATION, CONTENT_TYPE}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.connectors.SendConnector
import uk.gov.hmrc.customs.notification.connectors.SendConnector._
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models.SendToPullQueue
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.http.HeaderCarrier
import util.{IntegrationTestData, TestData}

import java.net.URL

/**
 * Convenience class to only test this suite, as running suite directly will complain with the following:
 * "Trait ConfiguredServer needs an Application value associated with key "org.scalatestplus.play.app" in the config map."
 */
@DoNotDiscover
private class TestOnlySendConnectorSpec extends Suites(new SendConnectorSpec) with IntegrationBaseSpec

@DoNotDiscover
class SendConnectorSpec extends AnyWordSpec
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with Matchers {

  private def connector = app.injector.instanceOf[SendConnector]

  implicit val hc: HeaderCarrier = TestData.HeaderCarrier

  "SendConnector when making an external push request" should {
    "return a Success(ExternalPush) when external service responds with 200 OK" in {
      stubFor(post(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        .willReturn(aResponse().withStatus(OK)))

      val expected = Right(SuccessfullySent(ExternalPush(TestData.PushCallbackData)))

      val actual = await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))
      actual shouldBe expected
    }

    "send the correct body" in {
      await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        .withRequestBody(equalToJson {
          Json.obj(
            "url" -> TestData.ClientCallbackUrl.toString,
            "conversationId" -> TestData.ConversationId.toString,
            "authHeaderToken" -> TestData.PushSecurityToken.value,
            "outboundCallHeaders" -> Json.arr(
              Json.obj("name" -> "X-Badge-Identifier", "value" -> TestData.BadgeId),
              Json.obj("name" -> "X-Submitter-Identifier", "value" -> TestData.SubmitterId),
              Json.obj("name" -> "X-Correlation-ID", "value" -> TestData.CorrelationId)),
            "xmlPayload" -> TestData.ValidXml.toString
          ).toString
        }))
    }

    "send the required headers" in {
      await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
        .withHeader(NOTIFICATION_ID_HEADER_NAME, equalTo(TestData.NotificationId.toString)))
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      stubFor(post(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        .willReturn(aResponse().withStatus(BAD_REQUEST)))

      val expected = Left(SendConnector.ClientSendError(Some(BAD_REQUEST)))

      val actual = await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stubFor(post(urlMatching(IntegrationTestData.ExternalPushUrlContext))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      val expected = Left(SendConnector.ServerSendError(INTERNAL_SERVER_ERROR))

      val actual = await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))

      actual shouldBe expected
    }
  }


  "SendConnector when making an internal push request" should {
    val internalPushUrl = new URL(s"${IntegrationTestData.TestOrigin}${IntegrationTestData.InternalPushUrlContext}")
    val internalClientNotification = TestData.Notification.copy(clientId = TestData.InternalClientId)
    val internalPushCallbackData = TestData.PushCallbackData.copy(callbackUrl = internalPushUrl)

    "return a Success(InternalPush) when external service responds with 200 OK" in {
      stubFor(post(urlMatching(IntegrationTestData.InternalPushUrlContext))
        .willReturn(aResponse().withStatus(OK)))

      val internalPushUrl = new URL(s"${IntegrationTestData.TestOrigin}${IntegrationTestData.InternalPushUrlContext}")
      val internalClientNotification = TestData.Notification.copy(clientId = TestData.InternalClientId)
      val internalPushCallbackData = TestData.PushCallbackData.copy(callbackUrl = internalPushUrl)
      val expected = Right(SuccessfullySent(SendConnector.InternalPush(internalPushCallbackData)))

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData, TestData.Notification))

      actual shouldBe expected
    }

    "send the correct body" in {
      val expectedBody = equalToXml(TestData.ValidXml.toString)

      await(connector.send(internalClientNotification, internalPushCallbackData, TestData.Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.InternalPushUrlContext))
        .withRequestBody(expectedBody))
    }

    "send all headers" in {
      await(connector.send(internalClientNotification, internalPushCallbackData, TestData.Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.InternalPushUrlContext))
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(ACCEPT, equalTo(MimeTypes.XML))
        .withHeader(AUTHORIZATION, equalTo(TestData.PushSecurityToken.value))
        .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(TestData.ConversationId.toString))
        .withHeader(X_CORRELATION_ID_HEADER_NAME, equalTo(TestData.CorrelationId))
        .withHeader(X_SUBMITTER_ID_HEADER_NAME, equalTo(TestData.SubmitterId))
        .withHeader(X_BADGE_ID_HEADER_NAME, equalTo(TestData.BadgeId))
        .withHeader(ISSUE_DATE_TIME_HEADER_NAME, equalTo(TestData.IssueDateTime.toString)))
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      stubFor(post(urlMatching(IntegrationTestData.InternalPushUrlContext))
        .willReturn(aResponse().withStatus(BAD_REQUEST)))

      val expected = Left(SendConnector.ClientSendError(Some(BAD_REQUEST)))

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData, TestData.Notification))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stubFor(post(urlMatching(IntegrationTestData.InternalPushUrlContext))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      val expected = Left(SendConnector.ServerSendError(INTERNAL_SERVER_ERROR))

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData, TestData.Notification))

      actual shouldBe expected
    }
  }

  "SendConnector when making an pull queue request" should {

    "return a Success(Pull) when external service responds with 200 OK" in {
      stubFor(post(urlMatching(IntegrationTestData.PullQueueContext))
        .willReturn(aResponse().withStatus(OK)))

      val expected = Right(SuccessfullySent(Pull))

      val actual = await(connector.send(TestData.Notification, SendToPullQueue, TestData.Notification))
      actual shouldBe expected
    }

    "send the correct body" in {
      val expectedBody = equalToXml(TestData.ValidXml.toString)

      await(connector.send(TestData.Notification, SendToPullQueue, TestData.Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.PullQueueContext))
        .withRequestBody(expectedBody))
    }

    "send the required headers" in {
      await(connector.send(TestData.Notification, SendToPullQueue, TestData.Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.PullQueueContext))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(TestData.ConversationId.toString))
        .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(TestData.NewClientSubscriptionId.toString))
        .withoutHeader(HeaderNames.AUTHORIZATION)
        .withoutHeader(ISSUE_DATE_TIME_HEADER_NAME))
    }

    "return an ClientSendError(Pull) given a Bad Request 400 response" in {
      stubFor(post(urlMatching(IntegrationTestData.PullQueueContext))
        .willReturn(aResponse().withStatus(BAD_REQUEST)))

      val expected = Left(SendConnector.ClientSendError(Some(BAD_REQUEST)))

      val actual = await(connector.send(TestData.Notification, SendToPullQueue, TestData.Notification))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stubFor(post(urlMatching(IntegrationTestData.PullQueueContext))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      val expected = Left(SendConnector.ServerSendError(INTERNAL_SERVER_ERROR))

      val actual = await(connector.send(TestData.Notification, SendToPullQueue, TestData.Notification))

      actual shouldBe expected
    }
  }
}
