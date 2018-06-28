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

import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.Json
import reactivemongo.api.{Cursor, DB}
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.notification.controllers.CustomMimeType
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationMongoRepo, MongoDbProvider}
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

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

  private val payload1 = "<foo1></foo1>"
  private val payload2 = "<foo2></foo2>"
  private val timeReceived = DateTime.now()
  private val headers = Seq(Header("h1","v1"), Header("h2", "v2"))
  private val notification1 = Notification(headers, payload1, CustomMimeType.XmlCharsetUtf8)
  private val notification2 = Notification(headers, payload2, CustomMimeType.XmlCharsetUtf8)

  private val client1Notification1 = ClientNotification(validClientSubscriptionId1, notification1, timeReceived, None)
  private val client1Notification2 = ClientNotification(validClientSubscriptionId1, notification2, timeReceived, None)
  private val client2Notification1 = ClientNotification(validClientSubscriptionId2, notification1, timeReceived, None)

  private val mockNotificationLogger = mock[NotificationLogger]

  private val mongoDbProvider = new MongoDbProvider {
    override val mongo: () => DB = self.mongo
  }

  private val repository = new ClientNotificationMongoRepo(mongoDbProvider, mockNotificationLogger)

  override def beforeEach() {
    await(repository.drop)
  }

  override def afterAll() {
    await(repository.drop)
  }

  private def collectionSize: Int = {
    await(repository.collection.count())
  }

  private def selector(clientSubscriptionId: ClientSubscriptionId) = {
    Json.obj("csid" -> clientSubscriptionId.id)
  }

  "repository" should {
    "successfully save a single notification" in {
      val saveResult = await(repository.save(client1Notification1))
      saveResult shouldBe true
      collectionSize shouldBe 1

      val findResult = await(repository.collection.find(selector(validClientSubscriptionId1)).one[ClientNotification]).get
      findResult.mongoObjectId should not be None
      findResult shouldBe client1Notification1
    }

    "successfully save when called multiple times" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      collectionSize shouldBe 3
      val clientNotifications = await(repository.collection.find(selector(validClientSubscriptionId1)).cursor[ClientNotification]().collect[List](Int.MaxValue, Cursor.FailOnError[List[ClientNotification]]()))
      clientNotifications.size shouldBe 2
      clientNotifications should contain(client1Notification1)
      clientNotifications should contain(client1Notification2)
    }

    "fetch by clientSubscriptionId should return a single record when found" in {
        await(repository.save(client1Notification1))
        await(repository.save(client1Notification2))
        await(repository.save(client2Notification1))

        val clientNotification = await(repository.fetch(validClientSubscriptionId1))

        clientNotification.head shouldBe client1Notification1
      }

    "return empty List when not found" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      val nonExistentClientNotification = client2Notification1

      await(repository.fetch(nonExistentClientNotification.csid)) shouldBe Nil
    }

    "delete by mongoObjectId should remove record" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))

      collectionSize shouldBe 2
      val clientNotifications = repository.fetch(client1Notification1.csid)

      val objectIdToDelete = clientNotifications.head.mongoObjectId.get.id
      await(repository.delete(objectIdToDelete))

      collectionSize shouldBe 1
      }

    "collection should be same size when deleting non-existent record" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      collectionSize shouldBe 2

      await(repository.delete("does-not-exist"))

      collectionSize shouldBe 2
    }

  }
}
