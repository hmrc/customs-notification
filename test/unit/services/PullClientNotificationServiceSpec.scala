/*
 * Copyright 2019 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.services.PullClientNotificationService
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future

class PullClientNotificationServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach {

  private val mockPullConnector = mock[NotificationQueueConnector]
  private val mockLogger = mock[CdsLogger]
  private val service = new PullClientNotificationService(mockPullConnector, mockLogger)
  private val someNotification = clientNotification()
  private val runtimeException = new RuntimeException("something went wrong")

  override protected def beforeEach(): Unit = {
    reset(mockPullConnector, mockLogger)

    when(mockPullConnector.enqueue(any[ClientNotification])).thenReturn(Future.successful(mock[HttpResponse]))
  }

  "Pull service" should {

    "return sync True when the request to Pull Service is successful" in {
      service.send(someNotification) should be(true)

      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientSubscriptionId=ffff01f9-ec3b-4ede-b263-61b626dde232]Notification has been passed on to PULL service")
        .verify()
    }

    "return sync False when the request to Pull Service is not successful" in {
      when(mockPullConnector.enqueue(any[ClientNotification]))
        .thenReturn(Future.failed(runtimeException))

      service.send(someNotification) should be(false)

      PassByNameVerifier(mockLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientSubscriptionId=ffff01f9-ec3b-4ede-b263-61b626dde232]Failed to pass the notification to PULL service")
        .withByNameParamMatcher(any[RuntimeException])
        .verify()
    }

    "return async True when the request to Pull Service is successful" in {
      service.send(someNotification) should be(true)

    }

    "return async False when the request to Pull Service is not successful" in {
      when(mockPullConnector.enqueue(any[ClientNotification]))
        .thenReturn(Future.failed(runtimeException))

      service.send(someNotification) should be(false)

      PassByNameVerifier(mockLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientSubscriptionId=ffff01f9-ec3b-4ede-b263-61b626dde232]Failed to pass the notification to PULL service")
        .withByNameParamMatcher(any[RuntimeException])
        .verify()
    }

  }
}