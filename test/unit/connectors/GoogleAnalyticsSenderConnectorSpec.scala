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

import org.mockito.ArgumentMatchers.any
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
import uk.gov.hmrc.customs.notification.connectors.GoogleAnalyticsSenderConnector
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.{ExecutionContext, Future}

class GoogleAnalyticsSenderConnectorSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach {

  private val mockHttpClient = mock[HttpClient]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockServiceConfigProvider = mock[ServiceConfigProvider]

  private val url = "the-url"
  private implicit val gaTrackingId: String = "UA-43414424-2"
  private implicit val gaClientId: String = "555"
  private val gaEventValue = "10"
  private val eventName: String = "event-name"
  private val eventLabel: String = "event-label"

  private lazy val configuration = Configuration.from(Map(
    "googleAnalytics.trackingId" -> gaTrackingId,
    "googleAnalytics.clientId" -> gaClientId,
    "googleAnalytics.eventValue" -> gaEventValue
  ))


  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private lazy val connector = new GoogleAnalyticsSenderConnector(
    mockHttpClient,
    mockNotificationLogger,
    mockServiceConfigProvider,
    configuration
  )

  override def beforeEach(): Unit = {
    reset(mockServiceConfigProvider, mockNotificationLogger, mockHttpClient)
    when(mockServiceConfigProvider.getConfig("google-analytics-sender")).thenReturn(ServiceConfig(url, None, "default"))
    when(mockHttpClient.POST(any[String](), any[JsValue](), any[Seq[(String, String)]]())(any[Writes[JsValue]](), any[HttpReads[HttpResponse]](), meq(hc), any[ExecutionContext]()))
      .thenReturn(Future.successful(mock[HttpResponse]))
  }

  private val emulatedHttpVerbsException = new RuntimeException("FooBar")

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

    "not propogate exception, log it correctly" in {
      when(mockHttpClient.POST(any(), any(), any())(any[Writes[JsValue]](), any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext]())).thenReturn(Future.failed(emulatedHttpVerbsException))

      await(connector.send(eventName, eventLabel))

      verify(mockNotificationLogger).error(s"Call to GoogleAnalytics sender service failed. POST url= $url")(hc)
    }


    //    "propagate exception in HTTP VERBS post" in {
    //      when(mockHttpClient.POST(any[String](), any[NodeSeq](), any[Seq[(String, String)]]())(
    //        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext]()))
    //        .thenThrow(emulatedHttpVerbsException)
    //
    //      val caught = intercept[RuntimeException] {
    //        await(connector.send(publicNotificationRequest))
    //      }
    //
    //      caught shouldBe emulatedHttpVerbsException
    //    }
  }
}
