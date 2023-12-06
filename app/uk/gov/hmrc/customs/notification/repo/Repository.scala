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
import org.mongodb.scala.Observable
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{compoundIndex, descending}
import org.mongodb.scala.model.Updates.{combine, set}
import org.mongodb.scala.model._
import play.api.http.MimeTypes
import play.api.libs.json._
import uk.gov.hmrc.customs.notification.config.RepoConfig
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedAndBlocked, FailedButNotBlocked, SavedToBeSent, Succeeded}
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.repo.Repository.Dto._
import uk.gov.hmrc.customs.notification.repo.Repository._
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.customs.notification.util.Helpers.ignore
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJodaFormats}
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.mongo.{MongoComponent, MongoUtils}

import java.time.{Duration, Instant, ZonedDateTime}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.xml.XML

@Singleton
class Repository @Inject()(mongo: MongoComponent,
                           dateTimeService: DateTimeService,
                           config: RepoConfig)
                          (implicit ec: ExecutionContext)
  extends WorkItemRepository[NotificationWorkItem](
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
      item = "clientNotification")) with Logger {

  override def now(): Instant = dateTimeService.now().toInstant

  override val inProgressRetryAfter: Duration = config.inProgressRetryDelay.toJava

  def insert(domainEntity: Notification,
             processingStatus: ProcessingStatus)
            (implicit lc: LogContext): Future[Either[MongoDbError, Unit]] =
    catchExceptions("saving notification") {
      val mongoWorkItem: WorkItem[NotificationWorkItem] = domainToRepo(domainEntity, processingStatus, now())
      collection.insertOne(mongoWorkItem).toFuture().map(ignore)
    }

  def setSucceeded(id: ObjectId)(implicit lc: LogContext): Future[Either[MongoDbError, Unit]] =
    catchExceptions(s"setting notification to Succeeded") {
      markAs(id, Succeeded.legacyStatus).map(ignore)
    }

  def setFailedAndBlocked(id: ObjectId, availableAt: ZonedDateTime)(implicit lc: LogContext): Future[Either[MongoDbError, Unit]] =
    catchExceptions("setting FailedAndBlocked for notification") {
      collection.updateOne(
        filter = Filters.equal(workItemFields.id, id),
        update = Updates.combine(
          Updates.set(workItemFields.status, FailedAndBlocked.toBson),
          Updates.set(workItemFields.updatedAt, now()),
          Updates.set(workItemFields.availableAt, availableAt.toInstant),
          Updates.inc(workItemFields.failureCount, 1)
        )
      ).toFuture()
        .map(_.getModifiedCount match {
          case 1 =>
            Right(())
          case _ =>
            throw new IllegalStateException("Notification doesn't exist")
        })
    }

  def setFailedButNotBlocked(id: ObjectId,
                             availableAt: ZonedDateTime)
                            (implicit lc: LogContext): Future[Either[MongoDbError, Unit]] =
    catchExceptions(s"setting FailedButNotBlocked for notification") {
      markAs(id, FailedButNotBlocked.legacyStatus, Some(availableAt.toInstant))
        .map { hasUpdated =>
          if (hasUpdated) {
            Right(())
          } else {
            throw new IllegalStateException("Notification doesn't exist")
          }
        }
    }

  def getFailedAndBlockedCount(clientId: ClientId)(implicit lc: LogContext): Future[Either[MongoDbError, Int]] =
    catchExceptions(s"getting count of FailedAndBlocked notifications for client ID [$clientId]") {
      val selector = and(
        equalToClientId(clientId),
        equalToStatus(FailedAndBlocked)
      )
      collection.countDocuments(selector).toFuture().map(_.toInt)
    }

  def unblockFailedAndBlocked(clientId: ClientId)(implicit lc: LogContext): Future[Either[MongoDbError, Int]] =
    catchExceptions(s"setting all FailedAndBlocked notifications to FailedButNotBlocked for client ID [$clientId]") {
      val selector = and(
        equalToClientId(clientId),
        equalToStatus(FailedAndBlocked)
      )
      val update = set(workItemFields.status, FailedButNotBlocked.toBson)

      collection.updateMany(selector, update).toFuture().map { result =>
        result.getModifiedCount.toInt
      }
    }

  def unblockFailedAndBlocked(csid: ClientSubscriptionId)(implicit lc: LogContext): Future[Either[MongoDbError, Int]] =
    catchExceptions("setting all FailedAndBlocked notifications to FailedButNotBlocked for client subscription ID") {
      val selector = and(
        equalToCsid(csid),
        equalToStatus(FailedAndBlocked))
      val update = updateStatusBson(FailedButNotBlocked)
      collection.updateMany(selector, update).toFuture().map(_.getModifiedCount.toInt)
    }

  def blockAllFailedButNotBlocked(csid: ClientSubscriptionId)(implicit lc: LogContext): Future[Either[MongoDbError, Int]] =
    catchExceptions("setting all FailedButNotBlocked notifications for client subscription ID to FailedAndBlocked") {
      val selector = and(
        equalToCsid(csid),
        equalToStatus(FailedButNotBlocked))
      val update = updateStatusBson(FailedAndBlocked)
      collection.updateMany(selector, update).toFuture().map { result =>
        result.getModifiedCount.toInt
      }
    }

  def checkFailedAndBlockedExist(csid: ClientSubscriptionId)(implicit lc: LogContext): Future[Either[MongoDbError, Boolean]] =
    catchExceptions(s"checking if FailedAndBlocked notifications exist") {
      val selector = and(
        equalToCsid(csid),
        equalToStatus(FailedAndBlocked)
      )

      collection.find(selector).first().toFutureOption().map(_.isDefined)
    }

  def getAllOutstanding()(implicit lc: LogContext): Either[MongoDbError, Observable[Notification]] =
    catchObservableExceptions("getting outstanding notifications to retry") {
      val selector: Bson =
        or(
          and(
            equal(workItemFields.status, FailedButNotBlocked.toBson),
            lte(workItemFields.availableAt, now())
          ),
          and(
            equal(workItemFields.status, SavedToBeSent.toBson),
            lte(workItemFields.updatedAt, now().minus(inProgressRetryAfter))
          )
        )
      collection.find(selector).map(repoToDomain)
    }

  def getLatestOutstandingFailedAndBlockedForEachCsId()(implicit lc: LogContext): Either[MongoDbError, Observable[Notification]] =
    catchObservableExceptions("getting a FailedAndBlocked notification for each distinct client subscription ID") {
      val latestAvailableAt = "latestAvailableAt"
      val docs1 = "docs1"
      val origAvailableAt = s"$docs1.${workItemFields.availableAt}"
      val docs2 = "docs2"
      val pipeline = List(
        Aggregates.`match`(
          equalToStatus(FailedAndBlocked)
        ),
        Aggregates.group(
          id = s"$$${FieldNames.ClientSubscriptionId}",
          fieldAccumulators =
            Accumulators.max(latestAvailableAt, s"$$${workItemFields.availableAt}"),
          Accumulators.push(docs1, "$$ROOT")
        ),
        // We only want to unblock if *all* FailedAndBlocked notifications under a csid are due for unblocking
        Aggregates.`match`(
          Filters.lte(latestAvailableAt, now())
        ),
        Aggregates.project(Document(
          docs2 -> Document(
            "$filter" -> Document(
              "input" -> s"$$$docs1",
              "as" -> s"$docs1",
              "cond" -> Document(
                "$eq" -> List(s"$$$$$origAvailableAt", s"$$$latestAvailableAt")
              )
            )
          )
        )),
        Aggregates.unwind(s"$$$docs2"),
        Aggregates.replaceWith(s"$$$docs2")
      )

      collection.aggregate(pipeline).map(repoToDomain)
    }


  private def equalToCsid(csid: ClientSubscriptionId): Bson =
    Filters.equal(FieldNames.ClientSubscriptionId, csid.id.toString)

  private def equalToStatus(status: ProcessingStatus): Bson =
    Filters.equal(workItemFields.status, status.toBson)

  private def equalToClientId(clientId: ClientId): Bson =
    Filters.equal(FieldNames.ClientId, clientId.id)

  private def updateStatusBson(updatedStatus: ProcessingStatus): Bson = {
    combine(
      set(workItemFields.status, updatedStatus.toBson),
      set(workItemFields.updatedAt, now())
    )
  }

  // scalastyle:off method.length
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
          keys = descending(FieldNames.ClientId),
          indexOptions = IndexOptions()
            .name("clientNotification-clientId-index")
            .unique(false)
        ),
        IndexModel(
          keys = compoundIndex(
            descending(FieldNames.ClientId),
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
            descending(FieldNames.ClientSubscriptionId),
            descending(WORK_ITEM_STATUS)
          ),
          indexOptions = IndexOptions()
            .name(s"csId-$WORK_ITEM_STATUS-index")
            .unique(false)
        )
      )
    }
    MongoUtils.ensureIndexes(collection, notificationWorkItemIndexes, replaceIndexes = true)
  }

  private def catchObservableExceptions[A](doingWhat: String)(block: => Observable[A])
                                          (implicit lc: LogContext): Either[MongoDbError, Observable[A]] = {
    try {
      Right(block)
    } catch {
      case t: Throwable =>
        val e = MongoDbError(doingWhat, t)
        logger.error(e.message)
        Left(e)
    }
  }

  private def catchExceptions[A](doingWhat: String)(block: => Future[A])
                                (implicit ec: ExecutionContext,
                                 lc: LogContext): Future[Either[MongoDbError, A]] = {
    block.map { result =>
      logger.debug(s"${doingWhat.capitalize}. Result: $result}")
      Right(result)
    }.recover {
      case t: Throwable =>
        val e = MongoDbError(doingWhat, t)
        logger.error(e.message)
        Left(e)
    }
  }
}

