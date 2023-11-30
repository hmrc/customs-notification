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

import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{and, equal, gte, lte}
import org.mongodb.scala.model.Indexes.{compoundIndex, descending}
import org.mongodb.scala.model.Updates.{combine, inc, set}
import org.mongodb.scala.model._
import play.api.http.MimeTypes
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json._
import uk.gov.hmrc.customs.notification.config.RepoConfig
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits._
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedAndBlocked, FailedButNotBlocked, SavedToBeSent, Succeeded}
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.repo.Repository.Dto._
import uk.gov.hmrc.customs.notification.repo.Repository._
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.customs.notification.util.Helpers.ignore
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJodaFormats}
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Duration, Instant, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters.ScalaDurationOps

@Singleton
class Repository @Inject()(mongo: MongoComponent,
                           dateTimeService: DateTimeService,
                           config: RepoConfig)
                          (implicit logger: Logger,
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

  private lazy val mostRecentPushPullHttpStatusFieldName = s"${workItemFields.item}.notification.mostRecentPushPullHttpStatus"

  override val inProgressRetryAfter: Duration = config.inProgressRetryDelay.toJava

  def insert(domainEntity: Notification,
             processingStatus: ProcessingStatus): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException("saving notification", domainEntity) {
      val mongoWorkItem: WorkItem[NotificationWorkItem] = domainToRepo(domainEntity, processingStatus, None, now())
      collection.insertOne(mongoWorkItem).toFuture().map(ignore)
    }

  def setSucceeded(id: ObjectId): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"setting notification to Succeeded", id) {
      complete(id, Succeeded.legacyStatus).map(_ => ())
    }

  def setFailedAndBlocked(id: ObjectId, httpStatus: Int): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"setting FailedAndBlocked for notification", id) {
      complete(id, FailedAndBlocked.legacyStatus).flatMap { updateSuccessful =>
        if (updateSuccessful) {
          setMostRecentPushPullHttpStatus(id, Some(httpStatus))
        } else {
          Future.successful(())
        }
      }
    }

  def setFailedButNotBlocked(id: ObjectId, maybeHttpStatus: Option[Int], availableAt: ZonedDateTime): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"setting FailedButNotBlocked for notification", id) {
      markAs(id, FailedButNotBlocked.legacyStatus, Some(availableAt.toInstant)).flatMap { updateSuccessful =>
        if (updateSuccessful) {
          setMostRecentPushPullHttpStatus(id, maybeHttpStatus)
        } else {
          Future.successful(())
        }
      }
    }

  def getSingleOutstanding(): Future[Either[MongoDbError, Option[Notification]]] =
    catchMongoDbException("getting an outstanding notification to retry", ()) {
      pullOutstanding(now(), now()).map(_.map(repoToDomain))
    }

  def getFailedAndBlockedCount(clientId: ClientId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("getting count of FailedAndBlocked notifications for client ID", clientId) {
      val selector = and(equal("clientNotification.clientId", Codecs.toBson(clientId)), equal(workItemFields.status, FailedAndBlocked.toBson))
      collection.countDocuments(selector).toFuture().map(_.toInt)
    }

  def unblockFailedAndBlocked(clientId: ClientId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("setting all FailedAndBlocked notifications to FailedButNotBlocked for client ID", clientId) {
      val selector = and(
        equal("clientNotification.clientId", Codecs.toBson(clientId)),
        equal(workItemFields.status, FailedAndBlocked.toBson)
      )
      val update = set(workItemFields.status, FailedButNotBlocked.toBson)

      collection.updateMany(selector, update).toFuture().map { result =>
        result.getModifiedCount.toInt
      }
    }

  def unblockFailedAndBlocked(csid: ClientSubscriptionId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("setting all FailedAndBlocked notifications to FailedButNotBlocked for client subscription ID", csid) {
      val selector = getAvailable(csid, FailedAndBlocked)
      val update = updateStatusBson(FailedButNotBlocked)
      collection.updateMany(selector, update).toFuture().map(_.getModifiedCount.toInt)
    }

  def blockAllFailedButNotBlocked(csid: ClientSubscriptionId): Future[Either[MongoDbError, Int]] =
    catchMongoDbException("setting all FailedButNotBlocked notifications for client subscription ID to FailedAndBlocked", csid) {
      val selector = getAvailable(csid, FailedButNotBlocked)
      val update = updateStatusBson(FailedAndBlocked)
      collection.updateMany(selector, update).toFuture().map { result =>
        result.getModifiedCount.toInt
      }
    }

  def checkFailedAndBlockedExist(csid: ClientSubscriptionId): Future[Either[MongoDbError, Boolean]] =
    catchMongoDbException(s"checking if FailedAndBlocked notifications exist", csid) {
      val selector = and(
        gte(mostRecentPushPullHttpStatusFieldName, INTERNAL_SERVER_ERROR),
        getAvailable(csid, FailedAndBlocked)
      )

      collection.find(selector).first().toFutureOption().map(_.isDefined)
    }

  def getSingleOutstandingFailedAndBlockedForEachCsId(): Future[Either[MongoDbError, Set[ClientSubscriptionId]]] =
    catchMongoDbException("getting a FailedAndBlocked notification for each distinct client subscription ID", ()) {
      val selector = and(
        equal(workItemFields.status, FailedAndBlocked.toBson),
        lte("availableAt", now()))
      collection.distinct[String]("clientNotification._id", selector)
        .toFuture()
        .map(_.map(id => ClientSubscriptionId(UUID.fromString(id))).toSet)
    }

  def getSingleOutstandingFailedAndBlocked(csid: ClientSubscriptionId): Future[Either[MongoDbError, Option[Notification]]] =
    catchMongoDbException(s"getting a FailedAndBlocked notification", csid) {
      val selector = getAvailable(csid, FailedAndBlocked)
      val update = updateStatusBson(SavedToBeSent)
      collection.findOneAndUpdate(selector, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).upsert(false))
        .toFutureOption().map(_.map(repoToDomain))
    }

  def incrementFailureCount(id: ObjectId): Future[Either[MongoDbError, Unit]] =
    catchMongoDbException(s"incrementing failure count", id) {
      val selector = equal(workItemFields.id, id)
      val update = inc(workItemFields.failureCount, 1)

      collection.findOneAndUpdate(selector, update).toFuture().map(_ => ())
    }

  private def setMostRecentPushPullHttpStatus(id: ObjectId, httpStatus: Option[Int])(implicit ec: ExecutionContext): Future[Unit] = {
    collection.updateOne(
      filter = Filters.equal(workItemFields.id, id),
      update = Updates.combine(
        Updates.set(workItemFields.updatedAt, now()),
        httpStatus.fold(BsonDocument(): Bson)(Updates.set("clientNotification.notification.mostRecentPushPullHttpStatus", _))
      )
    ).toFuture().map(_ => ())
  }

  private def getAvailable(csid: ClientSubscriptionId, status: ProcessingStatus): Bson = {
    and(
      equal("clientNotification._id", csid.id.toString),
      equal(workItemFields.status, status.toBson),
      lte("availableAt", now()))
  }

  private def updateStatusBson(updatedStatus: ProcessingStatus): Bson = {
    combine(
      set(workItemFields.status, updatedStatus.toBson),
      set(workItemFields.updatedAt, now())
    )
  }

  //scalastyle:off method.length
  override def ensureIndexes(): Future[Seq[String]] = {
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
            .expireAfter(config.notificationTtl.toSeconds, TimeUnit.SECONDS)
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
                equal(workItemFields.status, FailedAndBlocked.toBson)))
            .unique(false)
        )
      )
    }
    MongoUtils.ensureIndexes(collection, notificationWorkItemIndexes, replaceIndexes = true)
  }
}

