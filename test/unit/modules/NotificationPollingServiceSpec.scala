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

package unit.modules

import java.util.UUID.randomUUID

import akka.actor.ActorSystem
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.modules.NotificationPollingService
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.customs.notification.services.NotificationDispatcher
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec
import org.mockito.ArgumentCaptor

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class NotificationPollingServiceSpec extends UnitSpec with MockitoSugar {
  val clientNotificationRepoMock = mock[ClientNotificationRepo]
  val notificationDispatcherMock = mock[NotificationDispatcher]
  val configServiceMock = mock[ConfigService]

  val testActorSystem = ActorSystem("NotificationPollingService")

  "NotificationPollingService" should {

    "should poll the database and pass the ids onto CSID Despatcher" in {

      val csIds = Set(ClientSubscriptionId(randomUUID), ClientSubscriptionId(randomUUID), ClientSubscriptionId(randomUUID))
      val csIdsMinus1 = csIds.take(2)

      when(clientNotificationRepoMock.fetchDistinctNotificationCSIDsWhichAreNotLocked()).thenReturn(Future.successful(csIds)).thenReturn(csIdsMinus1)

      val argumentCapture = ArgumentCaptor.forClass(classOf[Set[ClientSubscriptionId]])

      new NotificationPollingService(configServiceMock,
          testActorSystem,
          clientNotificationRepoMock,
          notificationDispatcherMock)
      Thread.sleep(6000)
      verify(notificationDispatcherMock, times(2)).process(argumentCapture.capture())
      argumentCapture.getAllValues should contain theSameElementsAs List(csIds, csIdsMinus1)
    }
  }
}
