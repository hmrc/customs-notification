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

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.bson.types.ObjectId
import play.api.http.HeaderNames.ACCEPT
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.api.test.Helpers.{ACCEPTED, CONTENT_TYPE, OK}
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.util.HeaderNames.{X_BADGE_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME, X_SUBMITTER_ID_HEADER_NAME}
import util.IntegrationTestData.Responses.clientDataOkFor
import util.TestData._

import java.net.URL
import java.util.UUID
import scala.util.Random

object IntegrationTestData {
  val TestHost = "localhost"
  val TestPort = 9666
  val TestOrigin = s"http://$TestHost:$TestPort"

  val ApiSubsFieldsUrlContext = "/field"
  val InternalPushUrlContext = "/some-custom-internal-push-url"
  val ExternalPushUrlContext = "/notify-customs-declarant"
  val MetricsUrlContext = "/log-times"
  val PullQueueContext = "/queue"

  val AnotherObjectId = new ObjectId("bbbbbbbbbbbbbbbbbbbbbbbb")
  val ThirdObjectId = new ObjectId("cccccccccccccccccccccccc")
  val FourthObjectId = new ObjectId("dddddddddddddddddddddddd")
  val AnotherClientId = models.ClientId("Client2")
  val AnotherClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-9999-4444-7777-555555555555"))
  val AnotherCallbackUrl = new URL("http://www.example.edu")

  object Responses {
    def clientDataOkFor(clientId: models.ClientId,
                        csid: models.ClientSubscriptionId,
                        callbackUrl: Option[URL]): String = {
      val maybeCallbackUrlKv = callbackUrl.fold("")(c => s"""\n    "callbackUrl": "${c.toString}",""")
      s"""
         |{
         |  "clientId" : "$clientId",
         |  "apiContext" : "customs/declarations",
         |  "apiVersion" : "1.0",
         |  "fieldsId" : "${csid.toString}",
         |  "fields": {$maybeCallbackUrlKv
         |    "securityToken": "${PushSecurityToken.value}"
         |  }
         |}
         |""".stripMargin
    }
  }

  object Stubs {
    def validExternalPushRequestFor(callbackUrl: URL): RequestPatternBuilder = postRequestedFor(urlMatching(s"$ExternalPushUrlContext"))
      .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson {
        Json.obj(
          "conversationId" -> ConversationId.toString,
          "url" -> callbackUrl.toString,
          "authHeaderToken" -> PushSecurityToken.value,
          "outboundCallHeaders" -> Json.arr(
            Json.obj("name" -> X_BADGE_ID_HEADER_NAME, "value" -> BadgeId),
            Json.obj("name" -> X_SUBMITTER_ID_HEADER_NAME, "value" -> SubmitterId),
            Json.obj("name" -> X_CORRELATION_ID_HEADER_NAME, "value" -> CorrelationId),
          ),
          "xmlPayload" -> Payload.toString
        ).toString
      })

    def stubClientDataOk(): Unit =
      stubClientDataFor(ClientId, NewClientSubscriptionId, Some(ClientCallbackUrl))

    def stubClientDataFor(clientId: models.ClientId,
                          csid: models.ClientSubscriptionId,
                          callbackUrl: Option[URL]): Unit = {
      stubFor(get(urlMatching(s"$ApiSubsFieldsUrlContext/${csid.toString}"))
        .willReturn(aResponse().withStatus(OK).withBody(clientDataOkFor(clientId, csid, callbackUrl))))
    }

    def stubMetricsAccepted(): Unit = {
      stubFor(post(urlMatching(s"$MetricsUrlContext"))
        .willReturn(aResponse().withStatus(ACCEPTED)))
    }

    def stubExternalPush(responseStatus: Int): Unit = {
      stubFor(post(urlMatching(s"$ExternalPushUrlContext"))
        .willReturn(aResponse().withStatus(responseStatus)))
    }

    /**
     * Set up stubbing for a chain of states
     *
     * @param actionToResponseCode A sequence of responses keyed by a unique descriptive string
     */
    def stubWithScenarios(context: String)
                         (actionToResponseCode: (String, Int)*): Unit = {
      require(actionToResponseCode.nonEmpty)
      val randomScenarioName = Random.nextString(2)
      val (keys, values) = actionToResponseCode.unzip
      val slidingPairsOfKeys = (Scenario.STARTED :: keys.toList).sliding(2)

      (slidingPairsOfKeys zip values).foreach {
        case (List(before, after), responseStatus) =>
          stubFor(post(urlMatching(context))
            .inScenario(randomScenarioName)
            .whenScenarioStateIs(before)
            .willSetStateTo(after)
            .willReturn(aResponse().withStatus(responseStatus)))
        case _ => throw new IllegalStateException()
      }
    }
  }
}
