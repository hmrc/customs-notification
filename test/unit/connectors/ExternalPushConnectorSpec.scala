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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Writes
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.config.{ServiceConfig, ServiceConfigProvider}
import uk.gov.hmrc.customs.notification.connectors.ExternalPushConnector
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.PushNotificationRequestBody
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpReads, HttpResponse}
import util.TestData.{externalPushNotificationRequest, requestMetaData}
import util.UnitSpec

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

class ExternalPushConnectorSpec extends UnitSpec with MockitoSugar {

  private val mockHttpClient = mock[HttpClient]
  val mockLogger = mock[NotificationLogger]
  private val serviceConfigProvider = mock[ServiceConfigProvider]
  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private implicit val rm: RequestMetaData = requestMetaData

  private val connector = new ExternalPushConnector(
    mockHttpClient,
    mockLogger,
    serviceConfigProvider)

  private val url = "the-url"

  private val emulatedHttpVerbsException = new RuntimeException("FooBar")

  "ExternalPushConnector" should {
    when(serviceConfigProvider.getConfig("public-notification")).thenReturn(ServiceConfig(url, None, "default"))

    "POST valid payload" in {
      when(mockHttpClient.POST(any[String](), any[NodeSeq](), any[Seq[(String,String)]]())(
        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any(), any()))
        .thenReturn(Future.successful(mock[HttpResponse]))

      await(connector.send(externalPushNotificationRequest))

      val requestBody = ArgumentCaptor.forClass(classOf[PushNotificationRequestBody])
      verify(mockHttpClient).POST(ArgumentMatchers.eq(url), requestBody.capture(), any[Seq[(String,String)]]())(
        any[Writes[PushNotificationRequestBody]](), any[HttpReads[HttpResponse]](), any(), any())
      val body = requestBody.getValue.asInstanceOf[PushNotificationRequestBody]
      body shouldEqual externalPushNotificationRequest.body
    }

    "propagate exception in HTTP VERBS post" in {
      when(mockHttpClient.POST(any[String](), any[NodeSeq](), any[Seq[(String,String)]]())(
        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any(), any()))
        .thenThrow(emulatedHttpVerbsException)

      val caught = intercept[RuntimeException] {
        await(connector.send(externalPushNotificationRequest))
      }

      caught shouldBe emulatedHttpVerbsException
    }
  }
}
