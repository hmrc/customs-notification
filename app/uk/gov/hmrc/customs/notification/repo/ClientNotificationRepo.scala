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

package uk.gov.hmrc.customs.notification.repo

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsNumber, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix
import uk.gov.hmrc.mongo.ReactiveRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@ImplementedBy(classOf[ClientNotificationMongoRepo])
trait ClientNotificationRepo {

  def save(clientNotification: ClientNotification): Future[Boolean]

  def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]]

  def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]]

  def delete(clientNotification: ClientNotification): Future[Unit]

  def failedPushNotificationsExist(): Future[Boolean]
}

@Singleton
class ClientNotificationMongoRepo @Inject()(configService: CustomsNotificationConfig,
                                            reactiveMongoComponent: ReactiveMongoComponent,
                                            lockRepo: LockRepo,
                                            errorHandler: ClientNotificationRepositoryErrorHandler,
                                            logger: CdsLogger)
  extends ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = reactiveMongoComponent.mongoConnector.db,
    domainFormat = ClientNotification.clientNotificationJF
  ) with ClientNotificationRepo {

  private implicit val format = ClientNotification.clientNotificationJF

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

  override def save(clientNotification: ClientNotification): Future[Boolean] = {
    logger.debug(s"${logMsgPrefix(clientNotification)} saving clientNotification: $clientNotification")

    lazy val errorMsg = s"Client Notification not saved for clientSubscriptionId ${clientNotification.csid}"

    val selector = Json.obj("_id" -> clientNotification.id)
    val update = Json.obj("$currentDate" -> Json.obj("timeReceived" -> true), "$set" -> clientNotification)
    findAndUpdate(selector, update, upsert = true).map { result =>
        errorHandler.handleUpdateError(result, errorMsg, clientNotification)
    }
  }

  override def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]] = {
    logger.debug(s"fetching clientNotification(s) with csid: ${csid.id.toString} and with max records=${configService.pushNotificationConfig.maxRecordsToFetch}")

    val selector = Json.obj("csid" -> csid.id)
    val sortOrder = Json.obj("timeReceived" -> 1)
    collection.find(selector, Option.empty).sort(sortOrder).cursor().collect[List](maxDocs = configService.pushNotificationConfig.maxRecordsToFetch, Cursor.FailOnError[List[ClientNotification]]())
  }

  override def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]] = {
    logger.debug("fetching Distinct Notification CSIDs Which are not locked")
    for {
      csIds <- fetchDistinctCsIdsFromSavedNotifications
      lockedCsIds <- fetchCsIdsThatHaveLocks
    } yield csIds diff lockedCsIds
  }

  private def fetchCsIdsThatHaveLocks = {
    val lockedCSIds = lockRepo.lockedCSIds()
    lockedCSIds.map(lockedCSIdSet => logger.debug(s"fetching CSIDs That are locked ${lockedCSIdSet.size}"))
    lockedCSIds
  }

  private def fetchDistinctCsIdsFromSavedNotifications = {
    val csIds = collection.distinct[ClientSubscriptionId, Set]("csid", None, mongo().connection.options.readConcern, None)
    csIds.map(cdIdsSet => logger.debug(s"fetching Distinct CSIDs from Saved Notification count is ${cdIdsSet.size}"))
    csIds
  }

  override def delete(clientNotification: ClientNotification): Future[Unit] = {
    logger.debug(s"${logMsgPrefix(clientNotification)} deleting clientNotification with objectId: ${clientNotification.id.stringify}")

    lazy val errorMsg = s"Could not delete clientNotificationId: ${clientNotification.id}"
    remove("_id" -> clientNotification.id).map(errorHandler.handleDeleteError(_, errorMsg))
  }

  override def failedPushNotificationsExist(): Future[Boolean] = {

    val millisAgo = configService.pullExcludeConfig.notificationsOlderMillis + configService.pushNotificationConfig.pollingDelay.toMillis.toInt
    val csIds = configService.pullExcludeConfig.csIdsToExclude
    val olderThan = DateTime.now(DateTimeZone.UTC).minusMillis(millisAgo)

    logger.debug(s"finding clientSubscriptionIds $csIds older than $olderThan")
    val selector = Json.obj("csid" -> Json.obj("$in" -> csIds), "timeReceived" -> Json.obj("$lt" -> Json.obj("$date" -> JsNumber(olderThan.getMillis))))

    collection.find(selector, None).one[ClientNotification].map {
      case Some(_) => true
      case None => false
    }

  }
}
