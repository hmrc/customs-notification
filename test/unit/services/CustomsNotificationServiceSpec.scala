/*
 * Copyright 2020 HM Revenue & Customs
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

import java.time.{ZoneId, ZonedDateTime}

import org.mockito.ArgumentMatchers.{any, refEq, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, HasId, HttpResultError, NotificationConfig}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.workitem.{InProgress, PermanentlyFailed, ResultStatus, Succeeded}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.xml.Elem

class CustomsNotificationServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))
  private implicit val ec = Helpers.stubControllerComponents().executionContext
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val eventuallyRightOfPush = Future.successful(Right(Push))
  private val exception = new Exception("Boom")
  private val eventuallyLeftOfPush = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyLeftOfPush500 = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.INTERNAL_SERVER_ERROR, exception))))
  private val eventuallyLeftOfPull = Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyLeftOfApiSubsFields = Future.successful(Left(PushOrPullError(GetApiSubscriptionFields, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyRightOfPull = Future.successful(Right(Pull))
  private val eventuallyUnit = Future.successful(())

  private val ValidXML: Elem = <foo1></foo1>
  private val mockPushOrPullService = mock[PushOrPullService]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockNotificationWorkItemRepo = mock[NotificationWorkItemRepo]
  private lazy val mockMetricsService = mock[CustomsNotificationMetricsService]
  private lazy val mockDateTimeService = mock[DateTimeService]
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
    mockDateTimeService
  )

  private implicit val implicitRequestMetaData = requestMetaData

  override protected def beforeEach() {
    reset(mockNotificationWorkItemRepo, mockNotificationLogger, mockPushOrPullService, mockMetricsService, mockCustomsNotificationConfig)
    when(mockCustomsNotificationConfig.notificationConfig).thenReturn(notificationConfig)
    when(mockDateTimeService.zonedDateTimeUtc).thenReturn(currentTime)
  }

  "CustomsNotificationService" should {
    "for push" should {
      "send notification with metrics time to third party when url present and call metrics service" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier])).thenReturn(eventuallyRightOfPush)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))
        when(mockMetricsService.notificationMetric(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier]))
        eventually(verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1))
        infoLogVerifier("Push succeeded for workItemId 5c46f7d70100000100ef835a")
      }
      
      "send notification to third party when url present and don't call metrics service when failure count is not zero" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem3))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier])).thenReturn(eventuallyRightOfPush)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))
        when(mockMetricsService.notificationMetric(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier]))
        eventually(verifyNoInteractions(mockMetricsService))
        infoLogVerifier("Push succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "returned HasSaved is false when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe false
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verifyNoInteractions(mockPushOrPullService))
      }

      "returned HasSaved is true when repo saves but push fails with 404" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
        when(mockNotificationWorkItemRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier])).thenReturn(eventuallyLeftOfPush)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, currentTimePlus2Hour))
        errorLogVerifier("Push error PushOrPullError(Push,HttpResultError(404,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a", exception)
      }

      "returned HasSaved is true when repo saves but push fails with 500" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(eventuallyUnit)
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier])).thenReturn(eventuallyLeftOfPush500)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, PermanentlyFailed))
        errorLogVerifier("Push error PushOrPullError(Push,HttpResultError(500,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a", exception)
      }

      "returned HasSaved is true when there are existing permanently failed notifications" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(true))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(PermanentlyFailed))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId))
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(PermanentlyFailed)))
        eventually(verify(mockNotificationWorkItemRepo, times(0)).setCompletedStatus(any[BSONObjectID], any[ResultStatus]))
        infoLogVerifier("Existing permanently failed notifications found for client id: ClientId. Setting notification to permanently failed")
      }

      "returned HasSaved is true BEFORE push has succeeded" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any[HeaderCarrier])).thenReturn(
          Future{
            Right(Push)
          })

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true

        eventually(verify(mockNotificationWorkItemRepo).permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId))
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo, times(1)).setCompletedStatus(any[BSONObjectID], any[ResultStatus]))

      }
    }

    "for pull" should {
      "send notification to pull queue when url absent" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any[HeaderCarrier])).thenReturn(eventuallyRightOfPull)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, Succeeded))
        eventually(verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any[HeaderCarrier]))
        eventually(verifyNoInteractions(mockMetricsService))
        infoLogVerifier("Pull succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "fail when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe false
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verifyNoInteractions(mockPushOrPullService))
        eventually(verifyNoInteractions(mockMetricsService))
      }

      "return true when repo saves but pull fails with 404" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
        when(mockNotificationWorkItemRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any[HeaderCarrier])).thenReturn(eventuallyLeftOfPull)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, currentTimePlus2Hour))
        eventually(verifyNoInteractions(mockMetricsService))
        errorLogVerifier("Pull error PushOrPullError(Pull,HttpResultError(404,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a", exception)
      }
    }
  }

  private def infoLogVerifier(logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, "info")
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }

  private def errorLogVerifier(logText: String, e: Exception): Unit = {
    PassByNameVerifier(mockNotificationLogger, "error")
      .withByNameParam(logText)
      .withByNameParam(exception)
      .withParamMatcher(any[HasId])
      .verify()
  }
}
