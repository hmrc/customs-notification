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

import org.mockito.ArgumentMatchers.{any, refEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Span}
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.workitem.{Failed, PermanentlyFailed, Succeeded}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future
import scala.xml.Elem

class CustomsNotificationRetryServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

  private val badgeIdValue = "test-badge-id"

  val ValidXML: Elem = <foo1></foo1>
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockNotificationWorkItemRepo = mock[NotificationWorkItemRepo]
  private val mockPushService = mock[PushClientNotificationRetryService]
  private val mockPullService = mock[PullClientNotificationRetryService]

   private val service = new CustomsNotificationRetryService(
    mockNotificationLogger,
    mockNotificationWorkItemRepo,
    mockPushService,
    mockPullService
  )

  private implicit val implicitRequestMetaData = requestMetaData

  override protected def beforeEach() {
    reset(mockNotificationWorkItemRepo, mockNotificationLogger, mockPushService, mockPullService)
    when(mockPushService.send(ApiSubscriptionFieldsOneForPush, NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(true))
  }

  "CustomsNotificationRetryService" should {
    "for push" should {
      "send notification to third party when url present" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        eventually(verify(mockPushService).send(ApiSubscriptionFieldsOneForPush, NotificationWorkItemWithMetricsTime1))
        eventually(verifyZeroInteractions(mockPullService))
        logVerifier("info", "push succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "fail when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe false
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        eventually(verifyZeroInteractions(mockPushService))
        eventually(verifyZeroInteractions(mockPullService))
      }

      "return true when repo saves but push fails" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockPushService.send(ApiSubscriptionFieldsOneForPush, NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(false))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, PermanentlyFailed))
        eventually(verifyZeroInteractions(mockPullService))
        logVerifier("error", "push permanently-failed for workItemId 5c46f7d70100000100ef835a")
      }

      "return true when repo saves but push fails with exception" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
        when(mockPushService.send(ApiSubscriptionFieldsOneForPush, NotificationWorkItemWithMetricsTime1)).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verifyZeroInteractions(mockPullService))
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        logVerifier("error", "processing failed for notification work item id: 5c46f7d70100000100ef835a due to: Emulated service failure.")
      }
    }

    "for pull" should {
      "send notification to pull queue when url absent" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))
        when(mockPullService.send(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(true))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, Succeeded))
        eventually(verify(mockPullService).send(NotificationWorkItemWithMetricsTime1))
        eventually(verifyZeroInteractions(mockPushService))
        logVerifier("info", "pull succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "fail when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe false
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        eventually(verifyZeroInteractions(mockPushService))
        eventually(verifyZeroInteractions(mockPullService))
      }

      "return true when repo saves but pull fails" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(Future.successful(()))
        when(mockPullService.send(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(false))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, Failed))
        eventually(verifyZeroInteractions(mockPushService))
        logVerifier("error", "pull failed for workItemId 5c46f7d70100000100ef835a")
      }

      "return true when repo saves but pull fails with exception" in {
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
        when(mockPullService.send(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verifyZeroInteractions(mockPushService))
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
        logVerifier("error", "processing failed for notification work item id: 5c46f7d70100000100ef835a due to: Emulated service failure.")
      }

    }
  }

  private def logVerifier(logLevel: String, logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, logLevel)
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }

}
