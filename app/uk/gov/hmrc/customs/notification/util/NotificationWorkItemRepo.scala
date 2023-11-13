/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.util

import org.bson.types.ObjectId
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{and, equal, gte, lt}
import org.mongodb.scala.model.Indexes.{compoundIndex, descending}
import org.mongodb.scala.model.Updates.{combine, inc, set}
import org.mongodb.scala.model._
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.{ClientId, ClientSubscriptionId, CustomProcessingStatus, FailedAndBlocked, FailedButNotBlocked, NotificationWorkItem, SuccessfullyCommunicated}
import uk.gov.hmrc.customs.notification.util.NotificationWorkItemRepo.{MongoDbError, catchMongoDbException}
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.workitem.{ResultStatus, WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Duration, Instant, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationWorkItemRepo @Inject()(mongo: MongoComponent,
                                         customsNotificationConfig: CustomsNotificationConfig,
                                         logger: CdsLogger)(implicit ec: ExecutionContext) extends WorkItemRepository[NotificationWorkItem](
  collectionName = "notifications-work-item",
  mongoComponent = mongo,
  itemFormat = NotificationWorkItem.format,
  replaceIndexes = false,
  workItemFields = WorkItemFields(
    receivedAt = "createdAt",
    updatedAt = "lastUpdated",
    availableAt = "availableAt",
    status = "status",
    id = "_id",
    failureCount = "failures",
    item = "clientNotification")) {

  override def now(): Instant = Instant.now()

  private val mostRecentPushPullHttpStatusFieldName = s"${workItemFields.item}.notification.mostRecentPushPullHttpStatus"

  override val inProgressRetryAfter: Duration = {
    java.time.Duration.ofNanos(customsNotificationConfig.notificationConfig.retryPollerInProgressRetryAfter.toNanos)
  }

  override def ensureIndexes(): Future[Seq[String]] = {
    val ttlInSeconds = customsNotificationConfig.notificationConfig.ttlInSeconds
    val WORK_ITEM_STATUS = workItemFields.status
    val WORK_ITEM_UPDATED_AT = workItemFields.updatedAt
    val TTL_INDEX_NAME = "createdAt-ttl-index"
    lazy val notificationWorkItemIndexes = {
      indexes ++ Seq(
        IndexModel(
          keys = descending("createdAt"),
          indexOptions = IndexOptions()
            .name(TTL_INDEX_NAME)
            .unique(false)
            .expireAfter(ttlInSeconds, TimeUnit.SECONDS)
        ),
        IndexModel(
          keys = descending("clientNotification.clientId"),
          indexOptions = IndexOptions()
            .name("clientNotification-clientId-index")
            .unique(false)
        ),
        IndexModel(
          keys = compoundIndex(
            descending("clientNotification.clientId"),
            descending(WORK_ITEM_STATUS)
          ),
          indexOptions = IndexOptions()
            .name(s"clientId-$WORK_ITEM_STATUS-index")
            .unique(false)
        ),
        IndexModel(
          keys = compoundIndex(
            descending(WORK_ITEM_STATUS),
            descending(WORK_ITEM_UPDATED_AT)
          ),
          indexOptions = IndexOptions()
            .name(s"$WORK_ITEM_STATUS-$WORK_ITEM_UPDATED_AT-index")
            .unique(false)
        ),
        IndexModel(
          keys = compoundIndex(
            descending("clientNotification._id"),
            descending(WORK_ITEM_STATUS)
          ),
          indexOptions = IndexOptions()
            .name(s"csId-$WORK_ITEM_STATUS-index")
            .unique(false)
        ),
        IndexModel(
          keys = descending(mostRecentPushPullHttpStatusFieldName),
          indexOptions = IndexOptions()
            .name(mostRecentPushPullHttpStatusFieldName + "-5xx-permanentlyFailed-index")
            .partialFilterExpression(
              Filters.and(
                Filters.gte(mostRecentPushPullHttpStatusFieldName, 500),
                equal(workItemFields.status, FailedAndBlocked.convertToBson)))
            .unique(false)
        )
      )
    }

    MongoUtils.ensureIndexes(collection, notificationWorkItemIndexes, true)
  }

  def saveWithLock(notificationWorkItem: NotificationWorkItem,
                   processingStatus: CustomProcessingStatus): Future[Either[MongoDbError, WorkItem[NotificationWorkItem]]] =
    catchMongoDbException {
      val mongoWorkItem: WorkItem[NotificationWorkItem] = new WorkItem[NotificationWorkItem](
        id = new ObjectId(),
        receivedAt = now(),
        updatedAt = now(),
        availableAt = now(),
        status = processingStatus,
        failureCount = 0,
        item = notificationWorkItem)

      collection.insertOne(mongoWorkItem).toFuture().map(_ => mongoWorkItem)
    }

  def setCompletedStatus(mongoWorkItem: WorkItem[NotificationWorkItem], status: ResultStatus): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException {
      complete(mongoWorkItem.id, status).map(_ => ())
    }

  def setFailedAndBlocked(id: ObjectId, httpStatus: Int): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException {
      complete(id, FailedAndBlocked.convertToHmrcProcessingStatus).flatMap { updateSuccessful =>
        if (updateSuccessful) {
          setMostRecentPushPullHttpStatus(id, Some(httpStatus))
        } else {
          Future.successful(())
        }
      }
    }

  def setCompletedStatusWithAvailableAt(id: ObjectId, status: ResultStatus, httpStatus: Int, availableAt: ZonedDateTime): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException {
      logger.debug(s"setting completed status of $status for notification work item id: ${id.toString}" +
        s"with availableAt: $availableAt and mostRecentPushPullHttpStatus: $httpStatus")
      markAs(id, status, Some(availableAt.toInstant)).flatMap { updateSuccessful =>
        if (updateSuccessful) {
          setMostRecentPushPullHttpStatus(id, Some(httpStatus))
        } else {
          Future.successful(())
        }
      }
    }

  def blockedCount(clientId: ClientId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException {
      logger.debug(s"getting blocked count (i.e. those with status of ${FailedAndBlocked.name}) for clientId ${clientId.id}")
      val selector = and(equal("clientNotification.clientId", Codecs.toBson(clientId)), equal(workItemFields.status, FailedAndBlocked.convertToBson))
      collection.countDocuments(selector).toFuture().map(_.toInt)
    }

  def unblockFailedAndBlockedByClientId(clientId: ClientId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException {
      logger.debug(s"deleting blocked flags (i.e. updating status of notifications from ${FailedAndBlocked.name} to ${FailedButNotBlocked.name}) for clientId ${clientId.id}")
      val selector = and(
        equal("clientNotification.clientId", Codecs.toBson(clientId)),
        equal(workItemFields.status, FailedAndBlocked.convertToBson)
      )
      val update = set(workItemFields.status, FailedButNotBlocked.convertToBson)

      collection.updateMany(selector, update).toFuture().map { result =>
        logger.debug(s"deleted ${result.getModifiedCount} blocked flags (i.e. updating status of notifications from ${FailedAndBlocked.name} to ${FailedButNotBlocked.name}) for clientId ${clientId.id}")
        result.getModifiedCount.toInt
      }
    }

  def unblockFailedAndBlockedByCsId(csid: ClientSubscriptionId): Future[Int] = {
    val selector = csIdAndStatusSelector(csid, FailedAndBlocked)
    val update = updateStatusBson(FailedButNotBlocked)
    collection.updateMany(selector, update).toFuture().map(_.getModifiedCount.toInt)
  }

  def blockFailedButNotBlockedByCsId(csid: ClientSubscriptionId): Future[Int] = {
    logger.debug(s"setting all notifications with ${FailedButNotBlocked.name} status to ${FailedAndBlocked.name} for clientSubscriptionId ${csid.id}")
    val selector = csIdAndStatusSelector(csid, FailedButNotBlocked)
    val update = updateStatusBson(FailedAndBlocked)
    collection.updateMany(selector, update).toFuture().map { result =>
      logger.debug(s"updated ${result.getModifiedCount} notifications with ${FailedButNotBlocked.name} status to ${FailedAndBlocked.name} for clientSubscriptionId ${csid.id}")
      result.getModifiedCount.toInt
    }
  }

  def failedAndBlockedWithHttp5xxByCsIdExists(csid: ClientSubscriptionId): Future[Either[MongoDbError, Boolean]] =
    catchMongoDbException {
      val ServerErrorCodeMin = 500
      val selector = and(
        gte(mostRecentPushPullHttpStatusFieldName, ServerErrorCodeMin),
        csIdAndStatusSelector(csid, FailedAndBlocked)
      )

      collection.find(selector).first().toFutureOption().map {
        case Some(workItem) =>
          logger.info(s"Found existing permanently failed notification for client id: $csid " +
            s"with mostRecentPushPullHttpStatus: ${workItem.item.notification.mostRecentPushPullHttpStatus.getOrElse("None")}")
          true
        case None => false
      }
    }

  def failedAndBlockedGroupedByDistinctCsId(): Future[Either[MongoDbError, Set[ClientSubscriptionId]]] =
    catchMongoDbException {
      val selector = and(
        equal(workItemFields.status, FailedAndBlocked.convertToBson),
        lt("availableAt", now()))
      collection.distinct[String]("clientNotification._id", selector)
        .toFuture()
        .map(convertToClientSubscriptionIdSet)
    }

  def pullOutstandingWithFailedWith500ByCsId(csid: ClientSubscriptionId): Future[Either[MongoDbError, Option[WorkItem[NotificationWorkItem]]]] =
    catchMongoDbException {
      val selector = csIdAndStatusSelector(csid, FailedAndBlocked)
      val update = updateStatusBson(SuccessfullyCommunicated)
      collection.findOneAndUpdate(selector, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).upsert(false))
        .toFutureOption()
    }

  def incrementFailureCount(id: ObjectId): Future[Unit] = {
    logger.debug(s"incrementing failure count for notification work item id: ${id.toString}")

    val selector = equal(workItemFields.id, id)
    val update = inc(workItemFields.failureCount, 1)

    collection.findOneAndUpdate(selector, update).toFuture().map(_ => ())
  }

  def deleteAll(): Future[Unit] = {
    logger.debug(s"deleting all notifications")

    collection.deleteMany(BsonDocument()).toFuture().map { result =>
      logger.debug(s"deleted ${result.getDeletedCount} notifications")
    }
  }

  private def convertToClientSubscriptionIdSet(clientSubscriptionIds: Seq[String]): Set[ClientSubscriptionId] = {
    clientSubscriptionIds.map(id => ClientSubscriptionId(UUID.fromString(id))).toSet
  }

  private def setMostRecentPushPullHttpStatus(id: ObjectId, httpStatus: Option[Int]): Future[Unit] = {
    collection.updateOne(
      filter = Filters.equal(workItemFields.id, id),
      update = Updates.combine(
        Updates.set(workItemFields.updatedAt, now()),
        httpStatus.fold(BsonDocument(): Bson)(Updates.set("clientNotification.notification.mostRecentPushPullHttpStatus", _))
      )
    ).toFuture().map(_ => ())
  }

  private def csIdAndStatusSelector(csid: ClientSubscriptionId, status: CustomProcessingStatus): Bson = {
    and(
      equal("clientNotification._id", csid.id.toString),
      equal(workItemFields.status, status.convertToBson),
      lt("availableAt", now()))
  }

  private def updateStatusBson(updatedStatus: CustomProcessingStatus): Bson = {
    combine(
      set(workItemFields.status, updatedStatus.convertToBson),
      set(workItemFields.updatedAt, now())
    )
  }
}

object NotificationWorkItemRepo {
  case class MongoDbError(cause: Throwable) extends CdsError

  def catchMongoDbException[A](block: => Future[A])(implicit ec: ExecutionContext): Future[Either[MongoDbError, A]] = {
    block.map(Right(_)).recover {
      case t: Throwable => Left(MongoDbError(t))
    }
  }
}