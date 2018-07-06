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

package unit.services

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.{GoogleAnalyticsSenderConnector, PushNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.PushClientNotificationService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.TestData.emulatedServiceFailure

import scala.concurrent.Future

class PushClientNotificationServiceSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterEach {

  private val mockPushNotificationServiceConnector = mock[PushNotificationServiceConnector]
  private val mockGAConnector = mock[GoogleAnalyticsSenderConnector]
  private val mockNotificationLogger = mock[NotificationLogger]
  private implicit val hc = HeaderCarrier()

  private val pushService = new PushClientNotificationService(mockPushNotificationServiceConnector, mockGAConnector, mockNotificationLogger)

  override protected def beforeEach(): Unit = {
    reset(mockPushNotificationServiceConnector, mockGAConnector, mockNotificationLogger)

    when(mockGAConnector.send(any(), any())(meq(hc))).thenReturn(Future.successful(()))
  }


  "PushClientNotificationService" should {
    "return true when push is successful" in {
      when(mockPushNotificationServiceConnector.send(pnrOne)).thenReturn(Future.successful(()))
      when(mockGAConnector.send(any(), any())(meq(hc))).thenReturn(Future.successful(()))

      val result = await(pushService.send(DeclarantCallbackDataOne, ClientNotificationOne))

      result shouldBe true
      eventually(verify(mockPushNotificationServiceConnector).send(meq(pnrOne)))
      andGAEventHasBeenSentWith("notificationPushRequestSuccess", "[ConversationId=caca01f9-ec3b-4ede-b263-61b626dde231] A notification has been pushed successfully")
    }

    "return false when push fails" in {
      when(mockPushNotificationServiceConnector.send(pnrOne)).thenReturn(Future.failed(emulatedServiceFailure))
      when(mockGAConnector.send(any(), any())(meq(hc))).thenReturn(Future.successful(()))

      val result = await(pushService.send(DeclarantCallbackDataOne, ClientNotificationOne))
      result shouldBe false
      andGAEventHasBeenSentWith("notificationPushRequestFailed", "[ConversationId=caca01f9-ec3b-4ede-b263-61b626dde231] A notification Push request failed")
    }

  }

  private def andGAEventHasBeenSentWith(expectedEventName: String, expectedMessage: String) = {
    val eventNameCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
    val msgCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])

    verify(mockGAConnector, times(1)).send(eventNameCaptor.capture(), msgCaptor.capture())(any())

    eventNameCaptor.getValue should be(expectedEventName)
    msgCaptor.getValue should be(expectedMessage)
  }

}
