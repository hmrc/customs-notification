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

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import reactivemongo.api.Cursor
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.ReactiveRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@ImplementedBy(classOf[ClientNotificationMongoRepo])
trait ClientNotificationRepo {

  def save(clientNotification: ClientNotification): Future[Boolean]

  def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]]

  def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]]

  def delete(clientNotification: ClientNotification): Future[Unit]
}

@Singleton
class ClientNotificationMongoRepo @Inject()(mongoDbProvider: MongoDbProvider,
                                            lockRepo: LockRepo,
                                            notificationLogger: NotificationLogger)
  extends ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = mongoDbProvider.mongo,
    domainFormat = ClientNotification.clientNotificationJF
  ) with ClientNotificationRepo {

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
      unique = false
    )
  )

  //TODO MC revisit
  override def save(clientNotification: ClientNotification): Future[Boolean] = {
    notificationLogger.debug(s"${logMsgPrefix(clientNotification)} saving clientNotification: $clientNotification")
    lazy val exceptionMsg = s"Client Notification not saved for clientSubscriptionId ${clientNotification.csid}"

    val selector = Json.obj("_id" -> clientNotification.id)
    val update = Json.obj("$currentDate" -> Json.obj("timeReceived" -> true), "$set" -> clientNotification)
    collection.update(selector, update, upsert = true).map {
      case wr: WriteResult if wr.writeConcernError.isEmpty && databaseAltered(wr) =>
        true
      case wr: WriteResult =>
        val errMsg = wr.writeConcernError.fold(".") { a => s" $a" }
        val message = s"$exceptionMsg$errMsg"
        notificationLogger.error(message)
        false
    }
  }

  override def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]] = {
    notificationLogger.debug(s"fetching clientNotification(s) with csid: ${csid.id.toString}")

    val selector = Json.obj("csid" -> csid.id)
    val sortOrder = Json.obj("timeReceived" -> 1)
    collection.find(selector).sort(sortOrder).cursor().collect[List](Int.MaxValue, Cursor.FailOnError[List[ClientNotification]]())
  }

  override def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]] = {
    for {
      csids <- collection.distinct[ClientSubscriptionId, Set]("csid")
      lockedCsids <- lockRepo.lockedCSIds()
    } yield csids diff lockedCsids
  }

  override def delete(clientNotification: ClientNotification): Future[Unit] = {
    notificationLogger.debug(s"${logMsgPrefix(clientNotification)} deleting clientNotification with objectId: ${clientNotification.id.stringify}")

    val selector = Json.obj("_id" -> clientNotification.id)
    lazy val errorMsg = s"Could not delete entity for selector: $selector"
    collection.remove(selector).map { a =>
      val maybeErrorMessage = maybeGetErrorMessage(a, errorMsg)
      maybeErrorMessage.fold(Future.successful(())) { msg =>
        notificationLogger.error(msg)
        Future.failed(new RuntimeException(msg)) }
    }
  }

  private def maybeGetErrorMessage(result: WriteResult, exceptionMsg: => String): Option[String] = {
    result.writeConcernError.fold(if (!databaseAltered(result)) Some(exceptionMsg) else None) {
      errMsg =>
        Some(s"$exceptionMsg. $errMsg")
    }
  }

  private def databaseAltered(writeResult: WriteResult): Boolean = writeResult.n > 0

}
