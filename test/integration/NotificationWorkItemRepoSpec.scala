/*
 * Copyright 2021 HM Revenue & Customs
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

import java.time.{Clock, ZoneId, ZonedDateTime}

import com.typesafe.config.Config
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.Helpers
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, NotificationWorkItem, _}
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers.ClockJodaExtensions
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import util.UnitSpec
import uk.gov.hmrc.workitem._
import unit.logging.StubCdsLogger
import util.TestData._

import scala.concurrent.duration._
import scala.language.postfixOps

class NotificationWorkItemRepoSpec extends UnitSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with MockitoSugar
  with MongoSpecSupport {

  private implicit val ec = Helpers.stubControllerComponents().executionContext
  private val stubCdsLogger = StubCdsLogger()
  private val clock: Clock = Clock.systemUTC()
  private val mockUnblockPollerConfig = mock[UnblockPollerConfig]
  private val mockConfiguration = mock[Configuration]

  private val pushConfig = NotificationConfig(
    internalClientIds = Seq.empty,
    ttlInSeconds = 1,
    retryPollerEnabled = true,
    retryPollerInterval = 1 second,
    retryPollerAfterFailureInterval = 2 seconds,
    retryPollerInProgressRetryAfter = 2 seconds,
    retryPollerInstances = 1,
    nonBlockingRetryAfterMinutes = 120
  )

  private val reactiveMongoComponent: ReactiveMongoComponent =
    new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

  private val customsNotificationConfig: CustomsNotificationConfig = {
    new CustomsNotificationConfig {
      override def maybeBasicAuthToken: Option[String] = None
      override def notificationQueueConfig: NotificationQueueConfig = mock[NotificationQueueConfig]
      override def notificationConfig: NotificationConfig = pushConfig
      override def notificationMetricsConfig: NotificationMetricsConfig = mock[NotificationMetricsConfig]
      override def unblockPollerConfig: UnblockPollerConfig = mockUnblockPollerConfig
    }
  }

  private val repository = new NotificationWorkItemMongoRepo(reactiveMongoComponent, clock, customsNotificationConfig, stubCdsLogger, mockConfiguration)

  override def beforeEach() {
    when(mockConfiguration.underlying).thenReturn(mock[Config])
    await(repository.drop)
  }

  override def afterAll() {
    await(repository.drop)
  }

  private def collectionSize: Int = {
    await(repository.count(Json.obj()))
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

    "successfully save a single notification work item with specified status" in {
      val result = await(repository.saveWithLock(NotificationWorkItem1, PermanentlyFailed))

      result.status shouldBe PermanentlyFailed
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

    "update status of an item of work to Failed with a future availableAt when failed to complete" in {
      val result: WorkItem[NotificationWorkItem] = await(repository.saveWithLock(NotificationWorkItem1))
      result.status shouldBe InProgress

      val availableAt = ZonedDateTime.now(ZoneId.of("UTC")).plusSeconds(300)
      await(repository.setCompletedStatusWithAvailableAt(result.id, Failed, availableAt))

      val failedItem: Option[WorkItem[NotificationWorkItem]] = await(repository.findById(result.id))
      failedItem.get.status shouldBe Failed
      failedItem.get.failureCount shouldBe 1
      failedItem.get.availableAt.toLocalDateTime.toString shouldBe availableAt.toLocalDateTime.toString
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
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda.plusMinutes(120), failed _))

      val result = await(repository.toPermanentlyFailedByCsId(validClientSubscriptionId1))

      result shouldBe 0
    }

    "return count of notifications blocked by setting to PermanentlyFailed" in {
      val nowAsJoda = clock.nowAsJoda
      val wiClient1One = await(repository.pushNew(NotificationWorkItem1, nowAsJoda, failed _))
      val wiClient1Two = await(repository.pushNew(NotificationWorkItem1, nowAsJoda, failed _))
      val wiClient3One = await(repository.pushNew(NotificationWorkItem3, nowAsJoda, failed _))
      await(repository.pushNew(NotificationWorkItem1, nowAsJoda.plusMinutes(120), failed _))

      val result = await(repository.toPermanentlyFailedByCsId(validClientSubscriptionId1))

      result shouldBe 2
      await(repository.findById(wiClient1One.id)).get.status shouldBe PermanentlyFailed
      await(repository.findById(wiClient1Two.id)).get.status shouldBe PermanentlyFailed
      await(repository.findById(wiClient3One.id)).get.status shouldBe Failed
    }

    "return true when at least one permanently failed items exist for client id" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))

      val result = await(repository.permanentlyFailedByCsIdExists(NotificationWorkItem1.clientSubscriptionId))

      result shouldBe true
    }

    "return false when all permanently failed items for client id are availableAt in the future" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda.plusMinutes(120), permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda.plusMinutes(120), permanentlyFailed _))

      val result = await(repository.permanentlyFailedByCsIdExists(NotificationWorkItem1.clientSubscriptionId))

      result shouldBe false
    }

    "return count of notifications that are changed from failed to permanently failed by client id" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, failed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, failed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda.plusMinutes(120), failed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))

      val result = await(repository.toPermanentlyFailedByCsId(NotificationWorkItem1.clientSubscriptionId))

      result shouldBe 2
    }

    "return list of distinct clientIds" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem2, clock.nowAsJoda.plusMinutes(120), permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))

      val result = await(repository.distinctPermanentlyFailedByCsId())

      result should contain (ClientSubscriptionId(validClientSubscriptionId1UUID))
      result should not contain ClientSubscriptionId(validClientSubscriptionId2UUID)
    }

    "return a modified permanently failed notification with specified csid" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))

      val result = await(repository.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1))

      result.get.status shouldBe InProgress
      result.get.item.clientSubscriptionId shouldBe validClientSubscriptionId1
    }

    "not return a modified permanently failed notification with specified csid when availableAt is in the future" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda.plusMinutes(120), permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))

      val result = await(repository.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1))

      result.size shouldBe 0
    }

    "return count of notifications that are changed from permanently failed to failed by csid" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda.plusMinutes(120), permanentlyFailed _))
      val changed = await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, failed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, failed _))

      val result = await(repository.fromPermanentlyFailedToFailedByCsId(validClientSubscriptionId1))

      result shouldBe 2
      await(repository.findById(changed.id)).get.status shouldBe Failed
    }

    "successfully increment failure count" in {
      val result: WorkItem[NotificationWorkItem] = await(repository.saveWithLock(NotificationWorkItem1))

      await(repository.incrementFailureCount(result.id))

      val failedItem: Option[WorkItem[NotificationWorkItem]] = await(repository.findById(result.id))
      failedItem.get.failureCount shouldBe 1
    }
    
    "successfully delete all notifications" in {
      await(repository.pushNew(NotificationWorkItem1, clock.nowAsJoda, inProgress _))
      await(repository.pushNew(NotificationWorkItem2, clock.nowAsJoda, permanentlyFailed _))
      await(repository.pushNew(NotificationWorkItem3, clock.nowAsJoda, permanentlyFailed _))
      collectionSize shouldBe 3
      
      await(repository.deleteAll())

      collectionSize shouldBe 0
    }
    
  }
}
