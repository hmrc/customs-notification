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

package unit.services;

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.PullClientNotificationService
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future;

class PullClientNotificationServiceSpec extends UnitSpec with MockitoSugar {

  private val pullConnector = mock[NotificationQueueConnector]
  private val mockLogger = mock[NotificationLogger]
  private val service = new PullClientNotificationService(pullConnector, mockLogger)
  private val mockNotification = mock[ClientNotification]

  "Pull service" should {

    "wait for result and return True when the request to Pull Service is successful" in {
      when(pullConnector.enqueue(any[ClientNotification]))
        .thenReturn(Future.successful(mock[HttpResponse]))

      await(service.send(mockNotification)) should be(true)


    }

    "wait for result and return False when the request to Pull Service is not successful" in {
      when(pullConnector.enqueue(any[ClientNotification]))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))

      await(service.send(mockNotification)) should be(false)


    }

  }

}