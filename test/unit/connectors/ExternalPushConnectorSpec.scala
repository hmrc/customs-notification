/*
 * Copyright 2024 HM Revenue & Customs
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

package unit.connectors

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, post, postRequestedFor, urlEqualTo}
import com.github.tomakehurst.wiremock.http.Fault
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.MimeTypes
import play.api.http.Status.NO_CONTENT
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.inject.bind
import play.api.test.Helpers.{ACCEPT, CONTENT_TYPE}
import uk.gov.hmrc.customs.notification.config.{ServiceConfig, ServiceConfigProvider}
import uk.gov.hmrc.customs.notification.connectors.ExternalPushConnector
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.NonHttpError
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.bootstrap.http.{DefaultHttpAuditing, HttpClientV2Provider}
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData.{externalPushNotificationRequest, requestMetaData}
import util.UnitSpec

class ExternalPushConnectorSpec extends UnitSpec with WireMockSupport with GuiceOneAppPerSuite with MockitoSugar {

  private val serviceConfigProvider = mock[ServiceConfigProvider]
  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private implicit val rm: RequestMetaData = requestMetaData

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(
      "microservice.services.public-notification.host" -> Host,
      "microservice.services.public-notification.port" -> Port,
    ).overrides(
      bind[HttpAuditing].to[DefaultHttpAuditing],
      bind[HttpClientV2].toProvider[HttpClientV2Provider]
    ).build()

  private val connector: ExternalPushConnector = app.injector.instanceOf[ExternalPushConnector]

  private val url = "the-url"

  "ExternalPushConnector" should {
    when(serviceConfigProvider.getConfig("public-notification")).thenReturn(ServiceConfig(url, None, "default"))

    "POST valid payload" in {
      wireMockServer.stubFor(post(urlEqualTo("/notify-customs-declarant"))
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
        .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
        .withRequestBody(equalTo(Json.stringify(Json.toJson(externalPushNotificationRequest.body))))
        .willReturn(
          aResponse()
            .withStatus(NO_CONTENT)))

      await(connector.send(externalPushNotificationRequest))

      wireMockServer.verify(1, postRequestedFor(urlEqualTo("/notify-customs-declarant"))
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
        .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
        .withRequestBody(equalTo(Json.stringify(Json.toJson(externalPushNotificationRequest.body))))
      )
    }

    "propagate exception in HTTP VERBS post" in {
      wireMockServer.stubFor(
        post(urlEqualTo("/notify-customs-declarant"))
          .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
          .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
          .withRequestBody(equalTo(Json.stringify(Json.toJson(externalPushNotificationRequest.body))))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      val result = await(connector.send(externalPushNotificationRequest))

      result match {
        case Left(NonHttpError(e: java.net.SocketException)) =>
          e.getMessage should include ("Connection reset")
        case _ =>
          fail(s"Unexpected result: $result")
      }

      wireMockServer.verify(1, postRequestedFor(urlEqualTo("/notify-customs-declarant"))
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
        .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
        .withRequestBody(equalTo(Json.stringify(Json.toJson(externalPushNotificationRequest.body))))
      )
    }
  }
}
