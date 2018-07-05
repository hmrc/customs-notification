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

import org.mockito.ArgumentMatchers.{eq => meq}
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.PushClientNotificationService
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.TestData.emulatedServiceFailure

import scala.concurrent.Future

class PushClientNotificationServiceSpec extends UnitSpec with MockitoSugar with Eventually {

  private val mockPublicNotificationServiceConnector = mock[PublicNotificationServiceConnector]
  private val mockNotificationLogger = mock[NotificationLogger]

  private val pushService = new PushClientNotificationService(mockPublicNotificationServiceConnector, mockNotificationLogger)

  "PushClientNotificationService" should {
    "return true when push is successful" in {
      when(mockPublicNotificationServiceConnector.send(pnrOne)).thenReturn(Future.successful(()))

      val result = await(pushService.send(DeclarantCallbackDataOne, ClientNotificationOne))
      eventually(verify(mockPublicNotificationServiceConnector).send(meq(pnrOne)))

      result shouldBe true
    }

    "return false when push fails" in {
      when(mockPublicNotificationServiceConnector.send(pnrOne)).thenReturn(Future.failed(emulatedServiceFailure))

      val result = await(pushService.send(DeclarantCallbackDataOne, ClientNotificationOne))

      result shouldBe false
    }

  }

}
