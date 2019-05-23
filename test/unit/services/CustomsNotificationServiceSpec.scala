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

import org.mockito.ArgumentMatchers.{any, refEq, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Span}
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.test.Helpers
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain.{HasId, HttpResultError}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.workitem.{InProgress, PermanentlyFailed, ResultStatus, Succeeded}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future
import scala.xml.Elem

class CustomsNotificationServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

  private val eventuallyRightOfPush = Future.successful(Right(Push))
  private val exception = new Exception("Boom")
  private val eventuallyLeftOfPush = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyLeftOfPull = Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyLeftOfApiSubsFields = Future.successful(Left(PushOrPullError(GetApiSubscriptionFields, HttpResultError(Helpers.NOT_FOUND, exception))))
  private val eventuallyRightOfPull = Future.successful(Right(Pull))

  private val ValidXML: Elem = <foo1></foo1>
  private val mockPushOrPullService = mock[PushOrPullService]
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockNotificationWorkItemRepo = mock[NotificationWorkItemRepo]
  private lazy val mockMetricsService = mock[CustomsNotificationMetricsService]

   private val service = new CustomsNotificationService(
    mockNotificationLogger,
    mockNotificationWorkItemRepo,
    mockPushOrPullService,
    mockMetricsService
  )

  private implicit val implicitRequestMetaData = requestMetaData

  override protected def beforeEach() {
    reset(mockNotificationWorkItemRepo, mockNotificationLogger, mockPushOrPullService)
  }

  "CustomsNotificationService" should {
    "for push" should {
      "send notification with metrics time to third party when url present and call metrics service" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId])).thenReturn(eventuallyRightOfPush)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))
        when(mockMetricsService.notificationMetric(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId]))
        eventually(verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1))
        infoLogVerifier("Push succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "returned HasSaved is false when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe false
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verifyZeroInteractions(mockPushOrPullService))
      }

      "returned HasSaved is true when repo saves but push fails" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId])).thenReturn(eventuallyLeftOfPush)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, PermanentlyFailed))
        errorLogVerifier("Push error PushOrPullError(Push,HttpResultError(404,java.lang.Exception: Boom)) for workItemId 5c46f7d70100000100ef835a", exception)
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
        var returnTimeOfPush = 0L
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId])).thenReturn(
          Future{
            Thread.sleep(1000)
            returnTimeOfPush = System.currentTimeMillis()
            Right(Pull)
          })

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

        await(result) shouldBe true
        val returnTimeOfSavedToDb = System.currentTimeMillis()
        eventually(verify(mockNotificationWorkItemRepo).permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId))
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo, times(0)).setCompletedStatus(any[BSONObjectID], any[ResultStatus]))
        eventually(assert(returnTimeOfPush > returnTimeOfSavedToDb, "Save to database did not happen before push"))
      }
    }

    "for pull" should {
      "send notification to pull queue when url absent" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId])).thenReturn(eventuallyRightOfPull)
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, Succeeded))
        eventually(verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId]))
        eventually(verifyZeroInteractions(mockMetricsService))
        infoLogVerifier("Pull succeeded for workItemId 5c46f7d70100000100ef835a")
      }

      "fail when it was unable to save notification to repository" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe false
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verifyZeroInteractions(mockPushOrPullService))
        eventually(verifyZeroInteractions(mockMetricsService))
      }

      "return true when repo saves but pull fails" in {
        when(mockNotificationWorkItemRepo.permanentlyFailedByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
        when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
        when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
        when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId])).thenReturn(eventuallyLeftOfPull)

        val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

        await(result) shouldBe true
        eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress)))
        eventually(verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, PermanentlyFailed))
        eventually(verifyZeroInteractions(mockMetricsService))
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
