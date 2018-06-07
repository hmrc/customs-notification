/*
 * Copyright 2018 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import play.api.Configuration
import play.api.http.HeaderNames._
import play.api.http.MimeTypes
import play.api.libs.json.{JsValue, Json, Writes}
import uk.gov.hmrc.customs.api.common.config.{ServiceConfig, ServiceConfigProvider}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.GoogleAnalyticsSenderConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.{ExecutionContext, Future}

class GoogleAnalyticsSenderConnectorSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach {

  private val mockHttpClient = mock[HttpClient]
  private val mockCdsLogger = mock[CdsLogger]
  private val notificationLogger = mock[NotificationLogger]
  private val mockServiceConfigProvider = mock[ServiceConfigProvider]

  private val url = "the-url"
  private implicit val gaTrackingId: String = "UA-43414424-2"
  private implicit val gaClientId: String = "555"
  private val gaEventValue = "10"
  private val eventName: String = "event-name"
  private val eventLabel: String = "event-label"

  private val validConfigMap = Map(
    "googleAnalytics.trackingId" -> gaTrackingId,
    "googleAnalytics.clientId" -> gaClientId,
    "googleAnalytics.eventValue" -> gaEventValue
  )
  private lazy val configuration = Configuration.from(validConfigMap)


  private implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = Seq(
    X_CONVERSATION_ID_HEADER_NAME -> validConversationId,
    X_CDS_CLIENT_ID_HEADER_NAME -> validFieldsId))

  private lazy val connector = new GoogleAnalyticsSenderConnector(
    mockHttpClient,
    notificationLogger,
    mockServiceConfigProvider,
    configuration
  )

  override def beforeEach(): Unit = {
    reset(mockServiceConfigProvider, mockCdsLogger, mockHttpClient)
    when(mockServiceConfigProvider.getConfig("google-analytics-sender")).thenReturn(ServiceConfig(url, None, "default"))
    when(mockHttpClient.POST(any[String](), any[JsValue](), any[Seq[(String, String)]]())(any[Writes[JsValue]](), any[HttpReads[HttpResponse]](), meq(hc), any[ExecutionContext]()))
      .thenReturn(Future.successful(mock[HttpResponse]))
  }

  private val emulatedHttpVerbsException = new RuntimeException("Something has gone wrong....")

  "GoogleAnalyticsSenderConnector" should {

    "POST valid payload" in {
      await(connector.send(eventName, eventLabel))

      val requestCaptor: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])

      verify(mockHttpClient).POST(ArgumentMatchers.eq(url), requestCaptor.capture(), any[Seq[(String, String)]]())(
        any(), any(), any(), any())


      requestCaptor.getValue shouldBe Json.parse(
        s""" {
            |"payload" :
            |  "v=1&t=event&tid=$gaTrackingId&cid=$gaClientId&ec=CDS&ea=$eventName&el=$eventLabel&ev=$gaEventValue"
            |  } """.stripMargin)

    }

    "POST valid headers" in {
      val expectedHeaders = Seq(
        (ACCEPT, MimeTypes.JSON),
        (CONTENT_TYPE, MimeTypes.JSON))

      await(connector.send(eventName, eventLabel))

      verify(mockHttpClient).POST(ArgumentMatchers.eq(url), any(), meq(expectedHeaders))(any(), any(), any(), any())
    }

    "not propagate exception, log it correctly" in {
      when(mockHttpClient.POST(any(), any(), any())(any[Writes[JsValue]](), any[HttpReads[HttpResponse]](), meq(hc), any[ExecutionContext]()))
        .thenReturn(Future.failed(emulatedHttpVerbsException))

      await(connector.send(eventName, eventLabel))

      PassByNameVerifier(notificationLogger, "error")
        .withByNameParam(s"Call to GoogleAnalytics sender service failed. POST url= $url, eventName= $eventName, eventLabel= $eventLabel, reason= ${emulatedHttpVerbsException.getMessage}")
        .withParamMatcher(meq(hc))
        .verify()
    }

    "fail when GA Tracking Id is not configured" in {

      val e = intercept[RuntimeException] {
        new GoogleAnalyticsSenderConnector(
          mockHttpClient,
          notificationLogger,
          mockServiceConfigProvider,
          Configuration.from(validConfigMap - "googleAnalytics.trackingId"))
      }

      e.getMessage shouldBe "Google Analytics Tracking Id is not configured"
    }

    "fail when GA Client Id is not configured" in {

      val e = intercept[RuntimeException] {
        new GoogleAnalyticsSenderConnector(
          mockHttpClient,
          notificationLogger,
          mockServiceConfigProvider,
          Configuration.from(validConfigMap - "googleAnalytics.clientId"))
      }

      e.getMessage shouldBe "Google Analytics Client Id is not configured"
    }

    "fail when GA Event value is not configured" in {

      val e = intercept[RuntimeException] {
        new GoogleAnalyticsSenderConnector(
          mockHttpClient,
          notificationLogger,
          mockServiceConfigProvider,
          Configuration.from(validConfigMap - "googleAnalytics.eventValue"))
      }

      e.getMessage shouldBe "Google Analytics Event Value is not configured"
    }

  }
}
