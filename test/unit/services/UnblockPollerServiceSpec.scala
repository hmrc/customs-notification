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

import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mongodb.scala.bson.ObjectId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.ResultStatus
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import util.TestData.{WorkItem1, validClientSubscriptionId1}
import util.UnitSpec

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class UnblockPollerServiceSpec extends UnitSpec
  with MockitoSugar
  with Eventually
  with BeforeAndAfterEach {

  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext

  trait Setup {
    val csIdSetOfOne = Set(validClientSubscriptionId1)
    val CountOfChangedStatuses = 2
    val LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION = 1000000.milliseconds
    val BoomException = new Exception("Boom")

    val notificationWorkItemRepoMock = mock[NotificationWorkItemRepo]
    val configServiceMock = mock[ConfigService]
    val mockServicesConfig = mock[ServicesConfig]
    val logger = new CdsLogger(mockServicesConfig)
    val testActorSystem = ActorSystem("UnblockPollerService")
    val mockUnblockPollerConfig = mock[UnblockPollerConfig]
    val mockPushOrPullService = mock[PushOrPullService]
    val eventuallyUnit = Future.successful(())
    lazy val mockDateTimeService = mock[DateTimeService]
    lazy val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
    val currentTime = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
    val currentTimePlus2Hour = currentTime.plusMinutes(120)

    val retryPollerInProgressRetryAfter = 5
    val availableAt = currentTime.plusSeconds(retryPollerInProgressRetryAfter)

    val notificationConfig = NotificationConfig(Seq[String](""),
      60,
      false,
      FiniteDuration(30, SECONDS),
      FiniteDuration(30, SECONDS),
      FiniteDuration(30, SECONDS),
      1,
      120)

    when(mockServicesConfig.getString(ArgumentMatchers.eq[String]("application.logger.name"))).thenReturn("test-logger")
    when(configServiceMock.unblockPollerConfig).thenReturn(mockUnblockPollerConfig)
    when(mockCustomsNotificationConfig.notificationConfig).thenReturn(notificationConfig)
    when(mockDateTimeService.zonedDateTimeUtc).thenReturn(currentTime)
  }

  "UnblockPollerService" should {

    "should poll the database and unblock any blocked notifications when ONE distinct CsId found" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullSinglePfFor(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(notificationWorkItemRepoMock.fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)).thenReturn(Future.successful(CountOfChangedStatuses))
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Right(Push)))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullSinglePfFor(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(1)).setCompletedStatus(WorkItem1.id, Succeeded)
        verify(notificationWorkItemRepoMock, times(1)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        succeed
      }
    }

    "should poll the database and NOT unblock any blocked notifications when NO distinct CsId found" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(Set.empty[ClientSubscriptionId]))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatus(any[ObjectId], any[ResultStatus])
        verifyNoInteractions(mockPushOrPullService)
      }
    }

    "should poll the database and NOT unblock any blocked notifications when pull for CsId returns None" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullSinglePfFor(validClientSubscriptionId1)).thenReturn(None)
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verifyNoInteractions(mockPushOrPullService)
      }
    }

    "should poll the database and NOT unblock any blocked notifications when Push/Pull fails with a 5xx error" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullSinglePfFor(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.INTERNAL_SERVER_ERROR, BoomException)))))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)
      when(notificationWorkItemRepoMock.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
      when(notificationWorkItemRepoMock.setPermanentlyFailedWithAvailableAt(WorkItem1.id,PermanentlyFailed, Helpers.INTERNAL_SERVER_ERROR, availableAt)).thenReturn(eventuallyUnit)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullSinglePfFor(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(1)).incrementFailureCount(WorkItem1.id)
        verify(notificationWorkItemRepoMock, times(1)).setPermanentlyFailedWithAvailableAt(WorkItem1.id,PermanentlyFailed, Helpers.INTERNAL_SERVER_ERROR, availableAt)
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        succeed
      }
    }

    "should poll the database and recover from Push/Pull exception" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullSinglePfFor(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.failed(BoomException))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullSinglePfFor(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatus(any[ObjectId], any[ResultStatus])
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        succeed
      }
    }

    "should poll the database and recover from a 404 Push/Pull failure" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullSinglePfFor(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      private val exception = new IllegalStateException("BOOM!")
      private val httpResultError = HttpResultError(Helpers.NOT_FOUND, exception)
      private val pullError = PushOrPullError(Push, httpResultError)
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pullError)))
      when(notificationWorkItemRepoMock.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
      when(notificationWorkItemRepoMock.setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, currentTimePlus2Hour)).thenReturn(eventuallyUnit)
      when(notificationWorkItemRepoMock.fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)).thenReturn(Future.successful(CountOfChangedStatuses))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullSinglePfFor(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(1)).setCompletedStatusWithAvailableAt(any[ObjectId], ArgumentMatchers.eq[ResultStatus](Failed), anyInt(), any[ZonedDateTime])
        verify(notificationWorkItemRepoMock, times(1)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        succeed
      }
    }

    "should poll the database and recover from a 500 Push/Pull failure" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullSinglePfFor(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      private val exception = new IllegalStateException("BOOM!")
      private val httpResultError = HttpResultError(Helpers.INTERNAL_SERVER_ERROR, exception)
      private val pullError = PushOrPullError(Push, httpResultError)
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pullError)))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        logger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullSinglePfFor(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatusWithAvailableAt(any[ObjectId], any[ResultStatus], anyInt(), any[ZonedDateTime])
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        succeed
      }
    }
  }

}