object Repository {

  case class MongoDbError(doingWhat: String, cause: Throwable) {
    val message: String = s"MongoDb error while $doingWhat. Exception: ${cause.getMessage}"
  }

  def domainToRepo(notification: Notification,
                   status: ProcessingStatus,
                   updatedAt: Instant): WorkItem[NotificationWorkItem] = {
    val n = notification
    val notificationWorkItem = {
      NotificationWorkItem(
        n.csid,
        n.clientId,
        n.metricsStartDateTime,
        NotificationWorkItemBody(
          n.notificationId,
          n.conversationId,
          n.headers,
          n.payload.toString,
          MimeTypes.XML
        )
      )
    }
    val receivedAt, availableAt = updatedAt

    WorkItem[NotificationWorkItem](
      id = n.id,
      receivedAt = receivedAt,
      updatedAt = updatedAt,
      availableAt = availableAt,
      status = status.legacyStatus,
      failureCount = 0,
      item = notificationWorkItem)
  }

  def repoToDomain(w: WorkItem[NotificationWorkItem]): Notification = {
    Notification(
      w.id,
      w.item._id,
      w.item.clientId,
      w.item.notification.notificationId,
      w.item.notification.conversationId,
      w.item.notification.headers,
      Payload.from(w),
      w.item.metricsStartDateTime
    )
  }

  private object FieldNames {
    val ClientSubscriptionId = "clientNotification._id"
    val ClientId = "clientNotification.clientId"
  }

  object Dto {
    case class NotificationWorkItemBody(notificationId: NotificationId,
                                        conversationId: ConversationId,
                                        headers: Seq[Header],
                                        payload: String,
                                        contentType: String)

    object NotificationWorkItemBody {
      implicit val workItemBodyFormat: Format[NotificationWorkItemBody] = Json.format[NotificationWorkItemBody]
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