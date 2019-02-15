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

import akka.actor.ActorSystem
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.UnblockPollingConfig
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services.UnblockPollingService
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class UnblockPollingServiceSpec extends UnitSpec
  with MockitoSugar
  with BeforeAndAfterEach {
  private val notificationWorkItemRepoMock = mock[NotificationWorkItemRepo]
  private val configServiceMock = mock[ConfigService]
  private val mockCdsLogger= mock[CdsLogger]
  val testActorSystem = ActorSystem("UnblockPollingService")

  override def beforeEach(): Unit = {
    reset(notificationWorkItemRepoMock, configServiceMock)
  }

  "UnblockPollingService" should {

    "should poll the database and unblock any blocked notifications" in {

      val mockUnblockPollingConfig = mock[UnblockPollingConfig]

      when(notificationWorkItemRepoMock.unblock()).thenReturn(Future.successful(2))
      when(configServiceMock.unblockPollingConfig).thenReturn(mockUnblockPollingConfig)
      when(mockUnblockPollingConfig.pollingEnabled) thenReturn true
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(50.milliseconds)

      new UnblockPollingService(configServiceMock,
          testActorSystem,
          notificationWorkItemRepoMock,
          mockCdsLogger)

      Thread.sleep(100)
      verify(notificationWorkItemRepoMock, times(2)).unblock()
    }

    "should not poll the database when disabled" in {
      val mockUnblockPollingConfig = mock[UnblockPollingConfig]

      when(configServiceMock.unblockPollingConfig).thenReturn(mockUnblockPollingConfig)
      when(mockUnblockPollingConfig.pollingEnabled).thenReturn(false)
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(5.seconds)

      new UnblockPollingService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockCdsLogger)

      verify(notificationWorkItemRepoMock, never).unblock()
    }
  }
}
