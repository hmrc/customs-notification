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

package uk.gov.hmrc.customs.notification.connectors

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.{ACCEPTED, BAD_REQUEST, INTERNAL_SERVER_ERROR}
import play.api.http.{HeaderNames, MimeTypes}
import play.api.test.Helpers.{ACCEPT, AUTHORIZATION, CONTENT_TYPE}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.IntegrationSpecBase
import uk.gov.hmrc.customs.notification.connectors.SendConnector.*
import uk.gov.hmrc.customs.notification.connectors.SendConnector.Request.*
import uk.gov.hmrc.customs.notification.models.SendToPullQueue
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor.*
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.TestData.Implicits.*
import uk.gov.hmrc.customs.notification.util.WireMockHelpers

import java.net.URL

/**
 * Convenience class to only test this suite, as no app is available when running suite directly
 */
@DoNotDiscover
private class TestOnlySendConnectorSpec extends Suite with IntegrationSpecBase {
  override def nestedSuites: IndexedSeq[Suite] =
    Vector(new SendConnectorSpec(wireMockServer))
}

@DoNotDiscover
class SendConnectorSpec(val wireMockServer: WireMockServer) extends AnyWordSpec
  with WireMockHelpers
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with BeforeAndAfterEach
  with Matchers {

  private def connector = app.injector.instanceOf[SendConnector]

  "SendConnector when making an external push request" should {
    "return a Success(ExternalPush) when external service responds with 200 OK" in {
      stub(post)(ExternalPush, ACCEPTED)

      val expected = Right(SuccessfullySent(ExternalPushDescriptor))

      val actual = await(connector.send(Notification, PushCallbackData))
      actual shouldBe expected
    }

    "send the correct body and headers" in {
      await(connector.send(Notification, PushCallbackData))

      verify(
        WireMock.exactly(1),
        validExternalPushRequest()
      )
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      stub(post)(ExternalPush, BAD_REQUEST)

      val expected = Left(SendConnector.ClientError)

      val actual = await(connector.send(Notification, PushCallbackData))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)

      val expected = Left(SendConnector.ServerError)

      val actual = await(connector.send(Notification, PushCallbackData))

      actual shouldBe expected
    }
  }


  "SendConnector when making an internal push request" should {
    val internalClientNotification = Notification.copy(clientId = InternalClientId)
    val internalPushCallbackData =
    PushCallbackData
      .copy(
        callbackUrl = new URL(s"http://$testHost:$testPort${PathFor.InternalPush}")
      )

    "return a Success(InternalPush) when external service responds with 200 OK" in {
      stub(post)(InternalPush, ACCEPTED)

      val expected = Right(SuccessfullySent(InternalPushDescriptor))

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData))

      actual shouldBe expected
    }

    "send the correct body and all headers" in {
      val expectedBody = equalToXml(Payload.toString)

      await(connector.send(internalClientNotification, internalPushCallbackData))

      verify(postRequestedFor(urlMatching(InternalPush))
        .withRequestBody(expectedBody)
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(ACCEPT, equalTo(MimeTypes.XML))
        .withHeader(AUTHORIZATION, equalTo(PushSecurityToken.value))
        .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(ConversationId.toString))
        .withHeader(X_CORRELATION_ID_HEADER_NAME, equalTo(CorrelationId))
        .withHeader(X_SUBMITTER_ID_HEADER_NAME, equalTo(SubmitterId))
        .withHeader(X_BADGE_ID_HEADER_NAME, equalTo(BadgeId))
        .withHeader(ISSUE_DATE_TIME_HEADER_NAME, equalTo(IssueDateTime.toString))
      )
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      stub(post)(InternalPush, BAD_REQUEST)

      val expected = Left(SendConnector.ClientError)

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stub(post)(InternalPush, INTERNAL_SERVER_ERROR)

      val expected = Left(SendConnector.ServerError)

      val actual = await(connector.send(internalClientNotification, internalPushCallbackData))

      actual shouldBe expected
    }
  }

  "SendConnector when making an pull queue request" should {

    "return a Success(Pull) when external service responds with 200 OK" in {
      stub(post)(PullQueue, ACCEPTED)

      val expected = Right(SuccessfullySent(PullDescriptor))

      val actual = await(connector.send(Notification, SendToPullQueue))
      actual shouldBe expected
    }

    "send the correct body and headers" in {
      val expectedBody = equalToXml(Payload.toString)

      await(connector.send(Notification, SendToPullQueue))

      verify(postRequestedFor(urlMatching(PullQueue))
        .withRequestBody(expectedBody)
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
        .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(ConversationId.toString))
        .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(TranslatedCsid.toString))
        .withoutHeader(HeaderNames.AUTHORIZATION)
        .withoutHeader(ISSUE_DATE_TIME_HEADER_NAME)
      )
    }

    "return an ClientSendError(Pull) given a Bad Request 400 response" in {
      stub(post)(PullQueue, BAD_REQUEST)

      val expected = Left(SendConnector.ClientError)

      val actual = await(connector.send(Notification, SendToPullQueue))

      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      stub(post)(PullQueue, INTERNAL_SERVER_ERROR)

      val expected = Left(SendConnector.ServerError)

      val actual = await(connector.send(Notification, SendToPullQueue))

      actual shouldBe expected
    }
  }
}