object Repository {
  case class MongoDbError(doingWhat: String, cause: Throwable) {
    val message: String = s"MongoDb error while $doingWhat. Exception: ${cause.getMessage}"
  }

  def catchMongoDbException[A, L: Loggable](doingWhat: String, toLog: L, shouldLog: Boolean = true)(block: => Future[A])
                                           (implicit logger: Logger,
                                            ec: ExecutionContext): Future[Either[MongoDbError, A]] = {
    block.map(Right(_)).recover {
      case t: Throwable =>
        val e = MongoDbError(doingWhat, t)
        if (shouldLog) logger.error(e.message, toLog)
        Left(e)
    }
  }

  def domainToRepo(notification: Notification,
                   status: ProcessingStatus,
                   mostRecentPushPullHttpStatus: Option[Int],
                   availableAt: Instant): WorkItem[NotificationWorkItem] = {
    val n = notification
    val notificationWorkItem = {
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
    }
    val receivedAt, updatedAt = availableAt

    WorkItem[NotificationWorkItem](
      id = n.id,
      receivedAt = receivedAt,
      updatedAt = updatedAt,
      availableAt = availableAt,
      status = status.legacyStatus,
      failureCount = 0,
      item = notificationWorkItem)
  }

  def repoToDomain(w: WorkItem[NotificationWorkItem]): Notification =
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