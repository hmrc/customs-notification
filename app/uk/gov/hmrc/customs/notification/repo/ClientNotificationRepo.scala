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
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import reactivemongo.play.json.JsObjectDocumentWriter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ClientNotificationRepo {

  def save(clientNotification: ClientNotification): Future[Boolean]
  //FIFO based on whatever we decide to use, this method  has to return the list in insertion order. for now, leaving the timestamp in there but it yet to be decided.
  // speak to Avinder & Paul to get more context
  def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]]

  def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]]

  //make sure we log it properly, we cant recover from delete failure, we might need to raise an alert for this one. We'll come back to this one.
  def delete(mongoObjectId: String): Future[Unit]
}

//TODO add logging
@Singleton
class ClientNotificationMongoRepo @Inject()(mongoDbProvider: MongoDbProvider,
                                            notificationLogger: NotificationLogger)
  extends ReactiveRepository[ClientNotification, BSONObjectID]("notifications", mongoDbProvider.mongo,
    ClientNotification.clientNotificationJF, ReactiveMongoFormats.objectIdFormats)
    with ClientNotificationRepo
    with ClientNotificationRepositoryErrorHandler{

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
      name = Some("clientId-timeReceived-Index"),
      unique = true
    )
  )

  override def save(clientNotification: ClientNotification): Future[Boolean] = {
    notificationLogger.debug(s"saving clientNotification: $clientNotification")

    lazy val errorMsg = s"Client Notification not saved for clientSubscriptionId ${clientNotification.csid}"

    collection.insert(clientNotification).map {
      writeResult => handleSaveError(writeResult, errorMsg, clientNotification)
    }
  }

  override def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]] = {
    val selector = Json.obj("csid" -> csid.id)
    collection.find(selector).cursor().collect[List](Int.MaxValue, Cursor.FailOnError[List[ClientNotification]]())
  }

  override def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]] = {
    //TODO add 'not locked' condition
//    collection.distinct("clientSubscriptionId")
    Future.successful(Set.empty)
  }

  override def delete(mongoObjectId: String): Future[Unit] = {
    val selector = Json.obj("_id" -> mongoObjectId)
    lazy val errorMsg = s"Could not delete entity for selector: $selector"
    collection.remove(selector).map(handleDeleteError(_, errorMsg))
  }

}
