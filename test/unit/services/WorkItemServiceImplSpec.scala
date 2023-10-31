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

import com.codahale.metrics.{Counter, MetricRegistry}
import com.kenshoo.play.metrics.Metrics
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.config.{CustomsNotificationConfig, NotificationConfig}
import uk.gov.hmrc.customs.notification.models.HasId
import uk.gov.hmrc.customs.notification.models.repo.NotificationWorkItem
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.customs.notification.util.{NotificationLogger, NotificationWorkItemRepo}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{Failed, Succeeded}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._
import util.UnitSpec

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, SECONDS}

class WorkItemServiceImplSpec extends UnitSpec with MockitoSugar {
//
//  private implicit val ec = Helpers.stubControllerComponents().executionContext
//  private implicit val hc: HeaderCarrier = HeaderCarrier()
//
//  trait SetUp {
//    private[WorkItemServiceImplSpec] val mockRepo = mock[NotificationWorkItemRepo]
//    private[WorkItemServiceImplSpec] val mockPushOrPull = mock[PushOrPullService]
//    private[WorkItemServiceImplSpec] val mockDateTimeService = mock[DateTimeService]
//    private[WorkItemServiceImplSpec] val mockLogger = mock[NotificationLogger]
//    private[WorkItemServiceImplSpec] val mockMetrics = mock[Metrics]
//    private[WorkItemServiceImplSpec] val mockMetricRegistry: MetricRegistry = mock[MetricRegistry]
//    private[WorkItemServiceImplSpec] val mockCounter: Counter = mock[Counter]
//    private[WorkItemServiceImplSpec] lazy val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
//    private[WorkItemServiceImplSpec] val notificationConfig = NotificationConfig(Seq[String](""),
//      60,
//      false,
//      FiniteDuration(30, SECONDS),
//      FiniteDuration(30, SECONDS),
//      FiniteDuration(30, SECONDS),
//      1,
//      120)
//    private[WorkItemServiceImplSpec] val service = new WorkItemServiceImpl(
//      mockRepo, mockPushOrPull, mockDateTimeService, mockLogger, mockMetrics, mockCustomsNotificationConfig
//    )
//    private[WorkItemServiceImplSpec] val UtcZoneId = ZoneId.of("UTC")
//    private[WorkItemServiceImplSpec] val now: ZonedDateTime = ZonedDateTime.now(UtcZoneId)
//    private[WorkItemServiceImplSpec] val nowPlus2Hour = now.plusMinutes(120)
//    private[WorkItemServiceImplSpec] val nowAsInstant = now.toInstant
//    private[WorkItemServiceImplSpec] val eventualMaybeWorkItem1 = Future.successful(Some(WorkItem1))
//    private[WorkItemServiceImplSpec] val eventualNone = Future.successful(None)
//    private[WorkItemServiceImplSpec] val eventuallyUnit = Future.successful(())
//    private[WorkItemServiceImplSpec] val exception = new IllegalStateException("BOOM!")
//    private[WorkItemServiceImplSpec] val httpResultError = HttpResultError(Helpers.NOT_FOUND, exception)
//    private[WorkItemServiceImplSpec] val httpResultError500 = HttpResultError(Helpers.INTERNAL_SERVER_ERROR, exception)
//    private[WorkItemServiceImplSpec] val eventualFailed = Future.failed(exception)
//
//    when(mockMetrics.defaultRegistry).thenReturn(mockMetricRegistry)
//    when(mockMetricRegistry.counter("declaration-digital-notification-retry-total-counter")).thenReturn(mockCounter)
//    when(mockCustomsNotificationConfig.notificationConfig).thenReturn(notificationConfig)
//
//    private[WorkItemServiceImplSpec] def verifyErrorLog(msg: String) = {
//      PassByNameVerifier(mockLogger, "error")
//        .withByNameParam(msg)
//        .withByNameParamMatcher(any[Throwable])
//        .withParamMatcher(any[HasId])
//        .verify()
//    }
//
//    private[WorkItemServiceImplSpec] def verifyInfoLog(msg: String) = {
//      PassByNameVerifier(mockLogger, "info")
//        .withByNameParam(msg)
//        .withParamMatcher(any[HasId])
//        .verify()
//    }
//  }
//
//  "For PUSH processOne" should {
//    "return Future of false when there are no more work items" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualNone)
//      when(mockPushOrPull.send(WorkItem1.item)).thenReturn(Future.successful(Right(Push)))
//      when(mockRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(eventuallyUnit)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe false
//      verifyNoInteractions(mockPushOrPull)
//      verify(mockRepo, times(0)).blockFailedButNotBlockedByCsId(WorkItem1.item.clientSubscriptionId)
//      verify(mockRepo, times(0)).setCompletedStatus(WorkItem1.id, Failed)
//    }
//
//    "return Future of true and set WorkItem status to Success when PUSH returns 2XX" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Right(Push)))
//      when(mockRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(eventuallyUnit)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatus(WorkItem1.id, Succeeded)
//      verify(mockCounter).inc()
//      verifyInfoLog("Push retry succeeded for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
//    }
//
//    "return Future of true and set WorkItem status to Success when PULL returns 2XX" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Right(Pull)))
//      when(mockRepo.setCompletedStatus(WorkItem1.id, Succeeded)).thenReturn(eventuallyUnit)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verifyInfoLog("Pull retry succeeded for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml))")
//    }
//
//    "return Future of true and set WorkItem status to PermanentlyFailed when ApiSubscriptionFields connector returns an error" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      private val fieldsError = PushOrPullError(GetApiSubscriptionFields, httpResultError)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(fieldsError)))
//      when(mockRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)).thenReturn(eventuallyUnit)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)
//      verifyInfoLog("GetApiSubscriptionFields retry failed for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml)) with error HttpResultError(404,java.lang.IllegalStateException: BOOM!). Setting status to PermanentlyFailed for all notifications with clientSubscriptionId eaca01f9-ec3b-4ede-b263-61b626dde232")
//    }
//
//    "return Future of true and set WorkItem status to PermanentlyFailed when PUSH returns a 404" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      private val pushError = PushOrPullError(Push, httpResultError)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pushError)))
//      when(mockRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)).thenReturn(eventuallyUnit)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)
//      verifyInfoLog("Push retry failed for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml)) with error HttpResultError(404,java.lang.IllegalStateException: BOOM!). Setting status to PermanentlyFailed for all notifications with clientSubscriptionId eaca01f9-ec3b-4ede-b263-61b626dde232")
//    }
//
//    "return Future of true and set WorkItem status to PermanentlyFailed when PULL returns a 404" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      private val pullError = PushOrPullError(Pull, httpResultError)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pullError)))
//      when(mockRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)).thenReturn(eventuallyUnit)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)
//      verifyInfoLog("Pull retry failed for WorkItem(5c46f7d70100000100ef835a,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,2016-01-30T23:46:59Z,ToDo,0,NotificationWorkItem(eaca01f9-ec3b-4ede-b263-61b626dde232,ClientId,Some(2016-01-30T23:46:59.000Z),notificationId: Some(58373a04-2c45-4f43-9ea2-74e56be2c6d7), conversationId: eaca01f9-ec3b-4ede-b263-61b626dde231, headers: List(Header(X-Badge-Identifier,ABCDEF1234), Header(X-Submitter-Identifier,IAMSUBMITTER), Header(X-Correlation-ID,CORRID2234), Header(X-IssueDateTime,20190925104103Z)), contentType: application/xml)) with error HttpResultError(404,java.lang.IllegalStateException: BOOM!). Setting status to PermanentlyFailed for all notifications with clientSubscriptionId eaca01f9-ec3b-4ede-b263-61b626dde232")
//    }
//
//    "return Future of true and set WorkItem status to PermanentlyFailed when PUSH returns a 500" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      private val pushError = PushOrPullError(Push, httpResultError500)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pushError)))
//      when(mockRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(eventuallyUnit)
//      when(mockRepo.blockFailedButNotBlockedByCsId(WorkItem1.item.clientSubscriptionId)).thenReturn(Future.successful(1))
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatus(WorkItem1.id, Failed)
//      verify(mockRepo).blockFailedButNotBlockedByCsId(WorkItem1.item.clientSubscriptionId)
//    }
//
//    "return Future of true and set WorkItem status to PermanentlyFailed when PULL returns a 500" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      private val pullError = PushOrPullError(Pull, httpResultError500)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pullError)))
//      when(mockRepo.setCompletedStatus(WorkItem1.id, Failed)).thenReturn(eventuallyUnit)
//      when(mockRepo.blockFailedButNotBlockedByCsId(WorkItem1.item.clientSubscriptionId)).thenReturn(Future.successful(1))
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatus(WorkItem1.id, Failed)
//      verify(mockRepo).blockFailedButNotBlockedByCsId(WorkItem1.item.clientSubscriptionId)
//    }
//
//    "return Future of true and log database error when PUSH returns an error and call to repository setCompletedStatus fails" in new SetUp {
//      when(mockDateTimeService.zonedDateTimeUtc).thenReturn(now)
//      when(mockRepo.pullOutstanding(failedBefore = nowAsInstant, availableBefore = nowAsInstant)).thenReturn(eventualMaybeWorkItem1)
//      private val pullError = PushOrPullError(Push, httpResultError)
//      when(mockPushOrPull.send(any[NotificationWorkItem]())(any())).thenReturn(Future.successful(Left(pullError)))
//      when(mockRepo.setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)).thenReturn(eventualFailed)
//
//      val actual = await(service.processOne())
//
//      actual shouldBe true
//      verify(mockRepo).setCompletedStatusWithAvailableAt(WorkItem1.id, Failed, Helpers.NOT_FOUND, nowPlus2Hour)
//      verify(mockRepo, times(0)).blockFailedButNotBlockedByCsId(WorkItem1.item.clientSubscriptionId)
//      verifyErrorLog("Error updating database")
//    }
//
//  }

}
