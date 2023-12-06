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

import uk.gov.hmrc.customs.notification.connectors.SendConnector.Request._
import com.github.tomakehurst.wiremock.client.WireMock._
import integration.IntegrationSpecBase
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
import uk.gov.hmrc.customs.notification.models.SendToPullQueue
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import util.IntegrationTestData._
import util.TestData.Implicits._
import util.TestData._

import java.net.URL

/**
 * Convenience class to only test this suite, as running suite directly will complain with the following:
 * "Trait ConfiguredServer needs an Application value associated with key "org.scalatestplus.play.app" in the config map."
 */
@DoNotDiscover
private class TestOnlySendConnectorSpec extends Suites(new SendConnectorSpec) with IntegrationSpecBase

@DoNotDiscover
class SendConnectorSpec extends AnyWordSpec
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with Matchers {

  private def connector = app.injector.instanceOf[SendConnector]

  "SendConnector when making an external push request" should {
    "return a Success(ExternalPush) when external service responds with 200 OK" in {
      stubFor(post(urlMatching(ExternalPushUrlContext))
        .willReturn(aResponse().withStatus(OK)))

      val expected = Right(SuccessfullySent(ExternalPushDescriptor))

      val actual = await(connector.send(Notification, PushCallbackData))
      actual shouldBe expected
    }

    "send the correct body" in {
      await(connector.send(Notification, PushCallbackData))

      verify(postRequestedFor(urlMatching(ExternalPushUrlContext))
        .withRequestBody(equalToJson {
          Json.obj(
            "url" -> ClientCallbackUrl.toString,
            "conversationId" -> ConversationId.toString,
            "authHeaderToken" -> PushSecurityToken.value,
            "outboundCallHeaders" -> Json.arr(
              Json.obj("name" -> "X-Badge-Identifier", "value" -> BadgeId),
              Json.obj("name" -> "X-Submitter-Identifier", "value" -> SubmitterId),
              Json.obj("name" -> "X-Correlation-ID", "value" -> CorrelationId)),
            "xmlPayload" -> Payload.toString
          ).toString
        }))
    }

    "send the required headers" in {
      await(connector.send(Notification, PushCallbackData))

      verify(postRequestedFor(urlMatching(ExternalPushUrlContext))
        .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
        .withHeader(NOTIFICATION_ID_HEADER_NAME, equalTo(NotificationId.toString))
      )
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      stubFor(post(urlMatching(ExternalPushUrlContext))
        .willReturn(aResponse().withStatus(BAD_REQUEST)))

      val expected = Left(SendConnector.ClientError)

      val actual = await(connector.send(Notification, PushCallbackData))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stubFor(post(urlMatching(ExternalPushUrlContext))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      val expected = Left(SendConnector.ServerError)

      val actual = await(connector.send(Notification, PushCallbackData))

      actual shouldBe expected
    }
  }


  "SendConnector when making an internal push request" should {
    val internalPushUrl = new URL(s"$TestOrigin$InternalPushUrlContext")
    val internalClientNotification = Notification.copy(clientId = InternalClientId)
    val internalPushCallbackData = PushCallbackData.copy(callbackUrl = internalPushUrl)

    "return a Success(InternalPush) when external service responds with 200 OK" in {
      stubFor(post(urlMatching(InternalPushUrlContext))
        .willReturn(aResponse().withStatus(OK)))

      val internalPushUrl = new URL(s"$TestOrigin$InternalPushUrlContext")
      val internalClientNotification = Notification.copy(clientId = InternalClientId)
      val internalPushCallbackData = PushCallbackData.copy(callbackUrl = internalPushUrl)
      val expected = Right(SuccessfullySent(InternalPushDescriptor))

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData))

      actual shouldBe expected
    }

    "send the correct body" in {
      val expectedBody = equalToXml(Payload.toString)

      await(connector.send(internalClientNotification, internalPushCallbackData))

      verify(postRequestedFor(urlMatching(InternalPushUrlContext))
        .withRequestBody(expectedBody))
    }

    "send all headers" in {
      await(connector.send(internalClientNotification, internalPushCallbackData))

      verify(postRequestedFor(urlMatching(InternalPushUrlContext))
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(ACCEPT, equalTo(MimeTypes.XML))
        .withHeader(AUTHORIZATION, equalTo(PushSecurityToken.value))
        .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(ConversationId.toString))
        .withHeader(X_CORRELATION_ID_HEADER_NAME, equalTo(CorrelationId))
        .withHeader(X_SUBMITTER_ID_HEADER_NAME, equalTo(SubmitterId))
        .withHeader(X_BADGE_ID_HEADER_NAME, equalTo(BadgeId))
        .withHeader(ISSUE_DATE_TIME_HEADER_NAME, equalTo(IssueDateTime.toString)))
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      stubFor(post(urlMatching(InternalPushUrlContext))
        .willReturn(aResponse().withStatus(BAD_REQUEST)))

      val expected = Left(SendConnector.ClientError)

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stubFor(post(urlMatching(InternalPushUrlContext))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      val expected = Left(SendConnector.ServerError)

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData))

      actual shouldBe expected
    }
  }

  "SendConnector when making an pull queue request" should {

    "return a Success(Pull) when external service responds with 200 OK" in {
      stubFor(post(urlMatching(PullQueueContext))
        .willReturn(aResponse().withStatus(OK)))

      val expected = Right(SuccessfullySent(PullDescriptor))

      val actual = await(connector.send(Notification, SendToPullQueue))
      actual shouldBe expected
    }

    "send the correct body" in {
      val expectedBody = equalToXml(Payload.toString)

      await(connector.send(Notification, SendToPullQueue))

      verify(postRequestedFor(urlMatching(PullQueueContext))
        .withRequestBody(expectedBody))
    }

    "send the required headers" in {
      await(connector.send(Notification, SendToPullQueue))

      verify(postRequestedFor(urlMatching(PullQueueContext))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(ConversationId.toString))
        .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(NewClientSubscriptionId.toString))
        .withoutHeader(HeaderNames.AUTHORIZATION)
        .withoutHeader(ISSUE_DATE_TIME_HEADER_NAME))
    }

    "return an ClientSendError(Pull) given a Bad Request 400 response" in {
      stubFor(post(urlMatching(PullQueueContext))
        .willReturn(aResponse().withStatus(BAD_REQUEST)))

      val expected = Left(SendConnector.ClientError)

      val actual = await(connector.send(Notification, SendToPullQueue))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stubFor(post(urlMatching(PullQueueContext))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      val expected = Left(SendConnector.ServerError)

      val actual = await(connector.send(Notification, SendToPullQueue))

      actual shouldBe expected
    }
  }
}
