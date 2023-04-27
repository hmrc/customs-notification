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

package integration

import com.typesafe.config.Config
import org.mockito.Mockito._
import org.mongodb.scala.model.Filters
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Configuration
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import unit.logging.StubCdsLogger
import util.TestData._
import util.UnitSpec

import java.time.temporal.ChronoUnit
import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class NotificationWorkItemRepoSpec extends UnitSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with GuiceOneAppPerSuite
  with MockitoSugar
  with ScalaFutures {

  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext
  private val stubCdsLogger: StubCdsLogger = StubCdsLogger()
  private val mockUnblockPollerConfig: UnblockPollerConfig = mock[UnblockPollerConfig]
  private val mockConfiguration = mock[Configuration]

  private val pushConfig = NotificationConfig(
    internalClientIds = Seq.empty,
    ttlInSeconds = 1000,
    retryPollerEnabled = true,
    retryPollerInterval = 1 second,
    retryPollerAfterFailureInterval = 2 seconds,
    retryPollerInProgressRetryAfter = 2 seconds,
    retryPollerInstances = 1,
    nonBlockingRetryAfterMinutes = 120
  )

  private val mongoRepository: MongoComponent =
    app.injector.instanceOf[MongoComponent]

  private val customsNotificationConfig: CustomsNotificationConfig = {
    new CustomsNotificationConfig {
      override def maybeBasicAuthToken: Option[String] = None

      override def notificationQueueConfig: NotificationQueueConfig = mock[NotificationQueueConfig]

      override def notificationConfig: NotificationConfig = pushConfig

      override def notificationMetricsConfig: NotificationMetricsConfig = mock[NotificationMetricsConfig]

      override def unblockPollerConfig: UnblockPollerConfig = mockUnblockPollerConfig
    }
  }

  private val repository = new NotificationWorkItemMongoRepo(mongoRepository, customsNotificationConfig, stubCdsLogger, mockConfiguration)

  override def beforeEach(): Unit = {
    when(mockConfiguration.underlying).thenReturn(mock[Config])
    await(repository.collection.deleteMany(Filters.exists("_id")).toFuture())
  }

  private def collectionSize: Long = {
    await(repository.collection.estimatedDocumentCount().toFuture())
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
      await(repository.setCompletedStatusWithAvailableAt(result.id, Failed, Helpers.INTERNAL_SERVER_ERROR, availableAt))

      val failedItem: Option[WorkItem[NotificationWorkItem]] = await(repository.findById(result.id))
      failedItem.get.status shouldBe Failed
      failedItem.get.failureCount shouldBe 1
      failedItem.get.availableAt.toEpochMilli shouldBe availableAt.toInstant.toEpochMilli
    }

    "return correct count of permanently failed items" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))

      val result = await(repository.blockedCount(clientId1))

      result shouldBe 2
    }

    "return zero when no notifications are permanently failed" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))

      val result = await(repository.blockedCount(clientId1))

      result shouldBe 0
    }

    "return count of unblocked flags cleared" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))

      val result = await(repository.deleteBlocked(clientId1))

      result shouldBe 2
    }

    "return zero when no notification blocked flags present" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))

      val result = await(repository.deleteBlocked(clientId1))

      result shouldBe 0
    }

    "return zero when no notifications with clientId are present when setting to PermanentlyFailed" in {
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))
      await(repository.pushNew(NotificationWorkItem1, repository.now().plus(120, ChronoUnit.MINUTES), failed))

      val result = await(repository.toPermanentlyFailedByCsId(validClientSubscriptionId1))

      result shouldBe 0
    }

    "return count of notifications blocked by setting to PermanentlyFailed" in {
      val nowAsInstant = repository.now()
      val result = for {
        wiClient1One <- repository.pushNew(NotificationWorkItem1, nowAsInstant, failed)
        wiClient1Two <- repository.pushNew(NotificationWorkItem1, nowAsInstant, failed)
        wiClient3One <- repository.pushNew(NotificationWorkItem3, nowAsInstant, failed)
        _ <- repository.pushNew(NotificationWorkItem1, nowAsInstant.plus(120, ChronoUnit.MINUTES), failed)
        toPerFailedCount <- repository.toPermanentlyFailedByCsId(validClientSubscriptionId1)
      } yield (wiClient1One, wiClient1Two, wiClient3One, toPerFailedCount)

      whenReady(result) { case (wiClient1One, wiClient1Two, wiClient3One, toPerFailedCount) =>
        toPerFailedCount shouldBe 2
        await(repository.findById(wiClient1One.id)).get.status shouldBe PermanentlyFailed
        await(repository.findById(wiClient1Two.id)).get.status shouldBe PermanentlyFailed
        await(repository.findById(wiClient3One.id)).get.status shouldBe Failed
      }
    }

    "return true when at least one permanently failed items exist for client id" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))

      val result = await(repository.permanentlyFailedByCsIdExists(NotificationWorkItem1.clientSubscriptionId))

      result shouldBe true
    }

    "return false when all permanently failed items for client id are availableAt in the future" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now().plus(120, ChronoUnit.MINUTES), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now().plus(120, ChronoUnit.MINUTES), permanentlyFailed))

      val result = await(repository.permanentlyFailedByCsIdExists(NotificationWorkItem1.clientSubscriptionId))

      result shouldBe false
    }

    "return count of notifications that are changed from failed to permanently failed by client id" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), failed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), failed))
      await(repository.pushNew(NotificationWorkItem1, repository.now().plus(120, ChronoUnit.MINUTES), failed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))

      val result = await(repository.toPermanentlyFailedByCsId(NotificationWorkItem1.clientSubscriptionId))

      result shouldBe 2
    }

    "return list of distinct clientIds" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem2, repository.now().plus(120, ChronoUnit.MINUTES), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))

      val result = await(repository.distinctPermanentlyFailedByCsId())

      result should contain(ClientSubscriptionId(validClientSubscriptionId1UUID))
      result should not contain ClientSubscriptionId(validClientSubscriptionId2UUID)
    }

    "return a modified permanently failed notification with specified csid" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))

      val result = await(repository.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1))

      result.get.status shouldBe InProgress
      result.get.item.clientSubscriptionId shouldBe validClientSubscriptionId1
    }

    "not return a modified permanently failed notification with specified csid when availableAt is in the future" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now().plus(120, ChronoUnit.MINUTES), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))

      val result = await(repository.pullOutstandingWithPermanentlyFailedByCsId(validClientSubscriptionId1))

      result.size shouldBe 0
    }

    "return count of notifications that are changed from permanently failed to failed by csid" in {
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now().plus(120, ChronoUnit.MINUTES), permanentlyFailed))
      val changed = await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem1, repository.now(), failed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), failed))

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
      await(repository.pushNew(NotificationWorkItem1, repository.now(), inProgress))
      await(repository.pushNew(NotificationWorkItem2, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))
      collectionSize shouldBe 3

      await(repository.deleteAll())

      collectionSize shouldBe 0
    }

    "successfully get a notification that does not have a mostRecentPushPullHttpStatus" in {
      val workItem = await(repository.saveWithLock(NotificationWorkItem1))
      val actual = await(repository.findById(workItem.id))

      actual.get.item.notification.mostRecentPushPullStatusCode shouldBe None
    }

  }
}
