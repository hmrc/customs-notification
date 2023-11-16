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

package uk.gov.hmrc.customs.notification.repo

import uk.gov.hmrc.customs.notification.models.Loggable.Implicits._
import uk.gov.hmrc.customs.notification.repo.NotificationRepo.Dto._
import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJodaFormats}
import org.bson.types.ObjectId
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{and, equal, gte, lt}
import org.mongodb.scala.model.Indexes.{compoundIndex, descending}
import org.mongodb.scala.model.Updates.{combine, inc, set}
import org.mongodb.scala.model._
import play.api.http.MimeTypes
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.repo.NotificationRepo._
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.workitem.{ResultStatus, WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Duration, Instant, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationRepo @Inject()(mongo: MongoComponent,
                                 customsNotificationConfig: CustomsNotificationConfig)
                                (implicit logger: NotificationLogger,
                                 dateTimeService: DateTimeService,
                                 ec: ExecutionContext) extends WorkItemRepository[NotificationWorkItem](
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

  override def now(): Instant = dateTimeService.now().toInstant

  private val mostRecentPushPullHttpStatusFieldName = s"${workItemFields.item}.notification.mostRecentPushPullHttpStatus"

  override val inProgressRetryAfter: Duration = {
    java.time.Duration.ofNanos(customsNotificationConfig.notificationConfig.retryPollerInProgressRetryAfter.toNanos)
  }

  def saveWithLock(domainEntity: Notification,
                   processingStatus: CustomProcessingStatus): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException("saving notification", domainEntity) {
      val mongoWorkItem: WorkItem[NotificationWorkItem] = domainToRepo(domainEntity, processingStatus, None)
      collection.insertOne(mongoWorkItem).toFuture().map(_ => mongoWorkItem)
    }

  def setStatus(id: ObjectId, status: ResultStatus): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"setting notification", id) {
      complete(id, status).map(_ => ())
    }

  def setFailedAndBlocked(id: ObjectId, httpStatus: Int): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"setting failed and blocked for notification", id) {
      complete(id, FailedAndBlocked.convertToHmrcProcessingStatus).flatMap { updateSuccessful =>
        if (updateSuccessful) {
          setMostRecentPushPullHttpStatus(id, Some(httpStatus))
        } else {
          Future.successful(())
        }
      }
    }

  def setFailedButNotBlocked(id: ObjectId, httpStatus: Int, availableAt: ZonedDateTime): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"setting failed but not blocked for notification", id) {
      markAs(id, FailedButNotBlocked.convertToHmrcProcessingStatus, Some(availableAt.toInstant)).flatMap { updateSuccessful =>
        if (updateSuccessful) {
          setMostRecentPushPullHttpStatus(id, Some(httpStatus))
        } else {
          Future.successful(())
        }
      }
    }

  def blockedCount(clientId: ClientId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("getting blocked count", clientId) {
      val selector = and(equal("clientNotification.clientId", Codecs.toBson(clientId)), equal(workItemFields.status, FailedAndBlocked.convertToBson))
      collection.countDocuments(selector).toFuture().map(_.toInt)
    }

  def unblockFailedAndBlocked(clientId: ClientId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("setting failed and blocked notifications to failed but not blocked", clientId) {
      val selector = and(
        equal("clientNotification.clientId", Codecs.toBson(clientId)),
        equal(workItemFields.status, FailedAndBlocked.convertToBson)
      )
      val update = set(workItemFields.status, FailedButNotBlocked.convertToBson)

      collection.updateMany(selector, update).toFuture().map { result =>
        result.getModifiedCount.toInt
      }
    }

  def unblockFailedAndBlocked(csid: ClientSubscriptionId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("setting failed and blocked notifications to failed but not blocked", csid) {
      val selector = csIdAndStatusSelector(csid, FailedAndBlocked)
      val update = updateStatusBson(FailedButNotBlocked)
      collection.updateMany(selector, update).toFuture().map(_.getModifiedCount.toInt)
    }

  def blockFailedButNotBlocked(csid: ClientSubscriptionId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("setting all failed but not blocked notifications for client subscription ID to blocked", csid) {
      val selector = csIdAndStatusSelector(csid, FailedButNotBlocked)
      val update = updateStatusBson(FailedAndBlocked)
      collection.updateMany(selector, update).toFuture().map { result =>
        result.getModifiedCount.toInt
      }
    }

  def checkFailedAndBlockedExist(csid: ClientSubscriptionId): Future[Either[MongoDbError, Boolean]] =
    catchMongoDbException(s"checking if failed and blocked notifications exist", csid) {
      val selector = and(
        gte(mostRecentPushPullHttpStatusFieldName, INTERNAL_SERVER_ERROR),
        csIdAndStatusSelector(csid, FailedAndBlocked)
      )

      collection.find(selector).first().toFutureOption().map(_.isDefined)
    }

  def getSingleOutstandingFailedAndBlockedForEachCsId(): Future[Either[MongoDbError, Set[ClientSubscriptionId]]] =
    catchMongoDbException("getting a failed and blocked notification for each distinct client subscription ID", ()) {
      val selector = and(
        equal(workItemFields.status, FailedAndBlocked.convertToBson),
        lt("availableAt", now()))
      collection.distinct[String]("clientNotification._id", selector)
        .toFuture()
        .map(convertToClientSubscriptionIdSet)
    }

  def getOutstandingFailedAndBlocked(csid: ClientSubscriptionId): Future[Either[MongoDbError, Option[Notification]]] =
    catchMongoDbException(s"getting a failed and blocked notification", csid) {
      val selector = csIdAndStatusSelector(csid, FailedAndBlocked)
      val update = updateStatusBson(SavedToBeSent)
      collection.findOneAndUpdate(selector, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).upsert(false))
        .toFutureOption().map(_.map(repoToDomain))
    }

  def incrementFailureCount(id: ObjectId): Future[Unit] = {
    val selector = equal(workItemFields.id, id)
    val update = inc(workItemFields.failureCount, 1)

    collection.findOneAndUpdate(selector, update).toFuture().map(_ => ())
  }

  def deleteAll(): Future[Unit] = {
    collection.deleteMany(BsonDocument()).toFuture().map(_ => ())
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

  //scalastyle:off method.length
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
                Filters.gte(mostRecentPushPullHttpStatusFieldName, INTERNAL_SERVER_ERROR),
                equal(workItemFields.status, FailedAndBlocked.convertToBson)))
            .unique(false)
        )
      )
    }
    MongoUtils.ensureIndexes(collection, notificationWorkItemIndexes, replaceIndexes = true)
  }
}

