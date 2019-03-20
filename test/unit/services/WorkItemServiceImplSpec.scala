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

import java.time.{ZoneId, ZonedDateTime}

import org.joda.time.DateTimeZone
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.domain.{HasId, HttpResultError}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.workitem.{Failed, Succeeded}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future

class WorkItemServiceImplSpec extends UnitSpec with MockitoSugar {

  trait SetUp {
    private[WorkItemServiceImplSpec] val mockRepo = mock[NotificationWorkItemMongoRepo]
    private[WorkItemServiceImplSpec] val mockPushOrPull = mock[PushOrPullService]
    private[WorkItemServiceImplSpec] val mockDateTime = mock[DateTimeService]
    private[WorkItemServiceImplSpec] val mockLogger = mock[NotificationLogger]
    private[WorkItemServiceImplSpec] val service = new WorkItemServiceImpl(
      mockRepo, mockPushOrPull, mockDateTime, mockLogger
    )
    private[WorkItemServiceImplSpec] val UtcZoneId = ZoneId.of("UTC")
    private[WorkItemServiceImplSpec] val now: ZonedDateTime = ZonedDateTime.now(UtcZoneId)
    private[WorkItemServiceImplSpec] val nowAsDateTime = new org.joda.time.DateTime(now.toInstant.toEpochMilli, DateTimeZone.UTC)
    private[WorkItemServiceImplSpec] val eventualMaybeWorkItem1 = Future.successful(Some(WorkItem1))
    private[WorkItemServiceImplSpec] val eventualNone = Future.successful(None)
    private[WorkItemServiceImplSpec] val eventuallyUnit = Future.successful(())
    private[WorkItemServiceImplSpec] val exception = new IllegalStateException("BOOM!")
    private[WorkItemServiceImplSpec] val httpResultError = HttpResultError(Helpers.NOT_FOUND, exception)
    private[WorkItemServiceImplSpec] val eventualFailed = Future.failed(exception)

    private[WorkItemServiceImplSpec] def verifyErrorLog(msg: String) = {
      PassByNameVerifier(mockLogger, "error")
        .withByNameParam(msg)
        .withByNameParamMatcher(any[Throwable])
        .withParamMatcher(any[HasId])
        .verify()
    }

    private[WorkItemServiceImplSpec] def verifyInfoLog(msg: String) = {
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam(msg)
        .withParamMatcher(any[HasId])
        .verify()
    }
  }

  "For PUSH processOne" should {
    "return Future of false when there are no more work items" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualNone)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Right(Push)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(eventuallyUnit)

      val actual = await(service.processOne())

      actual shouldBe false
      verifyZeroInteractions(mockPushOrPull)
      verify(mockRepo, times(0)).toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)
      verify(mockRepo, times(0)).setCompletedStatus(WorkItem1.id, Failed)
    }

    "return Future of true and set WorkItem status to Success when PUSH returns 2XX" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualMaybeWorkItem1)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Right(Push)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(eventuallyUnit)

      val actual = await(service.processOne())

      actual shouldBe true
      verify(mockRepo).setCompletedStatus(WorkItem1.id, Succeeded)
      verifyInfoLog("Retry succeeded for Push")
    }

    "return Future of true and set WorkItem status to Success when PULL returns 2XX" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualMaybeWorkItem1)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Right(Pull)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(eventuallyUnit)

      val actual = await(service.processOne())

      actual shouldBe true
      verifyInfoLog("Retry succeeded for Pull")
    }

    "return Future of true and set WorkItem status to PermanentlyFailed when ApiSubscriptionFields connector returns an error" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualMaybeWorkItem1)
      private val fieldsError = PushOrPullError(GetApiSubscriptionFields, httpResultError)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Left(fieldsError)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(eventuallyUnit)
      when(mockRepo.toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)).thenReturn(Future.successful(1))

      val actual = await(service.processOne())

      actual shouldBe true
      verify(mockRepo).setCompletedStatus(WorkItem1.id, Failed)
      verify(mockRepo).toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)
      verifyInfoLog("Retry failed for GetApiSubscriptionFields with error HttpResultError(404,java.lang.IllegalStateException: BOOM!). Setting status to PermanentlyFailed for all notifications with clientId ClientId")
    }

    "return Future of true and set WorkItem status to PermanentlyFailed when PUSH returns an error" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualMaybeWorkItem1)
      private val pushError = PushOrPullError(Push, httpResultError)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Left(pushError)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(eventuallyUnit)
      when(mockRepo.toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)).thenReturn(Future.successful(1))

      val actual = await(service.processOne())

      actual shouldBe true
      verify(mockRepo).setCompletedStatus(WorkItem1.id, Failed)
      verify(mockRepo).toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)
      verifyInfoLog("Retry failed for Push with error HttpResultError(404,java.lang.IllegalStateException: BOOM!). Setting status to PermanentlyFailed for all notifications with clientId ClientId")
    }

    "return Future of true and set WorkItem status to PermanentlyFailed when PULL returns an error" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualMaybeWorkItem1)
      private val pullError = PushOrPullError(Pull, httpResultError)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Left(pullError)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(eventuallyUnit)
      when(mockRepo.toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)).thenReturn(Future.successful(1))

      val actual = await(service.processOne())

      actual shouldBe true
      verify(mockRepo).setCompletedStatus(WorkItem1.id, Failed)
      verify(mockRepo).toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)
      verifyInfoLog("Retry failed for Pull with error HttpResultError(404,java.lang.IllegalStateException: BOOM!). Setting status to PermanentlyFailed for all notifications with clientId ClientId")
    }

    "return Future of true and log database error when PUSH returns an error and call to repository setCompletedStatus fails" in new SetUp {
      when(mockDateTime.zonedDateTimeUtc).thenReturn(now)
      when(mockRepo.pullOutstanding(failedBefore = nowAsDateTime, availableBefore = nowAsDateTime)).thenReturn(eventualMaybeWorkItem1)
      private val pullError = PushOrPullError(Push, httpResultError)
      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Left(pullError)))
      when(mockRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(eventualFailed)

      val actual = await(service.processOne())

      actual shouldBe true
      verify(mockRepo).setCompletedStatus(WorkItem1.id, Failed)
      verify(mockRepo, times(0)).toPermanentlyFailedByClientId(WorkItem1.item.clientSubscriptionId)
      verifyErrorLog("Error updating database")
    }

  }

}
