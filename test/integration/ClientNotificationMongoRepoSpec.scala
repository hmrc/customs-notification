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

import org.joda.time.{DateTime, DateTimeZone, Seconds}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.Json
import reactivemongo.api.{Cursor, DB}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class ClientNotificationMongoRepoSpec extends UnitSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with MockitoSugar
  with MongoSpecSupport  { self =>

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockErrorHandler = mock[ClientNotificationRepositoryErrorHandler]

  private lazy implicit val emptyHC: HeaderCarrier = HeaderCarrier()
  private val timeoutInSeconds = 2
  private val duration = org.joda.time.Duration.standardSeconds(timeoutInSeconds)
  private val five = 5
  private val pushConfigWithMaxFiveRecords = PushNotificationConfig(
    internalClientIds = Seq.empty,
    pollingDelay = 0 second,
    lockDuration = org.joda.time.Duration.ZERO,
    maxRecordsToFetch = five,
    ttlInSeconds = 1
  )
  private val metricsConfig: NotificationMetricsConfig = NotificationMetricsConfig("http://abc.com")
  private val TenThousand = 10000
  private val pullExcludeConfigZeroMillis = PullExcludeConfig(pullExcludeEnabled = true, emailAddress = "some.address@domain.com",
    notificationsOlderMillis = 0, csIdsToExclude = Seq("eaca01f9-ec3b-4ede-b263-61b626dde232"), "some-email-url", 0 seconds, 0 minutes)

  private val mongoDbProvider: MongoDbProvider = new MongoDbProvider {
    override val mongo: () => DB = self.mongo
  }

  val lockRepository = new LockRepository
  val lockRepo: LockRepo = new LockRepo(mongoDbProvider, mockNotificationLogger) {
    val db: () => DB = () => mock[DB]
    override val repo: LockRepository = lockRepository
  }

  private def configWithMaxRecords(maxRecords: Int = five, notificationsOlder: Int = 0): CustomsNotificationConfig = {
    val config = new CustomsNotificationConfig{
      override def maybeBasicAuthToken: Option[String] = None
      override def notificationQueueConfig: NotificationQueueConfig = mock[NotificationQueueConfig]
      override def googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig = mock[GoogleAnalyticsSenderConfig]
      override def pushNotificationConfig: PushNotificationConfig = pushConfigWithMaxFiveRecords.copy(maxRecordsToFetch = maxRecords)
      override def pullExcludeConfig: PullExcludeConfig = pullExcludeConfigZeroMillis.copy(notificationsOlderMillis = notificationsOlder)

      override def notificationMetricsConfig: NotificationMetricsConfig = metricsConfig
    }
    config
  }

  private val repository = new ClientNotificationMongoRepo(configWithMaxRecords(five), mongoDbProvider, lockRepo, mockErrorHandler, mockNotificationLogger)
  private val repositoryWithOneMaxRecord = new ClientNotificationMongoRepo(configWithMaxRecords(1), mongoDbProvider, lockRepo, mockErrorHandler, mockNotificationLogger)
  private val repositoryWithLongWait = new ClientNotificationMongoRepo(configWithMaxRecords(five, TenThousand), mongoDbProvider, lockRepo, mockErrorHandler, mockNotificationLogger)

  override def beforeEach() {
    await(repository.drop)
    await(lockRepository.drop)
    Mockito.reset(mockErrorHandler, mockNotificationLogger)
  }

  override def afterAll() {
    await(repository.drop)
    await(lockRepository.drop)
  }

  private def collectionSize: Int = {
    await(repository.collection.count())
  }

  private def selector(clientSubscriptionId: ClientSubscriptionId) = {
    Json.obj("csid" -> clientSubscriptionId.id)
  }

  private def logVerifier(logLevel: String, logText: String): Unit = {
    PassByNameVerifier(mockNotificationLogger, logLevel)
      .withByNameParam(logText)
      .withParamMatcher(any[HeaderCarrier])
      .verify()
  }

  "repository" should {
    "successfully save a single notification" in {
      when(mockErrorHandler.handleSaveError(any(), any(), any())).thenReturn(true)
      val saveResult = await(repository.save(client1Notification1))
      saveResult shouldBe true
      collectionSize shouldBe 1

      val findResult = await(repository.collection.find(selector(validClientSubscriptionId1)).one[ClientNotification]).get
      findResult.id should not be None
      findResult.timeReceived should not be None
      Seconds.secondsBetween(DateTime.now(DateTimeZone.UTC), findResult.timeReceived.get).getSeconds should be < 3
      findResult.notification shouldBe client1Notification1.notification
      findResult.csid shouldBe client1Notification1.csid
    }

    "successfully save when called multiple times" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      collectionSize shouldBe 3
      val clientNotifications = await(repository.collection.find(selector(validClientSubscriptionId1)).cursor[ClientNotification]().collect[List](Int.MaxValue, Cursor.FailOnError[List[ClientNotification]]()))
      clientNotifications.size shouldBe 2
      clientNotifications.head.id should not be None
    }

    "fetch by clientSubscriptionId should return a two records when not limited by max records to fetch" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      val clientNotifications = await(repository.fetch(validClientSubscriptionId1))

      clientNotifications.size shouldBe 2
      clientNotifications.head.notification shouldBe client1Notification1.notification
      clientNotifications(1).notification shouldBe client1Notification2.notification

      logVerifier("debug", "fetching clientNotification(s) with csid: eaca01f9-ec3b-4ede-b263-61b626dde232 and with max records=5")
    }

    "fetch by clientSubscriptionId should return a one record when limited by one max record to fetch" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      val clientNotifications = await(repositoryWithOneMaxRecord.fetch(validClientSubscriptionId1))

      clientNotifications.size shouldBe 1
      clientNotifications.head.notification shouldBe client1Notification1.notification
    }

    "return empty List when not found" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      val nonExistentClientNotification = client2Notification1

      await(repository.fetch(nonExistentClientNotification.csid)) shouldBe Nil
    }

    "delete by ClientNotification should remove record" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))

      collectionSize shouldBe 2
      val clientNotifications = repository.fetch(client1Notification1.csid)

      val clientNotificationToDelete = clientNotifications.head
      await(repository.delete(clientNotificationToDelete))

      collectionSize shouldBe 1
      logVerifier("debug", s"[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde232] deleting clientNotification with objectId: ${clientNotificationToDelete.id.stringify}")
    }

    "collection should be same size when deleting non-existent record" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      collectionSize shouldBe 2

      await(repository.delete(client1Notification1.copy(id = BSONObjectID.generate())))

      collectionSize shouldBe 2
    }

    "notifications returned in insertion order" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))
      await(repository.save(client1Notification3))

      collectionSize shouldBe 4
      val clientNotifications = await(repository.fetch(validClientSubscriptionId1))
      clientNotifications.size shouldBe 3
      clientNotifications.head.notification.payload shouldBe payload1
      clientNotifications(1).notification.payload shouldBe payload2
      clientNotifications(2).notification.payload shouldBe payload3
    }

    "only notifications without locks should be returned" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))
      await(repository.save(client1Notification3))

      await(lockRepo.tryToAcquireOrRenewLock(validClientSubscriptionId1, LockOwnerId(validClientSubscriptionId1.id.toString), duration))

      val unlockedNotifications = await(repository.fetchDistinctNotificationCSIDsWhichAreNotLocked())

      unlockedNotifications.size shouldBe 1
      unlockedNotifications.head shouldBe validClientSubscriptionId2
    }

    "return true if notification exists" in {
      await(repository.save(client1Notification1))
      await(repository.save(client2Notification1))

      val exists = await(repository.failedPushNotificationsExist())

      exists shouldBe true
    }

    "return false if no notifications exist" in {
      await(repository.save(client2Notification1))

      val exists = await(repository.failedPushNotificationsExist())

      exists shouldBe false
    }

    "return true if notifications exists but are too young" in {
      await(repository.save(client1Notification1))
      await(repository.save(client2Notification1))

      val exists = await(repositoryWithLongWait.failedPushNotificationsExist())

      exists shouldBe false
    }

  }
}
