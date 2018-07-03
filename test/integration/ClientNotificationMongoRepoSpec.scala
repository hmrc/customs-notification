/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.Json
import reactivemongo.api.{Cursor, DB}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.notification.controllers.CustomMimeType
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier

import scala.concurrent.ExecutionContext.Implicits.global

class ClientNotificationMongoRepoSpec extends UnitSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with MockitoSugar
  with MongoSpecSupport  { self =>

  private val validClientSubscriptionId1String: String = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  private val validClientSubscriptionId1UUID = UUID.fromString(validClientSubscriptionId1String)
  private val validClientSubscriptionId1 = ClientSubscriptionId(validClientSubscriptionId1UUID)

  private val validClientSubscriptionId2String: String = "eaca01f9-ec3b-4ede-b263-61b626dde233"
  private val validClientSubscriptionId2UUID = UUID.fromString(validClientSubscriptionId2String)
  private val validClientSubscriptionId2 = ClientSubscriptionId(validClientSubscriptionId2UUID)

  private val validConversationIdString: String = "638b405b-9f04-418a-b648-ce565b111b7b"
  private val validConversationIdStringUUID = UUID.fromString(validConversationIdString)
  private val validConversationId = ConversationId(validConversationIdStringUUID)

  private val payload1 = "<foo1></foo1>"
  private val payload2 = "<foo2></foo2>"
  private val payload3 = "<foo3></foo3>"

  private val headers = Seq(Header("h1","v1"), Header("h2", "v2"))
  private val notification1 = Notification(validConversationId, headers, payload1, CustomMimeType.XmlCharsetUtf8)
  private val notification2 = Notification(validConversationId, headers, payload2, CustomMimeType.XmlCharsetUtf8)
  private val notification3 = Notification(validConversationId, headers, payload3, CustomMimeType.XmlCharsetUtf8)

  private val client1Notification1 = ClientNotification(validClientSubscriptionId1, notification1)
  private val client1Notification2 = ClientNotification(validClientSubscriptionId1, notification2)
  private val client1Notification3 = ClientNotification(validClientSubscriptionId1, notification3)
  private val client2Notification1 = ClientNotification(validClientSubscriptionId2, notification1)

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockErrorHandler = mock[ClientNotificationRepositoryErrorHandler]

  private lazy implicit val emptyHC: HeaderCarrier = HeaderCarrier()
  private val timeoutInSeconds = 2
  private val duration = org.joda.time.Duration.standardSeconds(timeoutInSeconds)

  private val mongoDbProvider = new MongoDbProvider {
    override val mongo: () => DB = self.mongo
  }

  val lockRepository = new LockRepository
  val lockRepo: LockRepo = new LockRepo() {
    val db: () => DB = () => mock[DB]
    override lazy val repo: LockRepository = lockRepository
  }

  private val repository = new ClientNotificationMongoRepo(mongoDbProvider, lockRepo, mockErrorHandler, mockNotificationLogger)

  override def beforeEach() {
    await(repository.drop)
    await(lockRepository.drop)
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

  "repository" should {
    "successfully save a single notification" in {
      when(mockErrorHandler.handleSaveError(any(), any(), any())).thenReturn(true)
      val saveResult = await(repository.save(client1Notification1))
      saveResult shouldBe true
      collectionSize shouldBe 1

      val findResult = await(repository.collection.find(selector(validClientSubscriptionId1)).one[ClientNotification]).get
      findResult.id should not be None
      findResult.timeReceived should not be None
      PassByNameVerifier(mockNotificationLogger, "debug")
        .withByNameParam(s"saving clientNotification: ClientNotification(ClientSubscriptionId(eaca01f9-ec3b-4ede-b263-61b626dde232),Notification(ConversationId(638b405b-9f04-418a-b648-ce565b111b7b),List(Header(h1,v1), Header(h2,v2)),<foo1></foo1>,application/xml; charset=UTF-8),None,${client1Notification1.id})")
        .withParamMatcher(any[HeaderCarrier])
        .verify()
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

    "fetch by clientSubscriptionId should return a single record when found" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      val clientNotification = await(repository.fetch(validClientSubscriptionId1))

      clientNotification.head.id should not be None
      clientNotification.head.notification shouldBe client1Notification1.notification

      PassByNameVerifier(mockNotificationLogger, "debug")
        .withByNameParam("fetching clientNotification(s) with csid: eaca01f9-ec3b-4ede-b263-61b626dde232")
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

    "return empty List when not found" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      val nonExistentClientNotification = client2Notification1

      await(repository.fetch(nonExistentClientNotification.csid)) shouldBe Nil
    }

    "delete by ObjectId should remove record" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))

      collectionSize shouldBe 2
      val clientNotifications = repository.fetch(client1Notification1.csid)

      val clientNotificationToDelete = clientNotifications.head
      await(repository.delete(clientNotificationToDelete))

      collectionSize shouldBe 1
      PassByNameVerifier(mockNotificationLogger, "debug")
        .withByNameParam(s"deleting clientNotification with objectId: ${clientNotificationToDelete.id}")
        .withParamMatcher(any[HeaderCarrier])
        .verify()

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
      val clientNotifications = await(repository.collection.find(selector(validClientSubscriptionId1)).cursor[ClientNotification]().collect[List](Int.MaxValue, Cursor.FailOnError[List[ClientNotification]]()))
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

  }
}
