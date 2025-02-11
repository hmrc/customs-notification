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
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, postRequestedFor, urlEqualTo}
import com.github.tomakehurst.wiremock.client.{MappingBuilder, WireMock}
import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.http.{HttpHeader, Request}
import com.github.tomakehurst.wiremock.matching.{MatchResult, RequestMatcherExtension, RequestPatternBuilder}
import play.api.http.MimeTypes
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import play.api.libs.json.Json
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.config.{ServiceConfig, ServiceConfigProvider}
import uk.gov.hmrc.customs.notification.connectors.ExternalPushConnector
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpResponse}
import unit.logging.StubNotificationLogger
import util.TestData.{externalPushNotificationRequest, requestMetaData}
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import util.UnitSpec

import java.util
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._;

class ExternalPushConnectorSpec
  extends UnitSpec
    with BeforeAndAfterEach
//    with MockitoSugar
    with Eventually
    with ScalaFutures
    with HttpClientV2Support
    with IntegrationPatience
    with WireMockSupport{

  //  private val mockHttpClient = mock[HttpClientV2]
//  val mockLogger = mock[NotificationLogger]
  val stubLogger = new StubNotificationLogger()
  private val serviceConfigProvider = mock(classOf[ServiceConfigProvider])
  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private implicit val rm: RequestMetaData = requestMetaData

  private val connector = new ExternalPushConnector(
    httpClientV2,
    stubLogger,
    serviceConfigProvider)

//  private val url = "https://customs-notification-gateway.public.mdtp"
  private val status = 200
  private val successfulResponse = HttpResponse(status, "")
  private val emulatedHttpVerbsException = new RuntimeException("FooBar")
//  private val mockRequestBuilder = mock[RequestBuilder]
//  override protected def beforeEach(): Unit = {
//    when(mockRequestBuilder.withBody(any())(any(), any(), any())).thenReturn(mockRequestBuilder)
//    when(mockRequestBuilder.execute[HttpResponse]).thenReturn(Future.successful(successfulResponse))
//  }

  implicit class WireMockOps(mappingBuilder: MappingBuilder) {
    def withHeaders(headers: Seq[(String, String)]): MappingBuilder = {
      headers
        .foldLeft(mappingBuilder)((acc, header) => acc.withHeader(header._1, equalTo(header._2)))
        .andMatching(new RequestMatcherExtension {
          override def `match`(request: Request, parameters: Parameters) = {
            val requestHeaders: Set[(String, String)] = request.getHeaders.all().asScala.map(httpHeader => (httpHeader.key(), httpHeader.values().asScala.toString())).toSet
            println(requestHeaders)
            println(headers.toSet)
            println(requestHeaders == headers.toSet)
            MatchResult.of(requestHeaders == headers.toSet)
          }
        })
    }
  }

  implicit class WireMockVerifyOps(requestPatternBuilder: RequestPatternBuilder) {
    def withHeaders(headers: Seq[(String, String)]): RequestPatternBuilder =
      headers.foldLeft(requestPatternBuilder)((acc, header) => acc.withHeader(header._1, equalTo(header._2)))
  }


  "ExternalPushConnector" should {
    when(serviceConfigProvider.getConfig("public-notification")).thenReturn(ServiceConfig(wireMockUrl, None, "default"))

    "POST valid payload" in {
      //      when(httpClientV2.POST(any[String](), any[NodeSeq](), any[Seq[(String,String)]]())(
      //        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any(), any()))
      //        .thenReturn(Future.successful(mock[HttpResponse]))

      val body = Json.toJson(externalPushNotificationRequest.body).toString()
      val OHeaders= Seq(
        (ACCEPT, MimeTypes.JSON),
        (CONTENT_TYPE, MimeTypes.JSON))
      val headerNames = HeaderNames.explicitlyIncludedHeaders
      val headers = hc.headers(headerNames) ++ hc.extraHeaders ++ OHeaders

      println(s"headers:::: ${headers}")

      wireMockServer.stubFor(
        WireMock
          .post(urlEqualTo("/"))
          .withRequestBody(equalTo(body))
          .withHeaders(headers)
          .willReturn(aResponse()
            .withBody("Massive body")
            .withStatus(status)
          )
      )

      connector.send(externalPushNotificationRequest).futureValue

      wireMockServer
        .verify(1,
          postRequestedFor(urlEqualTo("/"))
            .withRequestBody(equalTo(body))
            .withHeaders(headers)
        )

//      externalWireMockServer.verify(postRequestedFor(UrlPattern.fromOneOf(externalWireMockUrl, null, null, null, null)))
//      wireMockServer.verify(postRequestedFor(anyUrl()))

//
//      val requestBody = ArgumentCaptor.forClass(classOf[PushNotificationRequestBody])
//      //      verify(httpClientV2).POST(ArgumentMatchers.eq(url), requestBody.capture(), any[Seq[(String,String)]]())(
//      //        any[Writes[PushNotificationRequestBody]](), any[HttpReads[HttpResponse]](), any(), any())
//
//
//      val body = requestBody.getValue.asInstanceOf[PushNotificationRequestBody]
//      body shouldEqual externalPushNotificationRequest.body
    }

//    "propagate exception in HTTP VERBS post" in {
//      (pending)
//      //      when(httpClientV2.POST(any[String](), any[NodeSeq](), any[Seq[(String,String)]]())(
//      //        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any(), any()))
//      //        .thenThrow(emulatedHttpVerbsException)
//
//      val caught = intercept[RuntimeException] {
//        await(connector.send(externalPushNotificationRequest))
//      }
//
//      caught shouldBe emulatedHttpVerbsException
//    }
  }
}
