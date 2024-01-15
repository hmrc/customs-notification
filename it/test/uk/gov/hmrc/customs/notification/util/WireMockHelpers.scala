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

package uk.gov.hmrc.customs.notification.util

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.{RequestPatternBuilder, UrlPattern}
import org.scalatest.{BeforeAndAfterEach, Suite}
import play.api.http.HeaderNames.ACCEPT
import play.api.http.MimeTypes
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.api.test.Helpers.CONTENT_TYPE
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor
import uk.gov.hmrc.customs.notification.util.TestData.*

import java.net.URL

trait WireMockHelpers extends BeforeAndAfterEach {
  this: Suite =>

  val testHost = "localhost"
  val testPort = 9666

  protected def wireMockServer: WireMockServer

  override def beforeEach(): Unit = {
    wireMockServer.resetAll()
    super.beforeEach()
  }

  def stub(method: UrlPattern => MappingBuilder)
          (context: String,
           responseStatus: Int,
           // scalastyle:off null
           shouldReturnABodyOf: String = null): Unit = {
    stubFor(method(urlMatching(context))
      .willReturn(
        aResponse()
          .withBody(shouldReturnABodyOf)
          .withStatus(responseStatus)))
  }

  private val defaultStubClientDataResponseBody = stubClientDataResponseBody()
  def stubGetClientDataOk(): Unit = stub(get)(IntegrationTestHelpers.PathFor.defaultClientData, OK, defaultStubClientDataResponseBody)

  def stubClientDataResponseBody(clientId: models.ClientId = ClientId,
                                 csid: models.ClientSubscriptionId = TranslatedCsid,
                                 callbackUrl: Option[URL] = Some(ClientCallbackUrl)): String = {
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

  def validExternalPushRequest(callbackUrl: URL = ClientCallbackUrl): RequestPatternBuilder = postRequestedFor(urlMatching(PathFor.ExternalPush))
    .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
    .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
    .withHeader(NOTIFICATION_ID_HEADER_NAME, equalTo(NotificationId.toString))
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
}
