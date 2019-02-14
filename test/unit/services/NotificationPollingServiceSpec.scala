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

import java.util.UUID.randomUUID

import akka.actor.ActorSystem
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, PushNotificationConfig}
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.customs.notification.services.{NotificationDispatcher, NotificationPollingService}
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class NotificationPollingServiceSpec extends UnitSpec with MockitoSugar {
  private val clientNotificationRepoMock = mock[ClientNotificationRepo]
  private val notificationDispatcherMock = mock[NotificationDispatcher]
  private val configServiceMock = mock[ConfigService]
  private val mockCdsLogger= mock[CdsLogger]
  private val SIX_THOUSAND = 6000
  val testActorSystem = ActorSystem("NotificationPollingService")

  "NotificationPollingService" should {

    "should poll the database and pass the ids onto CSID Dispatcher" in {

      val csIds = Set(ClientSubscriptionId(randomUUID), ClientSubscriptionId(randomUUID), ClientSubscriptionId(randomUUID))
      val csIdsMinus1 = csIds.take(2)
      val mockPushNotificationConfig = mock[PushNotificationConfig]

      when(clientNotificationRepoMock.fetchDistinctNotificationCSIDsWhichAreNotLocked()).thenReturn(Future.successful(csIds)).thenReturn(csIdsMinus1)


      when(configServiceMock.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.pollingEnabled) thenReturn true
      when(mockPushNotificationConfig.pollingDelay).thenReturn(5.seconds)

      val argumentCapture = ArgumentCaptor.forClass(classOf[Set[ClientSubscriptionId]])

      new NotificationPollingService(configServiceMock,
          testActorSystem,
          clientNotificationRepoMock,
          notificationDispatcherMock,
          mockCdsLogger)

      Thread.sleep(SIX_THOUSAND)
      verify(notificationDispatcherMock, times(2)).process(argumentCapture.capture())
      argumentCapture.getAllValues should contain theSameElementsAs List(csIds, csIdsMinus1)
    }

    "when polling is disabled a log should be printed to console" in {
      val mockPushNotificationConfig = mock[PushNotificationConfig]

      when(configServiceMock.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.pollingEnabled) thenReturn false

      new NotificationPollingService(configServiceMock,
        testActorSystem,
        clientNotificationRepoMock,
        notificationDispatcherMock,
        mockCdsLogger)

      PassByNameVerifier(mockCdsLogger, "info") withByNameParam[String] "push notification polling disabled" verify
    }
  }
}
