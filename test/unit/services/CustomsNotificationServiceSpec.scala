/*
 * Copyright 2024 HM Revenue & Customs
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

import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.{any, refEq, eq => ameq}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{InProgress, PermanentlyFailed, Succeeded}
import uk.gov.hmrc.mongo.workitem.ResultStatus
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._
import util.UnitSpec

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.xml.Elem

class CustomsNotificationServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  private val timeout = Mockito.timeout(5000).times(1)
  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val eventuallyRightOfPush = Future.successful(Right(Push))
  private val exception = new Exception("Boom")
  private val eventuallyLeftOfPush = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyLeftOfPush500 = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.INTERNAL_SERVER_ERROR, exception))))
  private val eventuallyLeftOfPull = Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyRightOfPull = Future.successful(Right(Pull))
  private val eventuallyUnit = Future.successful(())

  private val ValidXML: Elem = <foo1></foo1>
  private val mockPushOrPullService = mock[PushOrPullService]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockNotificationWorkItemRepo = mock[NotificationWorkItemRepo]
  private lazy val mockMetricsService = mock[CustomsNotificationMetricsService]
  private lazy val mockDateTimeService = mock[DateTimeService]
  private lazy val mockAuditingService = mock[AuditingService]
  private lazy val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
  private val currentTime = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
  private val currentTimePlus2Hour = currentTime.plusMinutes(120)

  private val notificationConfig = NotificationConfig(Seq[String](""),
    60,
    false,
    FiniteDuration(30, SECONDS),
    FiniteDuration(30, SECONDS),
    FiniteDuration(30, SECONDS),
    1,
    120)

  private val service = new CustomsNotificationService(
    mockNotificationLogger,
    mockNotificationWorkItemRepo,
    mockPushOrPullService,
    mockMetricsService,
    mockCustomsNotificationConfig,
    mockDateTimeService,
    mockAuditingService
  )

  override protected def beforeEach(): Unit = {
    reset[Any](mockNotificationWorkItemRepo, mockNotificationLogger, mockPushOrPullService, mockMetricsService, mockCustomsNotificationConfig, mockAuditingService)
    when(mockCustomsNotificationConfig.notificationConfig).thenReturn(notificationConfig)
    when(mockDateTimeService.zonedDateTimeUtc).thenReturn(currentTime)
  }

  "CustomsNotificationService.handleNotification" should {
    "for push" should {
      "send notification with metrics time to third party when url present and call metrics service" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(eventuallyRightOfPush)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))
        when(mockMetricsService.notificationMetric(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())
        verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())

        infoLogVerifier("Push succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "return false and not push when it is unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe false
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        verifyNoInteractions(mockPushOrPullService)
        verifyNoInteractions(mockMetricsService)
      }

      "return true when repo saves but push fails with 404" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
        when(mockNotificationWorkItemRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(eventuallyLeftOfPush)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockNotificationWorkItemRepo, timeout).incrementFailureCount(WorkItem1.id)
        verify(mockNotificationWorkItemRepo, timeout).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)
        verify(mockAuditingService, timeout).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        errorLogVerifier("Push failed PushOrPullError(Push,HttpResultError(404,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a")
      }

      "return true when repo saves but push fails with 500" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setPermanentlyFailed(WorkItem1.id, Helpers.INTERNAL_SERVER_ERROR)).thenReturn(Future.successful(()))
        when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
        when(mockNotificationWorkItemRepo.setPermanentlyFailed(WorkItem1.id, Helpers.INTERNAL_SERVER_ERROR)).thenReturn(eventuallyUnit)
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(eventuallyLeftOfPush500)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id)
        verify(mockNotificationWorkItemRepo).setPermanentlyFailed(WorkItem1.id, Helpers.INTERNAL_SERVER_ERROR)
        verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        errorLogVerifier("Push failed PushOrPullError(Push,HttpResultError(500,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a")
      }

      "return true and not push when there are existing permanently failed notifications with 5xx errors" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(true))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(PermanentlyFailed))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        verify(mockNotificationWorkItemRepo).permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(PermanentlyFailed))
        verify(mockNotificationWorkItemRepo, times(0)).setCompletedStatus(any[ObjectId], any[ResultStatus])
        verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        verifyNoInteractions(mockPushOrPullService)
        infoLogVerifier("Existing permanently failed notifications found for client id: ClientId. Setting notification to permanently failed")
      }

      "return true BEFORE push has succeeded" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(
          Future {
            Right(Push)
          })

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true

        verify(mockNotificationWorkItemRepo).permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockNotificationWorkItemRepo, times(1)).setCompletedStatus(any[ObjectId], any[ResultStatus])
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())

      }
    }

    "for pull" should {
      "send notification to pull queue and call metrics service when url absent" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any())).thenReturn(eventuallyRightOfPull)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, Succeeded)
        verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any())
        verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        infoLogVerifier("Pull succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "fail when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe false
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        verifyNoInteractions(mockPushOrPullService)
        verifyNoInteractions(mockMetricsService)
      }

      "return true and call metrics service when repo saves but pull fails with 404" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
        when(mockNotificationWorkItemRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any())).thenReturn(eventuallyLeftOfPull)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
        verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id)
        verify(mockNotificationWorkItemRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)
        verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
        verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
        errorLogVerifier("Pull failed PushOrPullError(Pull,HttpResultError(404,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a")
      }
    }
  }

  private def infoLogVerifier(logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, "info")
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }

  private def errorLogVerifier(logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, "warn")
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }
}
