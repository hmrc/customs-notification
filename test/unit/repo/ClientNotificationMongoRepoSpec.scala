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

package unit.repo

import java.util.UUID

import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.DB
import reactivemongo.api.commands._
import reactivemongo.api.indexes.CollectionIndexesManager
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.collection.JSONCollection
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId, ConversationId, Notification}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationMongoRepo, LockRepo, MongoDbProvider}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class ClientNotificationMongoRepoSpec extends UnitSpec with MockitoSugar with MongoSpecSupport with ScalaFutures {
  self =>

  "ClientNotificationMongoRepo" can {

    "handle save" should {

      "return true if there are no database errors and at least one record inserted" in new Setup {
        updateReturns(1)

        await(repository.save(clientNotification)) shouldBe true
      }

      "return false and log error if there are no database errors but no record inserted" in new Setup {
        updateReturns(0)

        await(repository.save(clientNotification)) shouldBe false
        logVerifier("error", s"Client Notification not saved for clientSubscriptionId ${uuid.toString}.")
      }

      "return false if there is a database error" in new Setup {
        private val writeConcernError = Some(WriteConcernError(1, "ERROR"))
        updateReturns(0, writeConcernError)
        await(repository.save(clientNotification)) shouldBe false
        logVerifier("error", s"Client Notification not saved for clientSubscriptionId ${uuid.toString} WriteConcernError(1,ERROR)")
      }


      "handle Delete" should {
        "return true if there are no database errors and at least one record deleted" in new Setup {
          deleteReturns(1)
          await(repository.delete(clientNotification)) shouldBe (())
        }

        "return a failed future if there are no database errors and no record deleted" in new Setup {
          deleteReturns(0)
          await(repository.delete(clientNotification))
          logVerifier("error", """Could not delete entity for selector: {"_id":{"$oid":"123456789012345678901234"}}""")
        }

        "throw a RuntimeException if there is a database error" in new Setup {
          val writeConcernError = Some(WriteConcernError(1, "ERROR"))
          deleteReturns(0, writeConcernError)
          await(repository.delete(clientNotification))
          logVerifier("error", """Could not delete entity for selector: {"_id":{"$oid":"123456789012345678901234"}}. WriteConcernError(1,ERROR)""")
        }
      }
    }

    trait Setup {

      val lockRepo = mock[LockRepo]

      val mongoDbProvider = new MongoDbProvider {
        override val mongo: () => DB = self.mongo
      }
      val mockNotificationLogger = mock[NotificationLogger]

      val mockCollection = mock[JSONCollection]
      val mockIndexesManager = mock[CollectionIndexesManager]
      val uuid = UUID.randomUUID()
      val bsonId = BSONObjectID("123456789012345678901234")
      val notification = Notification(ConversationId(UUID.randomUUID()), Seq.empty, "<tag>abcdef</tag>", "application/xml")
      val clientNotification = ClientNotification(ClientSubscriptionId(uuid), notification, Some(DateTime.now()), bsonId)
      when(mockCollection.indexesManager(any())).thenReturn(mockIndexesManager)
      when(mockIndexesManager.create(any())).thenReturn(Future.successful(writeResult()))
      val repository = new ClientNotificationMongoRepo(mongoDbProvider, lockRepo, mockNotificationLogger) {
        override lazy val collection: JSONCollection = mockCollection
      }

      private def writeResult(result: Boolean = true, alteredRecords: Int = 0, writeErrors: Seq[WriteError] = Nil,
                      writeConcernError: Option[WriteConcernError] = None): DefaultWriteResult = {
        DefaultWriteResult(
          ok = result,
          n = alteredRecords,
          writeErrors = writeErrors,
          writeConcernError = writeConcernError,
          code = None,
          errmsg = None)
      }

      private def updateWriteResult(alteredRecords: Int, maybeConcernError: Option[WriteConcernError]): UpdateWriteResult = UpdateWriteResult(
        ok = true,
        n = alteredRecords,
        writeErrors = Nil,
        writeConcernError = maybeConcernError,
        code = None,
        errmsg = None, nModified = 1, upserted = Seq.empty)

      def logVerifier(logLevel: String, logText: String): Unit = {
        MockitoPassByNameHelper.PassByNameVerifier(mockNotificationLogger, logLevel)
          .withByNameParam(logText)
          .withParamMatcher(any[HeaderCarrier])
          .verify()
      }

      def updateReturns(alteredRecords: Int, maybeConcernError: Option[WriteConcernError] = None): OngoingStubbing[Future[UpdateWriteResult]] =
        when(mockCollection.update(any(), any(), any(), any(), any())(any(), any(), any())).thenReturn(Future.successful(updateWriteResult(alteredRecords, maybeConcernError)))

      def deleteReturns(n: Int, maybeConcernError: Option[WriteConcernError] = None): OngoingStubbing[Future[WriteResult]] = when(mockCollection.remove(any(), any(), any())(any(), any())).thenReturn(writeResult(writeConcernError = maybeConcernError, alteredRecords = n))
    }
  }
}