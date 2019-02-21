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

package integration

import java.time.Clock

import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, NotificationWorkItem, _}
import uk.gov.hmrc.customs.notification.repo.{MongoDbProvider, NotificationWorkItemMongoRepo}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers.ClockJodaExtensions
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.workitem._
import unit.logging.StubCdsLogger
import util.TestData._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class NotificationWorkItemRepoSpec extends UnitSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with MockitoSugar
  with MongoSpecSupport { self =>

  private val stubCdsLogger = StubCdsLogger()
  private val clock: Clock = Clock.systemUTC()
  private val five = 5
  private val mockUnblockPollingConfig = mock[UnblockPollingConfig]

  private val pushConfig = PushNotificationConfig(
    internalClientIds = Seq.empty,
    pollingEnabled = true,
    pollingDelay = 0 second,
    lockDuration = org.joda.time.Duration.ZERO,
    maxRecordsToFetch = five,
    ttlInSeconds = 1,
    retryDelay = 500 milliseconds,
    retryDelayFactor = 2,
    retryMaxAttempts = 3,
    retryPollerEnabled = true,
    retryInitialPollingInterval = 1 second,
    retryAfterFailureInterval = 2 seconds,
    retryInProgressRetryAfter = 2 seconds,
    retryPollerInstances = 1
  )

  private val mongoDbProvider: MongoDbProvider = new MongoDbProvider{
    override val mongo: () => DB = self.mongo
  }

  private val config: CustomsNotificationConfig = {
    new CustomsNotificationConfig {
      override def maybeBasicAuthToken: Option[String] = None
      override def notificationQueueConfig: NotificationQueueConfig = mock[NotificationQueueConfig]
      override def pushNotificationConfig: PushNotificationConfig = pushConfig
      override def pullExcludeConfig: PullExcludeConfig = mock[PullExcludeConfig]
      override def notificationMetricsConfig: NotificationMetricsConfig = mock[NotificationMetricsConfig]
      override def unblockPollingConfig: UnblockPollingConfig = mockUnblockPollingConfig
    }
  }

  private val repository = new NotificationWorkItemMongoRepo(mongoDbProvider, clock, config, stubCdsLogger)

  override def beforeEach() {
    await(repository.drop)
  }

  override def afterAll() {
    await(repository.drop)
  }

  private def collectionSize: Int = {
    await(repository.collection.count())
  }

  def failed(item: NotificationWorkItem): ProcessingStatus = Failed
  def permanentlyFailed(item: NotificationWorkItem): ProcessingStatus = PermanentlyFailed
  def inProgress(item: NotificationWorkItem): ProcessingStatus = InProgress

  "repository" should {
    "successfully save a single notification work item" in {
      val result = await(repository.saveWithLock(NotificationWorkItem1))

      result.status shouldBe InProgress
      result.item shouldBe NotificationWorkItem1
      result.failureCount shouldBe 0
      collectionSize shouldBe 1
    }

    "update status of an item of work to Succeeded when successfully completed" in {
      val result: WorkItem[NotificationWorkItem] = await(repository.saveWithLock(NotificationWorkItem1))
      result.status shouldBe InProgress

      await(repository.setCompletedStatus(result.id, Succeeded))

      val succeededItem: Option[WorkItem[NotificationWorkItem]] = await(repository.findById(result.id))
      succeededItem.get.status shouldBe Succeeded
    }

    "update status of an item of work to Failed when failed to complete" in {
      val result: WorkItem[NotificationWorkItem] = await(repository.saveWithLock(NotificationWorkItem1))
      result.status shouldBe InProgress

      await(repository.setCompletedStatus(result.id, Failed))

      val failedItem: Option[WorkItem[NotificationWorkItem]] = await(repository.findById(result.id))
      failedItem.get.status shouldBe Failed
      failedItem.get.failureCount shouldBe 1
    }

    "return correct count of permanently failed items" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))

      val result = await(repository.blockedCount(clientId1))

      result shouldBe 2
    }

    "return zero when no notifications are permanently failed" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))

      val result = await(repository.blockedCount(clientId1))

      result shouldBe 0
    }

    "return count of unblocked flags cleared" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))

      val result = await(repository.deleteBlocked(clientId1))

      result shouldBe 2
    }

    "return zero when no notification blocked flags present" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))

      val result = await(repository.deleteBlocked(clientId1))

      result shouldBe 0
    }

    "return zero when no notifications with clientId are present when setting to PermanentlyFailed" in {
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))

      val result = await(repository.toPermanentlyFailedByClientId(clientId1))

      result shouldBe 0
    }

    //TODO: verify updatedAt
    "return count of notifications blocked by setting to PermanentlyFailed" in {
      val nowAsJoda = clock.nowAsJoda
      val wiClient1One = await(repository.pushNew(NotificationWorkItem1, nowAsJoda, failed _))
      val wiClient1Two = await(repository.pushNew(NotificationWorkItem1, nowAsJoda, failed _))
      val wiClient3One = await(repository.pushNew(NotificationWorkItem3, nowAsJoda, failed _))

      val result = await(repository.toPermanentlyFailedByClientId(clientId1))

      result shouldBe 2
      await(repository.findById(wiClient1One.id)).get.status shouldBe PermanentlyFailed
      await(repository.findById(wiClient1Two.id)).get.status shouldBe PermanentlyFailed
      await(repository.findById(wiClient3One.id)).get.status shouldBe Failed
    }


    //TODO: verify updatedAt
    "update all blocked notifications to unblocked" in {

      when(mockUnblockPollingConfig.pollingDelay).thenReturn(1 second)
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      val item2 = await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))
      Thread.sleep(1000)
      val item3 = await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))

      val result = await(repository.unblock())

      result shouldBe 2
      await(repository.findById(item2.id)).get.status shouldBe Failed
      await(repository.findById(item3.id)).get.status shouldBe PermanentlyFailed
    }
  }
}
