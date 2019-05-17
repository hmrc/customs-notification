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

import java.time.Clock

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import org.joda.time.{DateTime, Duration}
import play.api.Configuration
import play.api.libs.functional.syntax.{unlift, _}
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONLong, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers._
import reactivemongo.play.json.JsObjectDocumentWriter
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientId, ClientSubscriptionId, CustomsNotificationConfig, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers.{ClockJodaExtensions, _}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.workitem._

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[NotificationWorkItemMongoRepo])
trait NotificationWorkItemRepo {

  def saveWithLock(notificationWorkItem: NotificationWorkItem, processingStatus: ProcessingStatus): Future[WorkItem[NotificationWorkItem]]

  def setCompletedStatus(id: BSONObjectID, status: ResultStatus): Future[Unit]

  def blockedCount(clientId: ClientId): Future[Int]

  def deleteBlocked(clientId: ClientId): Future[Int]

  def toPermanentlyFailedByCsId(csId: ClientSubscriptionId): Future[Int]

  def permanentlyFailedByCsIdExists(csId: ClientSubscriptionId): Future[Boolean]

  def distinctPermanentlyFailedByCsId(): Future[Set[ClientSubscriptionId]]

  def pullOutstandingWithPermanentlyFailedByCsId(csid: ClientSubscriptionId): Future[Option[WorkItem[NotificationWorkItem]]]

  def toFailedByCsId(csid: ClientSubscriptionId): Future[Int]
}

@Singleton
class NotificationWorkItemMongoRepo @Inject()(reactiveMongoComponent: ReactiveMongoComponent,
                                              clock: Clock, //TODO: use DateTime service
                                              customsNotificationConfig: CustomsNotificationConfig,
                                              logger: CdsLogger,
                                              configuration: Configuration)
                                             (implicit ec: ExecutionContext)
