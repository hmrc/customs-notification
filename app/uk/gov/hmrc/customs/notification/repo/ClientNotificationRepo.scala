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

package uk.gov.hmrc.customs.notification.repo

import javax.inject.{Inject, Singleton}

import play.api.libs.json.Json
import reactivemongo.api.Cursor
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ClientNotificationRepo {

  def save(clientNotification: ClientNotification): Future[Boolean]

  def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]]

  def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]]

  def delete(mongoObjectId: BSONObjectID): Future[Unit]
}

@Singleton
class ClientNotificationMongoRepo @Inject()(mongoDbProvider: MongoDbProvider,
                                            lockRepo: LockRepo,
                                            errorHandler: ClientNotificationRepositoryErrorHandler,
                                            notificationLogger: NotificationLogger)
  extends ReactiveRepository[ClientNotification, BSONObjectID]("notifications", mongoDbProvider.mongo,
    ClientNotification.clientNotificationJF, ReactiveMongoFormats.objectIdFormats)
    with ClientNotificationRepo {

  private implicit val format = ClientNotification.clientNotificationJF
  private lazy implicit val emptyHC: HeaderCarrier = HeaderCarrier()

  override def indexes: Seq[Index] = Seq(
    Index(
      key = Seq("csid" -> IndexType.Ascending),
      name = Some("csid-Index"),
      unique = false
    ),
    Index(
      key = Seq("csid" -> IndexType.Ascending, "timeReceived" -> IndexType.Descending),
      name = Some("csid-timeReceived-Index"),
      unique = true
    )
  )

  override def save(clientNotification: ClientNotification): Future[Boolean] = {
    notificationLogger.debug(s"saving clientNotification: $clientNotification")

    lazy val errorMsg = s"Client Notification not saved for clientSubscriptionId ${clientNotification.csid}"

    collection.insert(clientNotification).map {
      writeResult => errorHandler.handleSaveError(writeResult, errorMsg, clientNotification)
    }
  }

  override def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]] = {
    notificationLogger.debug(s"fetching clientNotification(s) with csid: ${csid.id.toString}")

    val selector = Json.obj("csid" -> csid.id)
    //TODO setting the value of timeReceived in MongoDB rather than passing from service appears to be a non-trivial task.
    val sortOrder = Json.obj("timeReceived" -> -1)
    collection.find(selector).sort(sortOrder).cursor().collect[List](Int.MaxValue, Cursor.FailOnError[List[ClientNotification]]())
  }

  override def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]] = {
    for {
      csids <- collection.distinct[ClientSubscriptionId, Set]("csid")
      lockedCsids <- lockRepo.currentLocks()
    } yield csids diff lockedCsids
  }

  override def delete(mongoObjectId: BSONObjectID): Future[Unit] = {
    notificationLogger.debug(s"deleting clientNotification with objectId: ${mongoObjectId.toString()}")

    val selector = Json.obj("_id" -> mongoObjectId)
    lazy val errorMsg = s"Could not delete entity for selector: $selector"
    collection.remove(selector).map(errorHandler.handleDeleteError(_, errorMsg))
  }

}
