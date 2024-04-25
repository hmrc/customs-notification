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
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.Elem

class CustomsNotificationServiceSpec extends UnitSpec with MockitoSugar with Eventually {

  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  trait setup {
    val timeout = Mockito.timeout(5000).times(1)
    val eventuallyRightOfPush = Future.successful(Right(Push))
    val exception = new Exception("Boom")
    val eventuallyLeftOfPush = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.NOT_FOUND, exception))))
    val eventuallyLeftOfPush500 = Future.successful(Left(PushOrPullError(Push, HttpResultError(Helpers.INTERNAL_SERVER_ERROR, exception))))
    val eventuallyLeftOfPull = Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.NOT_FOUND, exception))))
    val eventuallyRightOfPull = Future.successful(Right(Pull))
    val eventuallyUnit = Future.successful(())

    val ValidXML: Elem = <foo1></foo1>
    val currentTime = ZonedDateTime.of(2024, 4, 25, 0, 0, 0, 0, ZoneId.of("UTC"))
    val currentTimePlus2Hour = currentTime.plusHours(2)
    val mockPushOrPullService = mock[PushOrPullService]
    val mockNotificationLogger = mock[NotificationLogger]
    // if we want a real logger
    //    val serviceConfig = mock[ServicesConfig]
    //    when(serviceConfig.getString("application.logger.name")).thenReturn("massive-CustomsNotificationServiceSpec")
    //    val cdsLogger = new CdsLogger(serviceConfig)
    //    val mockNotificationLogger = new NotificationLogger(cdsLogger)
    val mockNotificationWorkItemRepo = mock[NotificationWorkItemRepo]
    lazy val mockMetricsService = mock[CustomsNotificationMetricsService]
    lazy val mockDateTimeService = mock[DateTimeService]
    lazy val mockAuditingService = mock[AuditingService]
    lazy val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]


    private val notificationConfig = NotificationConfig(Seq[String](""),
      60,
      false,
      FiniteDuration(30, SECONDS),
      FiniteDuration(30, SECONDS),
      FiniteDuration(30, SECONDS),
      1,
      120)

    when(mockCustomsNotificationConfig.notificationConfig).thenReturn(notificationConfig)
    when(mockDateTimeService.zonedDateTimeUtc).thenReturn(currentTime)

    val instance = new CustomsNotificationService(
      mockNotificationLogger,
      mockNotificationWorkItemRepo,
      mockPushOrPullService,
      mockMetricsService,
      mockCustomsNotificationConfig,
      mockDateTimeService,
      mockAuditingService
    )
  }

  "CustomsNotificationService.handleNotification" should {
    "for push" should {
      "send notification with metrics time to third party when url present and call metrics service" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
          when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(eventuallyRightOfPush)
          when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))
          when(mockMetricsService.notificationMetric(NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(()))

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

          await(result) shouldBe true
          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())
            verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            infoLogVerifier(mockNotificationLogger, s"Push succeeded for workItemId ${WorkItem1.id}")
          }
        }
      }

      "return false and not push when it is unable to save notification to repository" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

          await(result) shouldBe false
          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            verifyNoInteractions(mockPushOrPullService)
            verifyNoInteractions(mockMetricsService)
          }
        }
      }

      "return true when repo saves but push fails with 404" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
          when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
          when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
          when(mockNotificationWorkItemRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
          when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(eventuallyLeftOfPush)

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

          await(result) shouldBe true
          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockNotificationWorkItemRepo, timeout).incrementFailureCount(WorkItem1.id)
            verify(mockNotificationWorkItemRepo, timeout).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)
            verify(mockAuditingService, timeout).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            errorLogVerifier(mockNotificationLogger, s"Push failed PushOrPullError(Push,HttpResultError(404,java.lang.Exception: Boom)) for workItemId ${WorkItem1.id}")
          }
        }
      }

      "return true when repo saves but push fails with 500" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
          when(mockNotificationWorkItemRepo.setPermanentlyFailed(WorkItem1.id, Helpers.INTERNAL_SERVER_ERROR)).thenReturn(Future.successful(()))
          when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
          when(mockNotificationWorkItemRepo.setPermanentlyFailed(WorkItem1.id, Helpers.INTERNAL_SERVER_ERROR)).thenReturn(eventuallyUnit)
          when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(eventuallyLeftOfPush500)

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

          await(result) shouldBe true
          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id)
            verify(mockNotificationWorkItemRepo).setPermanentlyFailed(WorkItem1.id, Helpers.INTERNAL_SERVER_ERROR)
            verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            errorLogVerifier(mockNotificationLogger, s"Push failed PushOrPullError(Push,HttpResultError(500,java.lang.Exception: Boom)) for workItemId ${WorkItem1.id}")
          }
        }
      }

      "return true and not push when there are existing permanently failed notifications with 5xx errors" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(true))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(PermanentlyFailed))).thenReturn(Future.successful(WorkItem1))
          when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

          await(result) shouldBe true
          eventually {
            verify(mockNotificationWorkItemRepo).permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(PermanentlyFailed))
            verify(mockNotificationWorkItemRepo, times(0)).setCompletedStatus(any[ObjectId], any[ResultStatus])
            verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            verifyNoInteractions(mockPushOrPullService)
            infoLogVerifier(mockNotificationLogger, "Existing permanently failed notifications found for client id: ClientId. Setting notification to permanently failed")
          }
        }
      }

      "return true BEFORE push has succeeded" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
          when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, PermanentlyFailed)).thenReturn(Future.successful(()))
          when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPush))(any[HasId], any())).thenReturn(
            Future {
              Right(Push)
            })

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPush)

          await(result) shouldBe true
          eventually {
            verify(mockNotificationWorkItemRepo).permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockNotificationWorkItemRepo, times(1)).setCompletedStatus(any[ObjectId], any[ResultStatus])
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
          }
        }
      }
    }

    "for pull" should {
      "send notification to pull queue and call metrics service when url absent" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
          when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any())).thenReturn(eventuallyRightOfPull)
          when(mockNotificationWorkItemRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(Future.successful(()))

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

          await(result) shouldBe true
          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockNotificationWorkItemRepo).setCompletedStatus(WorkItem1.id, Succeeded)
            verify(mockPushOrPullService).send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any())
            verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            infoLogVerifier(mockNotificationLogger, s"Pull succeeded for workItemId ${WorkItem1.id}")
          }
        }
      }

      "fail when it was unable to save notification to repository" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.failed(emulatedServiceFailure))

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

          await(result) shouldBe false
          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            verifyNoInteractions(mockPushOrPullService)
            verifyNoInteractions(mockMetricsService)
          }
        }
      }

      "return true and call metrics service when repo saves but pull fails with 404" in {
        new setup {
          when(mockNotificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(NotificationWorkItemWithMetricsTime1.clientSubscriptionId)).thenReturn(Future.successful(false))
          when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))).thenReturn(Future.successful(WorkItem1))
          when(mockNotificationWorkItemRepo.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
          when(mockNotificationWorkItemRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
          when(mockPushOrPullService.send(refEq(NotificationWorkItemWithMetricsTime1), ameq(ApiSubscriptionFieldsOneForPull))(any[HasId], any())).thenReturn(eventuallyLeftOfPull)

          val result = instance.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsOneForPull)

          await(result) shouldBe true

          eventually {
            verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1), refEq(InProgress))
            verify(mockNotificationWorkItemRepo).incrementFailureCount(WorkItem1.id)
            verify(mockNotificationWorkItemRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, Helpers.NOT_FOUND, currentTimePlus2Hour)
            verify(mockMetricsService).notificationMetric(NotificationWorkItemWithMetricsTime1)
            verify(mockAuditingService).auditNotificationReceived(any[PushNotificationRequest])(any[HasId], any())
            errorLogVerifier(mockNotificationLogger, s"Pull failed PushOrPullError(Pull,HttpResultError(404,java.lang.Exception: Boom)) for workItemId ${WorkItem1.id}")
          }
        }
      }
    }
  }

  private def infoLogVerifier(mockNotificationLogger: NotificationLogger, logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, "info")
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }

  private def errorLogVerifier(mockNotificationLogger: NotificationLogger, logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, "warn")
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }
}
