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

package util

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, equalToJson, get, post, postRequestedFor, stubFor, urlMatching}
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import org.bson.types.ObjectId
import play.api.http.HeaderNames.ACCEPT
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.api.test.Helpers.{ACCEPTED, CONTENT_TYPE, OK}
import uk.gov.hmrc.customs.notification.models.{ClientId, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.util.HeaderNames.{X_BADGE_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME, X_SUBMITTER_ID_HEADER_NAME}
import util.IntegrationTestData.Responses.apiSubscriptionFieldsOkFor

import java.net.URL
import java.util.UUID

object IntegrationTestData {
  val TestHost = "localhost"
  val TestPort = 9666
  val TestOrigin = s"http://$TestHost:$TestPort"

  val ApiSubsFieldsUrlContext = "/field"
  val InternalPushUrlContext = "/some-custom-internal-push-url"
  val ExternalPushUrlContext = "/notify-customs-declarant"
  val MetricsUrlContext = "/log-times"
  val PullQueueContext = "/queue"

  val AnotherNotificationObjectId = new ObjectId("bbbbbbbbbbbbbbbbbbbbbbbb")
  val AnotherClientId = ClientId("Client2")
  val AnotherClientSubscriptionId = ClientSubscriptionId(UUID.fromString("00000000-9999-4444-7777-555555555555"))
  val AnotherCallbackUrl = new URL("http://www.example.edu")

  object Responses {
    def apiSubscriptionFieldsOkFor(clientId: ClientId, csid: ClientSubscriptionId, callbackUrl: URL): String =
      s"""
         |{
         |  "clientId" : "$clientId",
         |  "apiContext" : "customs/declarations",
         |  "apiVersion" : "1.0",
         |  "fieldsId" : "${csid.toString}",
         |  "fields": {
         |    "callbackUrl": "${callbackUrl.toString}",
         |    "securityToken": "${TestData.PushSecurityToken.value}"
         |  }
         |}
         |""".stripMargin
  }
  
  object Stubs {
     def validExternalPushRequestFor(callbackUrl: URL): RequestPatternBuilder = postRequestedFor(urlMatching(s"$ExternalPushUrlContext"))
      .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson {
        Json.obj(
          "conversationId" -> TestData.ConversationId.toString,
          "url" -> callbackUrl.toString,
          "authHeaderToken" -> TestData.PushSecurityToken.value,
          "outboundCallHeaders" -> Json.arr(
            Json.obj("name" -> X_BADGE_ID_HEADER_NAME, "value" -> TestData.BadgeId),
            Json.obj("name" -> X_SUBMITTER_ID_HEADER_NAME, "value" -> TestData.SubmitterId),
            Json.obj("name" -> X_CORRELATION_ID_HEADER_NAME, "value" -> TestData.CorrelationId),
          ),
          "xmlPayload" -> TestData.ValidXml.toString
        ).toString
      })

     def stubApiSubscriptionFieldsOk(): Unit =
      stubApiSubscriptionFieldsOkFor(TestData.ClientId, TestData.NewClientSubscriptionId, TestData.ClientCallbackUrl)

     def stubApiSubscriptionFieldsOkFor(clientId: ClientId,
                                               clientSubscriptionId: ClientSubscriptionId,
                                               callbackUrl: URL): Unit = {
      stubFor(get(urlMatching(s"$ApiSubsFieldsUrlContext/${clientSubscriptionId.toString}"))
        .willReturn(aResponse().withStatus(OK).withBody(apiSubscriptionFieldsOkFor(clientId, clientSubscriptionId, callbackUrl))))
    }

     def stubMetricsAccepted(): Unit = {
      stubFor(post(urlMatching(s"$MetricsUrlContext"))
        .willReturn(aResponse().withStatus(ACCEPTED)))
    }

     def stubExternalPush(responseStatus: Int): Unit = {
      stubFor(post(urlMatching(s"$ExternalPushUrlContext"))
        .willReturn(aResponse().withStatus(responseStatus)))
    }
  }
}
