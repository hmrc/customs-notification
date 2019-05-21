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
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.test.Helpers
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, HttpResultError, UnblockPollingConfig}
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.workitem.{PermanentlyFailed, ResultStatus, Succeeded}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData.{WorkItem1, validClientSubscriptionId1}

import scala.concurrent.Future
import scala.concurrent.duration._

class UnblockPollingServiceSpec extends UnitSpec
  with MockitoSugar
  with Eventually
  with BeforeAndAfterEach {

  trait Setup {
    private[UnblockPollingServiceSpec] val csIdSetOfOne = Set(validClientSubscriptionId1)
    private[UnblockPollingServiceSpec] val CountOfChangedStatuses = 2
    private[UnblockPollingServiceSpec] val LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION = 1000000.milliseconds
    private[UnblockPollingServiceSpec] val BoomException = new Exception("Boom")

    private[UnblockPollingServiceSpec] val notificationWorkItemRepoMock = mock[NotificationWorkItemRepo]
    private[UnblockPollingServiceSpec] val configServiceMock = mock[ConfigService]
    private[UnblockPollingServiceSpec] val mockCdsLogger= mock[CdsLogger]
    private[UnblockPollingServiceSpec] val testActorSystem = ActorSystem("UnblockPollingService")
    private[UnblockPollingServiceSpec] val mockUnblockPollingConfig = mock[UnblockPollingConfig]
    private[UnblockPollingServiceSpec] val mockPushOrPullService = mock[PushOrPullService]

    private[UnblockPollingServiceSpec] def verifyInfoLog(msg: String) = {
      PassByNameVerifier(mockCdsLogger, "info")
        .withByNameParam(msg)
        .verify()
    }

    private[UnblockPollingServiceSpec] def verifyErrorLog(msg: String) = {
      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam(msg)
        .withByNameParamMatcher(any[Throwable])
        .verify()
    }

    when(configServiceMock.unblockPollingConfig).thenReturn(mockUnblockPollingConfig)
  }

  "UnblockPollingService" should {

    "should poll the database and unblock any blocked notifications when ONE distinct CsId found" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(notificationWorkItemRepoMock.toFailedByCsId(validClientSubscriptionId1)).thenReturn(Future.successful(CountOfChangedStatuses))
      when(mockPushOrPullService.send(WorkItem1.item)).thenReturn(Future.successful(Right(Push)))
      when(mockUnblockPollingConfig.pollingEnabled) thenReturn true
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollingService(configServiceMock,
          testActorSystem,
          notificationWorkItemRepoMock,
          mockPushOrPullService,
          mockCdsLogger)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(WorkItem1.item)
        verify(notificationWorkItemRepoMock, times(1)).setCompletedStatus(WorkItem1.id, Succeeded)
        verify(notificationWorkItemRepoMock, times(1)).toFailedByCsId(validClientSubscriptionId1)
        verifyInfoLog("Unblock - discovered 1 blocked csids (i.e. with status of permanently-failed)")
        verifyInfoLog("Unblock pilot send for Push succeeded. CsId = eaca01f9-ec3b-4ede-b263-61b626dde232. Setting work item status succeeded for WorkItem(BSONObjectID(\"5c46f7d70100000100ef835a\"),2016-01-30T23:46:59.000Z,2016-01-30T23:46:59.000Z,2016-01-30T23:46:59.000Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),Notification(eaca01f9-ec3b-4ede-b263-61b626dde231,List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234)),<foo1></foo1>,application/xml)))")
        verifyInfoLog("Unblock - number of notifications set from PermanentlyFailed to Failed = 2 for CsId eaca01f9-ec3b-4ede-b263-61b626dde232")
      }
    }

    "should poll the database and NOT unblock any blocked notifications when NO distinct CsId found" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(Set.empty[ClientSubscriptionId]))
      when(mockUnblockPollingConfig.pollingEnabled) thenReturn true
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollingService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatus(any[BSONObjectID], any[ResultStatus])
        verifyZeroInteractions(mockPushOrPullService)
      }
    }

    "should poll the database and NOT unblock any blocked notifications when pull for CsId returns None" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(None)
      when(mockUnblockPollingConfig.pollingEnabled) thenReturn true
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollingService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger)

      eventually {
        verifyZeroInteractions(mockPushOrPullService)
        verifyInfoLog("Unblock - discovered 1 blocked csids (i.e. with status of permanently-failed)")
        verifyInfoLog("Unblock found no PermanentlyFailed notifications for CsId eaca01f9-ec3b-4ede-b263-61b626dde232")
      }
    }

    "should poll the database and NOT unblock any blocked notifications when Push/Pull fails" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(mockPushOrPullService.send(WorkItem1.item)).thenReturn(Future.successful(Left(PushOrPullError(Pull, HttpResultError(Helpers.NOT_FOUND, BoomException)))))
      when(mockUnblockPollingConfig.pollingEnabled) thenReturn true
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollingService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(WorkItem1.item)
        verify(notificationWorkItemRepoMock, times(1)).setCompletedStatus(WorkItem1.id, PermanentlyFailed)
        verify(notificationWorkItemRepoMock, times(0)).toFailedByCsId(validClientSubscriptionId1)
        verifyInfoLog("Unblock - discovered 1 blocked csids (i.e. with status of permanently-failed)")
        verifyInfoLog("Unblock pilot send for Pull failed with error HttpResultError(404,java.lang.Exception: Boom). CsId = eaca01f9-ec3b-4ede-b263-61b626dde232. Setting work item status back to permanently-failed for WorkItem(BSONObjectID(\"5c46f7d70100000100ef835a\"),2016-01-30T23:46:59.000Z,2016-01-30T23:46:59.000Z,2016-01-30T23:46:59.000Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),Notification(eaca01f9-ec3b-4ede-b263-61b626dde231,List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234)),<foo1></foo1>,application/xml)))")
      }
    }

    "should poll the database and recover from Push/Pull failure" in new Setup {
      when(notificationWorkItemRepoMock.distinctPermanentlyFailedByCsId()).thenReturn(Future.successful(csIdSetOfOne), Future.successful(csIdSetOfOne))
      when(notificationWorkItemRepoMock.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)).thenReturn(Some(WorkItem1))
      when(mockPushOrPullService.send(WorkItem1.item)).thenReturn(Future.failed(BoomException))
      when(mockUnblockPollingConfig.pollingEnabled) thenReturn true
      when(mockUnblockPollingConfig.pollingDelay).thenReturn(LARGE_DELAY_TO_ENSURE_ONCE_ONLY_EXECUTION)

      new UnblockPollingService(configServiceMock,
        testActorSystem,
        notificationWorkItemRepoMock,
        mockPushOrPullService,
        mockCdsLogger)

      eventually {
        verify(notificationWorkItemRepoMock, times(1)).distinctPermanentlyFailedByCsId()
        verify(notificationWorkItemRepoMock, times(1)).pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1)
        verify(mockPushOrPullService, times(1)).send(WorkItem1.item)
        verify(notificationWorkItemRepoMock, times(0)).setCompletedStatus(any[BSONObjectID], any[ResultStatus])
        verify(notificationWorkItemRepoMock, times(0)).toFailedByCsId(validClientSubscriptionId1)
        verifyErrorLog("Unblock - error with pilot unblock of work item WorkItem(BSONObjectID(\"5c46f7d70100000100ef835a\"),2016-01-30T23:46:59.000Z,2016-01-30T23:46:59.000Z,2016-01-30T23:46:59.000Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),Notification(eaca01f9-ec3b-4ede-b263-61b626dde231,List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234)),<foo1></foo1>,application/xml)))")
      }
    }
  }

}
