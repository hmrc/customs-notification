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
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.customs.notification.services.LogNotificationCountsPollingService
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData.emulatedServiceFailure

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class LogNotificationCountsPollingServiceSpec extends UnitSpec
  with MockitoSugar
  with Eventually {

  trait Setup {

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
    val mockLogger = mock[CdsLogger]
    val mockLogNotificationCountsPollingConfig = mock[LogNotificationCountsPollingConfig]
    val testActorSystem = ActorSystem("LogNotificationCountsPollingService")
    val mockActorSystem = mock[ActorSystem]

    val year = 2017
    val monthOfYear = 7
    val dayOfMonth = 4
    val hourOfDay = 13
    val minuteOfHour = 45
    val timeReceived = new DateTime(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, DateTimeZone.UTC)
    val latestReceived = timeReceived.plus(1)

    val counts = List(NotificationCount("csid1", 2, timeReceived), NotificationCount("csid2", 1, latestReceived))

    when(mockCustomsNotificationConfig.logNotificationCountsPollingConfig).thenReturn(mockLogNotificationCountsPollingConfig)
    when(mockLogNotificationCountsPollingConfig.pollingInterval).thenReturn(1 minute)
    when(mockLogNotificationCountsPollingConfig.pollingEnabled).thenReturn(true)
  }

  "LogNotificationCountsPollingService" should {
    "log counts" in new Setup {
      when(mockClientNotificationRepo.notificationCountByCsid()).thenReturn(Future.successful(counts))
      when(mockLogNotificationCountsPollingConfig.pollingEnabled).thenReturn(true)
      val service = new LogNotificationCountsPollingService(mockClientNotificationRepo, testActorSystem, mockCustomsNotificationConfig, mockLogger)

      Thread.sleep(1000)
      eventually(
        PassByNameVerifier(mockLogger, "info")
        .withByNameParam("current notification counts in descending order: \ncsid1 count: 2 latest: 2017-07-04T13:45:00.000Z\ncsid2 count: 1 latest: 2017-07-04T13:45:00.001Z")
        .verify()
      )
    }

    "log error when fails" in new Setup {
      when(mockClientNotificationRepo.notificationCountByCsid()).thenReturn(Future.failed(emulatedServiceFailure))
      when(mockLogNotificationCountsPollingConfig.pollingEnabled).thenReturn(true)
      val service = new LogNotificationCountsPollingService(mockClientNotificationRepo, testActorSystem, mockCustomsNotificationConfig, mockLogger)

      Thread.sleep(1000)
      eventually(
        PassByNameVerifier(mockLogger, "error")
        .withByNameParam("failed to get notification counts due to Emulated service failure.")
        .verify()
      )
    }

    "not run when poller is disabled" in new Setup {
      when(mockLogNotificationCountsPollingConfig.pollingEnabled).thenReturn(false)
      val service = new LogNotificationCountsPollingService(mockClientNotificationRepo, mockActorSystem, mockCustomsNotificationConfig, mockLogger)

      verify(mockActorSystem, never()).scheduler
    }
  }
}
