/*
 * Copyright 2023 HM Revenue & Customs
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
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mongodb.scala.bson.ObjectId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.ResultStatus
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData.{WorkItem1, validClientSubscriptionId1}
import util.UnitSpec

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.Future
import scala.concurrent.duration._

class UnblockPollerServiceSpec extends UnitSpec
  with MockitoSugar
  with Eventually
  with BeforeAndAfterEach {

  private implicit val ec = Helpers.stubControllerComponents().executionContext

  trait Setup {
    private[UnblockPollerServiceSpec] val csIdSetOfOne = Set(validClientSubscriptionId1)
    private[UnblockPollerServiceSpec] val CountOfChangedStatuses = 2
    private[UnblockPollerServiceSpec] val LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION = 1000000.milliseconds
    private[UnblockPollerServiceSpec] val BoomException = new Exception("Boom")

    private[UnblockPollerServiceSpec] val notificationWorkItemRepoMock = mock[NotificationWorkItemRepo]
    private[UnblockPollerServiceSpec] val configServiceMock = mock[ConfigService]
    private[UnblockPollerServiceSpec] val mockCdsLogger= mock[CdsLogger]
    private[UnblockPollerServiceSpec] val testActorSystem = ActorSystem("UnblockPollerService")
    private[UnblockPollerServiceSpec] val mockUnblockPollerConfig = mock[UnblockPollerConfig]
    private[UnblockPollerServiceSpec] val mockPushOrPullService = mock[PushOrPullService]
    private[UnblockPollerServiceSpec] val eventuallyUnit = Future.successful(())
    private[UnblockPollerServiceSpec] lazy val mockDateTimeService = mock[DateTimeService]
    private[UnblockPollerServiceSpec] lazy val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
    private[UnblockPollerServiceSpec] val currentTime = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
    private[UnblockPollerServiceSpec] val currentTimePlus2Hour = currentTime.plusMinutes(120)

    private[UnblockPollerServiceSpec] val notificationConfig = NotificationConfig(Seq[String](""),
      60,
      false,
      FiniteDuration(30, SECONDS),
      FiniteDuration(30, SECONDS),
      FiniteDuration(30, SECONDS),
      1,
      120)

    private[UnblockPollerServiceSpec] def verifyInfoLog(msg: String) = {
      PassByNameVerifier(mockCdsLogger, "info")
        .withByNameParam(msg)
        .verify()
    }

    private[UnblockPollerServiceSpec] def verifyErrorLog(msg: String) = {
      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam(msg)
        .withByNameParamMatcher(any[Throwable])
        .verify()
    }

    when(configServiceMock.unblockPollerConfig).thenReturn(mockUnblockPollerConfig)
    when(mockCustomsNotificationConfig.notificationConfig).thenReturn(notificationConfig)
    when(mockDateTimeService.zonedDateTimeUtc).thenReturn(currentTime)
  }

  "UnblockPollerService" should {

    "should poll the database and unblock any blocked notifications when ONE distinct CsId found" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(notificationWorkItemRepoMock.fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)).thenReturn(Future.successful(CountOfChangedStatuses))
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Right(Push)))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
          testActorSystem,
          notificationWorkItemRepoMock,
          mockPushOrPullService,
          mockCdsLogger,
          mockDateTimeService,
          mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(1)).setCompletedStatus(WorkItem1.id, Succeeded)
        verify(notificationWorkItemRepoMock, times(1)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        verifyInfoLog("Unblock - discovered 1 blocked csids (i.e. with status of permanently-failed)")
        verifyInfoLog("Unblock pilot for Push succeeded. CsId = eaca01f9-ec3b-4ede-b263-61b626dde232. Setting work item status succeeded for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
        verifyInfoLog("Unblock - number of notifications set from PermanentlyFailed to Failed = 2 for CsId eaca01f9-ec3b-4ede-b263-61b626dde232")
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
        mockCdsLogger,
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
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(None)
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verifyNoInteractions(mockPushOrPullService)
        verifyInfoLog("Unblock - discovered 1 blocked csids (i.e. with status of permanently-failed)")
        verifyInfoLog("Unblock found no PermanentlyFailed notifications for CsId eaca01f9-ec3b-4ede-b263-61b626dde232")
      }
    }

    "should poll the database and NOT unblock any blocked notifications when Push/Pull fails" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.NOT_FOUND, BoomException)))))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)
      when(notificationWorkItemRepoMock.incrementFailureCount(WorkItem1.id)).thenReturn(eventuallyUnit)
      when(notificationWorkItemRepoMock.setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, currentTimePlus2Hour)).thenReturn(eventuallyUnit)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(1)).incrementFailureCount(WorkItem1.id)
        verify(notificationWorkItemRepoMock, times(1)).setCompletedStatusWithAvailableAt(WorkItem1.id, PermanentlyFailed, currentTimePlus2Hour)
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        verifyInfoLog("Unblock - discovered 1 blocked csids (i.e. with status of permanently-failed)")
        verifyInfoLog("Unblock pilot for Pull failed with error HttpResultError(404,java.lang.Exception: Boom). CsId = eaca01f9-ec3b-4ede-b263-61b626dde232. Setting work item status back to permanently-failed for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
      }
    }

    "should poll the database and recover from Push/Pull failure" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.failed(BoomException))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatus(any[ObjectId], any[ResultStatus])
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        verifyErrorLog("Unblock - error with pilot unblock of work item WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
      }
    }

    "should poll the database and recover from a 404 Push/Pull failure" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      private val exception = new IllegalStateException("BOOM!")
      private val httpResultError = HttpResultError(Helpers.NOT_FOUND, exception)
      private val pullError = PushOrPullError(Push, httpResultError)
      when(mockPushOrPullService.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pullError)))
      when(mockUnblockPollerConfig.pollerEnabled) thenReturn true
      when(mockUnblockPollerConfig.pollerInterval).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollerService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatusWithAvailableAt(any[ObjectId], any[ResultStatus], any[ZonedDateTime])
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        verifyErrorLog("Unblock - error with pilot unblock of work item WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
      }
    }

    "should poll the database and recover from a 500 Push/Pull failure" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
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
        mockCdsLogger,
        mockDateTimeService,
        mockCustomsNotificationConfig)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(any[NotificationWorkItem]())(any())
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatusWithAvailableAt(any[ObjectId], any[ResultStatus], any[ZonedDateTime])
        verify(notificationWorkItemRepoMock, times(0)).fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1)
        verifyErrorLog("Unblock - error with pilot unblock of work item WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
      }
    }
  }

}
