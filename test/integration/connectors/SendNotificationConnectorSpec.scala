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

package integration.connectors

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import integration.{IntegrationBaseSpec, IntegrationRouter, Spy}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, DoNotDiscover, Inside, Suites}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, OK}
import play.api.http.{HeaderNames, MimeTypes}
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, Json}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.connectors.SendNotificationConnector
import uk.gov.hmrc.customs.notification.connectors.SendNotificationConnector._
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.util.HeaderNames.NOTIFICATION_ID_HEADER_NAME
import uk.gov.hmrc.http.HeaderCarrier
import util.TestData

import java.net.URL

/**
 * Convenience class to only test this suite, as running suite directly will complain with the following:
 * "Trait ConfiguredServer needs an Application value associated with key "org.scalatestplus.play.app" in the config map."
 */
@DoNotDiscover
private class TestOnlySendNotificationConnectorSpec extends Suites(new SendNotificationConnectorSpec) with IntegrationBaseSpec

@DoNotDiscover
class SendNotificationConnectorSpec extends AnyWordSpec
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with Matchers
  with Inside {

  private def connector = app.injector.instanceOf[SendNotificationConnector]

  implicit val hc: HeaderCarrier = TestData.EmptyHeaderCarrier

  lazy val wireMockServer = new WireMockServer(wireMockConfig().port(IntegrationRouter.TestPort))

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (!wireMockServer.isRunning) wireMockServer.start()
    WireMock.configureFor(IntegrationRouter.TestHost, IntegrationRouter.TestPort)
  }

  override def afterAll(): Unit = {
    try {
      super.afterAll()
    } finally {
      wireMockServer.stop()
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    wireMockServer.resetAll()
  }

  def stubForOk(path: String) =
    stubFor(post(urlMatching(path))
      .willReturn(aResponse().withStatus(OK)))

  "SendNotificationConnector when making an external push request" should {

    "return a Success(ExternalPush) when external service responds with 200 OK" in {
      stubForOk(IntegrationRouter.ExternalPushUrlContext)

      val expected = Right(SuccessfullySent(ExternalPush(TestData.PushCallbackData)))

      val actual = await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))
      actual shouldBe expected
    }

    "send the correct body" in {
      stubForOk(IntegrationRouter.ExternalPushUrlContext)

      val expectedBody =
        Json.obj(
          "url" -> TestData.ClientPushUrl.toString,
          "conversationId" -> TestData.ConversationId.toString,
          "authHeaderToken" -> TestData.PushSecurityToken.value,
          "outboundCallHeaders" -> Json.arr(
            Json.obj("name" -> "X-Badge-Identifier", "value" -> TestData.BadgeId),
            Json.obj("name" -> "X-Submitter-Identifier", "value" -> TestData.SubmitterId),
            Json.obj("name" -> "X-Correlation-ID", "value" -> TestData.CorrelationId)),
          "xmlPayload" -> TestData.ValidXml.toString
        )

      await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))

      verify(1,
        postRequestedFor(urlMatching(IntegrationRouter.ExternalPushUrlContext))
          .withRequestBody(equalToJson(expectedBody.toString)))
    }

    "send the required headers" in {
      val expectedHeaders = List(
        HeaderNames.ACCEPT -> MimeTypes.JSON,
        HeaderNames.CONTENT_TYPE -> MimeTypes.JSON,
        NOTIFICATION_ID_HEADER_NAME -> TestData.NotificationId.toString)

      await(connector.send(TestData.Notification, TestData.PushCallbackData, TestData.Notification))

      //      spy.request.headers should contain allElementsOf expectedHeaders
    }

    "return an ClientSendError(ExternalPush) given a Bad Request 400 response" in {
      val expected = Left(SendNotificationConnector.ClientSendError(Some(BAD_REQUEST)))

      val badRequestPushCallbackData = TestData.PushCallbackData.copy(callbackUrl = TestData.BadRequestUrl)
      val actual = await(connector.send(TestData.Notification, badRequestPushCallbackData, TestData.Notification))
      actual shouldBe expected
    }

    "return an ServerSendError(ExternalPush) given a Internal Server Error 500 response" in {
      val expected = Left(SendNotificationConnector.ServerSendError(INTERNAL_SERVER_ERROR))

      val internalServerErrorPushCallbackData = TestData.PushCallbackData.copy(callbackUrl = TestData.InternalServerErrorUrl)
      val actual = await(connector.send(TestData.Notification, internalServerErrorPushCallbackData, TestData.Notification))
      actual shouldBe expected
    }
  }


  "SendNotificationConnector when making an internal push request" should {

    "return a Success(InternalPush)" in {
      val internalPushUrl = new URL(s"${IntegrationRouter.TestOrigin}${IntegrationRouter.InternalPushUrlContext}")
      val internalClientNotification = TestData.Notification.copy(clientId = TestData.InternalClientId)
      val internalPushCallbackData = TestData.PushCallbackData.copy(callbackUrl = internalPushUrl)
      val expected = Right(SuccessfullySent(SendNotificationConnector.InternalPush(internalPushCallbackData)))

      implicit val hc: HeaderCarrier = TestData.EmptyHeaderCarrier
      val actual = await(connector.send(internalClientNotification, internalPushCallbackData, TestData.Notification))
      actual shouldBe expected
    }
  }
}
