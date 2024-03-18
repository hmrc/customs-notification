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

import akka.actor.ActorSystem
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, NotificationConfig}
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.customs.notification.services.{WorkItemProcessingScheduler, WorkItemService}
import util.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class WorkItemProcessingSchedulerSpec extends UnitSpec with MockitoSugar
  with Eventually {

  private[WorkItemProcessingSchedulerSpec] val exception = new Exception("planned failure")

  trait SetUp {
    private[WorkItemProcessingSchedulerSpec] implicit val actorSystem = ActorSystem.create("WorkItemProcessingSchedulerSpec")

    private[WorkItemProcessingSchedulerSpec] implicit val applicationLifecycle = new ApplicationLifecycle {
      
      override def addStopHook(hook: () => Future[_]): Unit = {}
      override def stop(): Future[_] = {}
    }

    private[WorkItemProcessingSchedulerSpec] val mockConfig = mock[CustomsNotificationConfig]
    private[WorkItemProcessingSchedulerSpec] val mockPushConfig = mock[NotificationConfig]
    private[WorkItemProcessingSchedulerSpec] val mockLogger = mock[CdsLogger]

    when(mockConfig.notificationConfig).thenReturn(mockPushConfig)
    when(mockPushConfig.retryPollerEnabled).thenReturn(true)
    when(mockPushConfig.retryPollerInterval).thenReturn(1 second)
    when(mockPushConfig.retryPollerAfterFailureInterval).thenReturn(2 second)
    when(mockPushConfig.retryPollerInProgressRetryAfter).thenReturn(2 second)
    when(mockPushConfig.retryPollerInstances).thenReturn(1)

    private[WorkItemProcessingSchedulerSpec] val stubWorkItemService = new StubWorkItemService()
    private[WorkItemProcessingSchedulerSpec] val scheduler = new WorkItemProcessingScheduler(stubWorkItemService, mockConfig, mockLogger)
  }


  class StubWorkItemService extends WorkItemService {
    var remainingItems = 0
    var processedItems = 0
    var shouldFail = false

    def addRemainingItems(count: Int): Unit = synchronized {
      remainingItems = remainingItems + count
    }

    def simulateFailure(): Unit = synchronized {
      shouldFail = true
    }

    override def processOne(): Future[Boolean] = synchronized {
      if (shouldFail) {
        shouldFail = false
        Future.failed(exception)
      } else if (remainingItems > 0) {
        remainingItems = remainingItems - 1
        processedItems = processedItems + 1
        Future.successful(true)
      } else {
        Future.successful(false)
      }
    }

  }

  "scheduler" should {

    "process all requests waiting in the queue" in new SetUp {

      stubWorkItemService.addRemainingItems(3)

      eventually(Timeout(scaled(Span(2, Seconds))),
        Interval(scaled(Span(200, Millis)))) {
        stubWorkItemService.processedItems == 3
      }
      scheduler.shutDown()

    }

    "continue polling after a while when there are no messages in the queue" in new SetUp {

      stubWorkItemService.addRemainingItems(3)

      eventually(Timeout(scaled(Span(2, Seconds))),
        Interval(scaled(Span(200, Millis)))) {
        stubWorkItemService.processedItems shouldBe 3
      }

      Thread.sleep(1000)

      stubWorkItemService.addRemainingItems(3)

      eventually(Timeout(scaled(Span(2, Seconds))),
        Interval(scaled(Span(200, Millis)))) {
        stubWorkItemService.processedItems shouldBe 6
      }

      scheduler.shutDown()

    }

    "continue polling after a while when there was an error processing the message" in new SetUp {
      stubWorkItemService.addRemainingItems(3)

      eventually(Timeout(scaled(Span(2, Seconds))),
        Interval(scaled(Span(200, Millis)))) {
        stubWorkItemService.processedItems shouldBe 3
      }

      stubWorkItemService.simulateFailure()

      stubWorkItemService.addRemainingItems(3)

      eventually(Timeout(scaled(Span(5, Seconds))),
        Interval(scaled(Span(200, Millis)))) {
        stubWorkItemService.processedItems shouldBe 6

        PassByNameVerifier(mockLogger, "error")
          .withByNameParam("Queue processing failed")
          .withByNameParam(exception)
          .verify()
      }

      scheduler.shutDown()
    }

  }


}
