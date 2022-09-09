/*
 * Copyright 2022 HM Revenue & Customs
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
import org.bson.UuidRepresentation
import org.bson.codecs.UuidCodec
import org.bson.types.ObjectId
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{and, equal, lt}
import org.mongodb.scala.model.Updates.{combine, inc, set}
import org.mongodb.scala.model.{FindOneAndUpdateOptions, ReturnDocument}
import play.api.Configuration
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientId, ClientSubscriptionId, CustomsNotificationConfig, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.repo.helpers.{NotificationWorkItemFields, NotificationWorkItemIndexes}
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{Failed, InProgress, PermanentlyFailed}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, ResultStatus, WorkItem, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Clock, Duration, Instant, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[NotificationWorkItemMongoRepo])
trait NotificationWorkItemRepo {

  def saveWithLock(notificationWorkItem: NotificationWorkItem, processingStatus: ProcessingStatus): Future[WorkItem[NotificationWorkItem]]

  def setCompletedStatus(id: ObjectId, status: ResultStatus): Future[Unit]

  def setCompletedStatusWithAvailableAt(id: ObjectId, status: ResultStatus, availableAt: ZonedDateTime): Future[Unit]

  def blockedCount(clientId: ClientId): Future[Int]

  def deleteBlocked(clientId: ClientId): Future[Int]

  def toPermanentlyFailedByCsId(csId: ClientSubscriptionId): Future[Int]

  def permanentlyFailedByCsIdExists(csId: ClientSubscriptionId): Future[Boolean]

  def distinctPermanentlyFailedByCsId(): Future[Set[ClientSubscriptionId]]

  def pullOutstandingWithPermanentlyFailedByCsId(csid: ClientSubscriptionId): Future[Option[WorkItem[NotificationWorkItem]]]

  def fromPermanentlyFailedToFailedByCsId(csid: ClientSubscriptionId): Future[Int]

  def incrementFailureCount(id: ObjectId): Future[Unit]

  def deleteAll(): Future[Unit]
}

@Singleton
class NotificationWorkItemMongoRepo @Inject()(mongo: MongoComponent,
                                              customsNotificationConfig: CustomsNotificationConfig,
                                              logger: CdsLogger,
                                              configuration: Configuration)
                                             (implicit ec: ExecutionContext)
extends WorkItemRepository[NotificationWorkItem] (
  collectionName = "notifications-work-item",
  mongoComponent = mongo,
  itemFormat = NotificationWorkItem.format,
  workItemFields = NotificationWorkItemFields.workItemFields
) with NotificationWorkItemRepo {

  val ttlInSeconds = customsNotificationConfig.notificationConfig.ttlInSeconds
  val notificationWorkItemIndexes = NotificationWorkItemIndexes.indexes(ttlInSeconds)

  override def now(): Instant = Instant.now()

  override lazy val inProgressRetryAfter: Duration = {
    java.time.Duration.ofNanos(customsNotificationConfig.notificationConfig.retryPollerInProgressRetryAfter.toNanos)
  }

  override def ensureIndexes: Future[Seq[String]] = {
    dropInvalidIndexes.flatMap { _ =>
      MongoUtils.ensureIndexes(collection, NotificationWorkItemIndexes.indexes(ttlInSeconds), true)
    }
  }

  def saveWithLock(notificationWorkItem: NotificationWorkItem, processingStatus: ProcessingStatus = InProgress): Future[WorkItem[NotificationWorkItem]] = {
    logger.debug(s"saving a new notification work item in locked state (${processingStatus.name}) $notificationWorkItem")

    def processWithInitialStatus(item: NotificationWorkItem): ProcessingStatus = processingStatus

    pushNew(notificationWorkItem, now(), processWithInitialStatus)
  }

  def setCompletedStatus(id: ObjectId, status: ResultStatus): Future[Unit] = {
    logger.debug(s"setting completed status of $status for notification work item id: ${id.toString}")
    complete(id, status).map(_ => () )
  }

  def setCompletedStatusWithAvailableAt(id: ObjectId, status: ResultStatus, availableAt: ZonedDateTime): Future[Unit] = {
    logger.debug(s"setting completed status of $status for notification work item id: ${id.toString} with availableAt: $availableAt")
    markAs(id, status, Some(availableAt.toInstant)).map(_ => () )
  }

  override def blockedCount(clientId: ClientId): Future[Int] = {
    logger.debug(s"getting blocked count (i.e. those with status of ${PermanentlyFailed.name}) for clientId ${clientId.id}")
    val selector = and(equal("clientNotification.clientId", Codecs.toBson(clientId)), equal(workItemFields.status, ProcessingStatus.toBson(PermanentlyFailed)))
    collection.countDocuments(selector).toFuture().map(_.toInt)
  }

  override def deleteBlocked(clientId: ClientId): Future[Int] = {
    logger.debug(s"deleting blocked flags (i.e. updating status of notifications from ${PermanentlyFailed.name} to ${Failed.name}) for clientId ${clientId.id}")
    val selector = and(
      equal("clientNotification.clientId", Codecs.toBson(clientId)),
      equal(workItemFields.status, ProcessingStatus.toBson(PermanentlyFailed))
    )
    val update = set(workItemFields.status, ProcessingStatus.toBson(Failed))

    collection.updateMany(selector, update).toFuture().map { result =>
      logger.debug(s"deleted ${result.getModifiedCount} blocked flags (i.e. updating status of notifications from ${PermanentlyFailed.name} to ${Failed.name}) for clientId ${clientId.id}")
      result.getModifiedCount.toInt
    }
  }

  override def toPermanentlyFailedByCsId(csid: ClientSubscriptionId): Future[Int] = {
    logger.debug(s"setting all notifications with ${Failed.name} status to ${PermanentlyFailed.name} for clientSubscriptionId ${csid.id}")
    val selector = csIdAndStatusSelector(csid, Failed)
    val update = updateStatusBson(PermanentlyFailed)
    collection.updateMany(selector, update).toFuture().map {result =>
      logger.debug(s"updated ${result.getModifiedCount} notifications with ${Failed.name} status to ${PermanentlyFailed.name} for clientSubscriptionId ${csid.id}")
      result.getModifiedCount.toInt
    }
  }

  override def fromPermanentlyFailedToFailedByCsId(csid: ClientSubscriptionId): Future[Int] = {
    val selector = csIdAndStatusSelector(csid, PermanentlyFailed)
    val update = updateStatusBson(Failed)
    collection.updateMany(selector, update).toFuture().map {result =>
      logger.debug(s"updated ${result.getModifiedCount} notifications with status equal to ${PermanentlyFailed.name} to ${Failed.name} for csid ${csid.id}")
      result.getModifiedCount.toInt
    }
  }

  override def permanentlyFailedByCsIdExists(csid: ClientSubscriptionId): Future[Boolean] = {
    val selector = csIdAndStatusSelector(csid, PermanentlyFailed)

    collection.find(selector).first().toFutureOption().map {
      case Some(_) =>
        logger.info(s"Found existing permanently failed notification for client id: $csid")
        true
      case None => false
    }
  }

  override def distinctPermanentlyFailedByCsId(): Future[Set[ClientSubscriptionId]] = {
    val selector = and(
      equal(workItemFields.status, ProcessingStatus.toBson(PermanentlyFailed)),
      lt("availableAt", now()))
    collection.distinct[String]("clientNotification.id", selector)
      .toFuture()
      .map(
        convertToClientSubscriptionIdSet(_)
      )
  }

  private def convertToClientSubscriptionIdSet(clientSubscriptionIds: Seq[String]): Set[ClientSubscriptionId] = {
    clientSubscriptionIds.map(id => ClientSubscriptionId(UUID.fromString(id))).toSet
  }

  override def pullOutstandingWithPermanentlyFailedByCsId(csid: ClientSubscriptionId): Future[Option[WorkItem[NotificationWorkItem]]] = {
    val selector = csIdAndStatusSelector(csid, PermanentlyFailed)
    val update = updateStatusBson(InProgress)
    collection.findOneAndUpdate(selector, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).upsert(false))
      .toFutureOption()
  }

  override def incrementFailureCount(id: ObjectId): Future[Unit] = {
    logger.debug(s"incrementing failure count for notification work item id: ${id.toString}")

    val selector = equal(workItemFields.id, id)
    val update = inc(workItemFields.failureCount, 1)

    collection.findOneAndUpdate(selector, update).toFuture().map(_ => ())
  }

  override def deleteAll(): Future[Unit] = {
    logger.debug(s"deleting all notifications")

    collection.deleteMany(BsonDocument()).toFuture().map { result =>
      logger.debug(s"deleted ${result.getDeletedCount} notifications")
    }
  }

  private def csIdAndStatusSelector(csid: ClientSubscriptionId, status: ProcessingStatus): Bson = {
    and(
      equal("clientNotification.id", csid.id.toString),
      equal(workItemFields.status, ProcessingStatus.toBson(status)),
      lt("availableAt", now()))
  }

  private def updateStatusBson(updStatus: ProcessingStatus): Bson = {
    combine(
      set(workItemFields.status, ProcessingStatus.toBson(updStatus)),
      set(workItemFields.updatedAt, now())
    )
  }

  private def dropInvalidIndexes: Future[_] = {
    val optInvalidIndex = indexes.find { index =>
      index.getOptions.getName == NotificationWorkItemIndexes.TTL_INDEX_NAME &&
        index.getOptions.getExpireAfter(TimeUnit.SECONDS) != ttlInSeconds
    }
    optInvalidIndex match {
      case Some(_) =>
        logger.debug(s"dropping ${NotificationWorkItemIndexes.TTL_INDEX_NAME} index as ttl value is incorrect")
        collection.dropIndex(NotificationWorkItemIndexes.TTL_INDEX_NAME).toFuture()
      case None => Future.successful(())
    }
  }
}