object NotificationRepo {
  case class MongoDbError(doingWhat: String, cause: Throwable) extends CdsError {
    val message: String = s"MongoDb error while $doingWhat. Exception: $cause"
  }

  def catchMongoDbException[A](doingWhat: String)(block: => Future[A])
                              (implicit logger: NotificationLogger,
                               ec: ExecutionContext): Future[Either[MongoDbError, A]] =
    catchMongoDbException(doingWhat, (), shouldLog = false)(block)

  def catchMongoDbException[A, L](doingWhat: String, toLog: L, shouldLog: Boolean = true)(block: => Future[A])
                                 (implicit logger: NotificationLogger,
                                  ev: Loggable[L],
                                  ec: ExecutionContext): Future[Either[MongoDbError, A]] = {
    block.map(Right(_)).recover {
      case t: Throwable =>
        val e = MongoDbError(doingWhat, t)
        if (shouldLog) logger.error(e.message, toLog)
        Left(e)
    }
  }

  private def domainToRepo(n: Notification,
                           status: CustomProcessingStatus,
                           mostRecentPushPullHttpStatus: Option[Int])
                          (implicit dateTimeService: DateTimeService): WorkItem[NotificationWorkItem] = {
    val now = dateTimeService.now().toInstant
    val notificationWorkItem =
      NotificationWorkItem(
        n.clientSubscriptionId,
        n.clientId,
        n.metricsStartDateTime,
        NotificationWorkItemBody(
          n.notificationId,
          n.conversationId,
          n.headers,
          n.payload,
          MimeTypes.XML,
          mostRecentPushPullHttpStatus)
      )

    WorkItem[NotificationWorkItem](
      id = n.id,
      receivedAt = now,
      updatedAt = now,
      availableAt = now,
      status = status.convertToHmrcProcessingStatus,
      failureCount = 0,
      item = notificationWorkItem)
  }

  private def repoToDomain(w: WorkItem[NotificationWorkItem]): Notification =
    Notification(
      w.id,
      w.item._id,
      w.item.clientId,
      w.item.notification.notificationId,
      w.item.notification.conversationId,
      w.item.notification.headers,
      w.item.notification.payload,
      w.item.metricsStartDateTime
    )

  object Dto {
    case class NotificationWorkItemBody(notificationId: NotificationId,
                                        conversationId: ConversationId,
                                        headers: Seq[Header],
                                        payload: String,
                                        contentType: String,
                                        mostRecentPushPullHttpStatus: Option[Int] = None)

    object NotificationWorkItemBody {
      implicit val notificationJF: Format[NotificationWorkItemBody] = Json.format[NotificationWorkItemBody]
    }

    case class NotificationWorkItem(_id: ClientSubscriptionId,
                                    clientId: ClientId,
                                    metricsStartDateTime: ZonedDateTime,
                                    notification: NotificationWorkItemBody)

    object NotificationWorkItem {
      implicit val dateFormats: Format[DateTime] = MongoJodaFormats.dateTimeFormat
      implicit val objectIdFormats: Format[ObjectId] = MongoFormats.objectIdFormat
      implicit val format: OFormat[NotificationWorkItem] = Json.format[NotificationWorkItem]
    }
  }
}