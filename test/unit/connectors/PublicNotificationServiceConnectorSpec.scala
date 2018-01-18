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
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Writes
import uk.gov.hmrc.customs.api.common.config.{ServiceConfig, ServiceConfigProvider}
import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequestBody
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.WSPostImpl
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.config.inject.AppName
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData.publicNotificationRequest

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

class PublicNotificationServiceConnectorSpec extends UnitSpec with MockitoSugar {

  private val mockHttpPost = mock[WSPostImpl]
  private val mockAppName = mock[AppName]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val serviceConfigProvider = mock[ServiceConfigProvider]

  private val connector = new PublicNotificationServiceConnector(
    mockHttpPost,
    mockAppName,
    mockNotificationLogger,
    serviceConfigProvider
  )

  private val url = "the-url"

  private val emulatedHttpVerbsException = new RuntimeException("FooBar")

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "PublicNotificationServiceConnector" should {
    when(serviceConfigProvider.getConfig("public-notification")).thenReturn(ServiceConfig(url, None, "default"))

    "POST valid payload" in {
      when(mockHttpPost.POST(any[String](), any[NodeSeq](), any[Seq[(String,String)]]())(
        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext]()))
        .thenReturn(Future.successful(mock[HttpResponse]))

      await(connector.send(publicNotificationRequest))

      val requestBody = ArgumentCaptor.forClass(classOf[PublicNotificationRequestBody])
      verify(mockHttpPost).POST(ArgumentMatchers.eq(url), requestBody.capture(), any[Seq[(String,String)]]())(
        any[Writes[PublicNotificationRequestBody]](), any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext]())
      val body = requestBody.getValue.asInstanceOf[PublicNotificationRequestBody]
      body shouldEqual publicNotificationRequest.body
    }

    "propagate exception in HTTP VERBS post" in {
      when(mockHttpPost.POST(any[String](), any[NodeSeq](), any[Seq[(String,String)]]())(
        any[Writes[NodeSeq]](), any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext]()))
        .thenThrow(emulatedHttpVerbsException)

      val caught = intercept[RuntimeException] {
        await(connector.send(publicNotificationRequest))
      }

      caught shouldBe emulatedHttpVerbsException
    }
  }
}