extends WorkItemRepository[NotificationWorkItem, BSONObjectID] (
        collectionName = "notifications-work-item",
        mongo = reactiveMongoComponent.mongoConnector.db,
        itemFormat = WorkItemFormat.workItemMongoFormat[NotificationWorkItem],
        configuration.underlying) with NotificationWorkItemRepo {

  override def workItemFields: WorkItemFieldNames = new WorkItemFieldNames {
    val receivedAt = "createdAt"
    val updatedAt = "lastUpdated"
    val availableAt = "availableAt"
    val status = "status"
    val id = "_id"
    val failureCount = "failures"
  }

  override def now: DateTime = clock.nowAsJoda

  override def inProgressRetryAfterProperty: String =
    ??? // we don't use this, we override inProgressRetryAfter instead

  override lazy val inProgressRetryAfter: Duration =
    customsNotificationConfig.pushNotificationConfig.retryInProgressRetryAfter.toJodaDuration

  override def indexes: Seq[Index] = super.indexes ++ Seq(
    Index(
      key = Seq("createdAt" -> IndexType.Descending),
      name = Some("createdAt-ttl-index"),
      unique = false,
      options = BSONDocument("expireAfterSeconds" -> BSONLong(customsNotificationConfig.pushNotificationConfig.ttlInSeconds.toLong))
    ),
    Index(
      key = Seq("clientNotification.clientId" -> IndexType.Descending),
      name = Some("clientNotification-clientId-index"),
      unique = false),
    Index(
      key = Seq("clientNotification.clientId" -> IndexType.Descending, workItemFields.status -> IndexType.Descending),
      name = Some(s"clientId-${workItemFields.status}-index"),
      unique = false),
    Index(
      key = Seq(workItemFields.status -> IndexType.Descending, workItemFields.updatedAt -> IndexType.Descending),
      name = Some(s"${workItemFields.status}-${workItemFields.updatedAt}-index"),
      unique = false),
    Index(
      key = Seq("clientNotification._id" -> IndexType.Descending, workItemFields.status -> IndexType.Descending),
      name = Some(s"csId-${workItemFields.status}-index"),
      unique = false)
  )

  def saveWithLock(notificationWorkItem: NotificationWorkItem, processingStatus: ProcessingStatus = InProgress): Future[WorkItem[NotificationWorkItem]] = {
    logger.debug(s"saving a new notification work item in locked state (${processingStatus.name}) $notificationWorkItem")

    def processWithInitialStatus(item: NotificationWorkItem): ProcessingStatus = processingStatus

    pushNew(notificationWorkItem, now, processWithInitialStatus _)
  }

  def setCompletedStatus(id: BSONObjectID, status: ResultStatus): Future[Unit] = {
    logger.debug(s"setting completed status of $status for notification work item id: ${id.stringify}")
    complete(id, status).map(_ => () )
  }

  override def blockedCount(clientId: ClientId): Future[Int] = {
    logger.debug(s"getting blocked count (i.e. those with status of ${PermanentlyFailed.name}) for clientId ${clientId.id}")
    val selector = Json.obj("clientNotification.clientId" -> clientId, workItemFields.status -> PermanentlyFailed)
    count(selector)
  }

  override def deleteBlocked(clientId: ClientId): Future[Int] = {
    logger.debug(s"deleting blocked flags (i.e. updating status of notifications from ${PermanentlyFailed.name} to ${Failed.name}) for clientId ${clientId.id}")
    val selector = Json.obj("clientNotification.clientId" -> clientId, workItemFields.status -> PermanentlyFailed)
    val update = Json.obj("$set" -> Json.obj(workItemFields.status -> Failed))
    collection.update(selector, update, multi = true).map {result =>
      logger.debug(s"deleted ${result.n} blocked flags (i.e. updating status of notifications from ${PermanentlyFailed.name} to ${Failed.name}) for clientId ${clientId.id}")
      result.n
    }
  }

  override def toPermanentlyFailedByCsId(csId: ClientSubscriptionId): Future[Int] = {
    import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeFormats

    logger.debug(s"setting all notifications with ${Failed.name} status to ${PermanentlyFailed.name} for clientSubscriptionId ${csId.id}")
    val selector = Json.obj("clientNotification._id" -> csId.id, workItemFields.status -> Failed)
    val update = Json.obj("$set" -> Json.obj(workItemFields.status -> PermanentlyFailed, workItemFields.updatedAt -> now))
    collection.update(selector, update, multi = true).map {result =>
      logger.debug(s"updated ${result.nModified} notifications with ${Failed.name} status to ${PermanentlyFailed.name} for clientSubscriptionId ${csId.id}")
      result.nModified
    }
  }

  override def toFailedByCsId(csid: ClientSubscriptionId): Future[Int] = {
    import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeFormats

    val selector = Json.obj("clientNotification._id" -> csid.id, workItemFields.status -> PermanentlyFailed)
    val update = Json.obj("$set" -> Json.obj(workItemFields.status -> Failed, workItemFields.updatedAt -> now))
    collection.update(selector, update, multi = true).map {result =>
      logger.debug(s"updated ${result.nModified} notifications with status equal to ${PermanentlyFailed.name} to ${Failed.name} for csid ${csid.id}")
      result.nModified
    }
  }

  override def permanentlyFailedByCsIdExists(csId: ClientSubscriptionId): Future[Boolean] = {
    val selector = Json.obj("clientNotification._id" -> csId.id,  workItemFields.status -> PermanentlyFailed)

    collection.find(selector, None)(JsObjectDocumentWriter, JsObjectDocumentWriter).one[JsValue].map { // No need for json deserialisation
      case Some(_) =>
        logger.info(s"Found existing permanently failed notification for client id: $csId")
        true
      case None => false
    }
  }

  override def distinctPermanentlyFailedByCsId(): Future[Set[ClientSubscriptionId]] = {
    collection.distinct[ClientSubscriptionId, Set]("clientNotification._id", None, mongo().connection.options.readConcern, None)
  }

  override def pullOutstandingWithPermanentlyFailedByCsId(csid: ClientSubscriptionId): Future[Option[WorkItem[NotificationWorkItem]]] = {
    import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeFormats

    val selector = Json.obj("clientNotification._id" -> csid.toString, workItemFields.status -> PermanentlyFailed)
    val update = Json.obj("$set" -> Json.obj(workItemFields.status -> InProgress, workItemFields.updatedAt -> now))
    collection.findAndUpdate(selector, update, fetchNewObject = true).map(_.result[WorkItem[NotificationWorkItem]])
  }
}

object WorkItemFormat {

  def workItemMongoFormat[T](implicit nFormat: Format[T]): Format[WorkItem[T]] =
    ReactiveMongoFormats.mongoEntity(
      notificationFormat(ReactiveMongoFormats.objectIdFormats,
        ReactiveMongoFormats.dateTimeFormats,
        nFormat))

  private def notificationFormat[T](implicit bsonIdFormat: Format[BSONObjectID],
                                    dateTimeFormat: Format[DateTime],
                                    nFormat: Format[T]): Format[WorkItem[T]] = {
    val reads = (
      (__ \ "id").read[BSONObjectID] and
        (__ \ "createdAt").read[DateTime] and
        (__ \ "lastUpdated").read[DateTime] and
        (__ \ "availableAt").read[DateTime] and
        (__ \ "status").read[uk.gov.hmrc.workitem.ProcessingStatus] and
        (__ \ "failures").read[Int].orElse(Reads.pure(0)) and
        (__ \ "clientNotification").read[T]
      )(WorkItem.apply[T](_, _, _, _, _, _, _))

    val writes = (
      (__ \ "id").write[BSONObjectID] and
        (__ \ "createdAt").write[DateTime] and
        (__ \ "lastUpdated").write[DateTime] and
        (__ \ "availableAt").write[DateTime] and
        (__ \ "status").write[uk.gov.hmrc.workitem.ProcessingStatus] and
        (__ \ "failures").write[Int] and
        (__ \ "clientNotification").write[T]
      )(unlift(WorkItem.unapply[T]))

    Format(reads, writes)
  }

}
