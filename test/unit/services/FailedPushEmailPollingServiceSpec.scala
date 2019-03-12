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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.customs.notification.connectors.EmailConnector
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, Email, PullExcludeConfig, SendEmailRequest}
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.customs.notification.services.FailedPushEmailPollingService
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubCdsLogger

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class FailedPushEmailPollingServiceSpec extends UnitSpec with MockitoSugar with Eventually {

  trait Setup {

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockEmailConnector = mock[EmailConnector]
    val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
    val stubCdsLogger = StubCdsLogger()
    val mockPullExcludeConfig = mock[PullExcludeConfig]
    val testActorSystem = ActorSystem("FailedPushEmailPollingService")
    val mockActorSystem = mock[ActorSystem]

    val year = 2017
    val monthOfYear = 7
    val dayOfMonth = 4
    val hourOfDay = 13
    val minuteOfHour = 45
    val timeReceived = new DateTime(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, DateTimeZone.UTC)
    val latestReceived = timeReceived.plus(1)
    val clientId1 = "clientId1"

    val sendEmailRequest = SendEmailRequest(List(Email("some-email@address.com")),
      "customs_push_notifications_warning", Map("timestamp" -> "some-timestamp"), force = false)
    when(mockCustomsNotificationConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
    when(mockPullExcludeConfig.emailAddress).thenReturn("some-email@address.com")
    when(mockPullExcludeConfig.pollingInterval).thenReturn(1 minute)
    when(mockPullExcludeConfig.pollingDelay).thenReturn(0 seconds)
  }

  "FailedPushEmailPollingService" should {
    "send an email" in new Setup {
      when(mockClientNotificationRepo.failedPushNotificationsExist()).thenReturn(Future.successful(true))
      when(mockPullExcludeConfig.pullExcludeEnabled).thenReturn(true)
      val warningEmailService = new FailedPushEmailPollingService(mockClientNotificationRepo, mockEmailConnector, testActorSystem, mockCustomsNotificationConfig, stubCdsLogger)
      val emailRequestCaptor: ArgumentCaptor[SendEmailRequest] = ArgumentCaptor.forClass(classOf[SendEmailRequest])

      //TODO investigate a way of not requiring sleep
      Thread.sleep(1000)
      eventually(verify(mockEmailConnector).send(emailRequestCaptor.capture()))

      val request = emailRequestCaptor.getValue
      request.to.head.value shouldBe "some-email@address.com"
      request.templateId shouldBe "customs_push_notifications_warning"
      request.parameters.head._1 shouldBe "timestamp"
    }

    "not send an email when no notifications failed to push" in new Setup {
      when(mockPullExcludeConfig.pullExcludeEnabled).thenReturn(true)
      when(mockClientNotificationRepo.failedPushNotificationsExist()).thenReturn(Future.successful(false))
      val warningEmailService = new FailedPushEmailPollingService(mockClientNotificationRepo, mockEmailConnector, testActorSystem, mockCustomsNotificationConfig, stubCdsLogger)

      //TODO investigate a way of not requiring sleep
      Thread.sleep(1000)
      verify(mockEmailConnector, never()).send(any[SendEmailRequest]())
    }

    "not run when poller is disabled" in new Setup {
      when(mockPullExcludeConfig.pullExcludeEnabled).thenReturn(false)
      val warningEmailService = new FailedPushEmailPollingService(mockClientNotificationRepo, mockEmailConnector, testActorSystem, mockCustomsNotificationConfig, stubCdsLogger)

      verify(mockActorSystem, never()).scheduler
    }
  }
}
