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

package uk.gov.hmrc.customs.notification.repositories

import org.bson.types.ObjectId
import org.mongodb.scala.model.*
import play.api.http.MimeTypes
import play.api.libs.json.*
import uk.gov.hmrc.customs.notification.config.RepoConfig
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.*
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository.*
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository.Dto.*
import uk.gov.hmrc.customs.notification.repositories.utils.Errors.{MongoDbError, catchException}
import uk.gov.hmrc.customs.notification.repositories.utils.MongoDbUtils
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.customs.notification.util.Helpers.ignoreResult
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}

import java.time.{Duration, Instant, ZonedDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters.ScalaDurationOps

@Singleton
class NotificationRepository @Inject()(mongo: MongoComponent,
                                       dateTimeService: DateTimeService,
                                       config: RepoConfig)
                                      (implicit ec: ExecutionContext) extends Logger {

  private def nowInstant() = dateTimeService.now().toInstant

  val underlying: WorkItemRepository[NotificationWorkItem] = new WorkItemRepository[NotificationWorkItem](
    collectionName = "notifications-work-item",
    mongoComponent = mongo,
    itemFormat = NotificationWorkItem.format,
    replaceIndexes = false,
    workItemFields = WorkItemFields(
      id = Fields.WorkItemId,
      receivedAt = Fields.ReceivedAt,
      updatedAt = Fields.UpdatedAt,
      availableAt = Fields.AvailableAt,
      status = Fields.Status,
      failureCount = Fields.FailureCount,
      item = Fields.Item
    ),
    extraIndexes = indexes(config.notificationTtl)
  ) {
    override def now(): Instant = nowInstant()

    override val inProgressRetryAfter: Duration = config.inProgressRetryDelay.toJava
  }

  def insert(domainEntity: Notification,
             processingStatus: ProcessingStatus,
             failureCount: Int)
            (implicit lc: LogContext): Future[Either[MongoDbError, Unit]] = {
    catchException("saving notification") {
      val mongoWorkItem: WorkItem[NotificationWorkItem] =
        Mapping.domainToRepo(
          domainEntity,
          processingStatus,
          nowInstant(),
          failureCount)
      underlying.collection.insertOne(mongoWorkItem).toFuture().map(ignoreResult)
    }
  }

  def setSucceeded(id: ObjectId)(implicit lc: LogContext): Future[Either[MongoDbError, Unit]] =
    catchException(s"setting notification to Succeeded") {
      underlying.markAs(id, Succeeded.internalStatus).map(ignoreResult)
    }

  def setFailedAndBlocked(id: ObjectId)(implicit lc: LogContext): Future[Either[MongoDbError, Unit]] =
    catchException("setting FailedAndBlocked for notification") {
      underlying.collection.updateOne(
        filter = Filters.equal(Fields.WorkItemId, id),
        update = Updates.combine(
          Updates.set(Fields.Status, FailedAndBlocked.toBson),
          Updates.set(Fields.UpdatedAt, nowInstant()),
          Updates.inc(Fields.FailureCount, 1)
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
    catchException(s"setting FailedButNotBlocked for notification") {
      underlying.markAs(id, FailedButNotBlocked.internalStatus, Some(availableAt.toInstant))
        .map { hasUpdated =>
          if (hasUpdated) {
            Right(())
          } else {
            throw new IllegalStateException("Notification doesn't exist")
          }
        }
    }

  def pullOutstanding()(implicit lc: LogContext): Future[Either[MongoDbError, Option[Notification]]] =
    catchException("getting outstanding notifications to retry") {
      val failedBefore, availableBefore = nowInstant()
      underlying
        .pullOutstanding(failedBefore, availableBefore)
        .map(_.map(Mapping.repoToDomain))
    }
}

object NotificationRepository {
  object Mapping {
    def domainToRepo(notification: Notification,
                     status: ProcessingStatus,
                     updatedAt: Instant,
                     failureCount: Int): WorkItem[NotificationWorkItem] = {
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
        status = status.internalStatus,
        failureCount = failureCount,
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
      implicit val objectIdFormats: Format[ObjectId] = MongoFormats.objectIdFormat
      implicit val format: OFormat[NotificationWorkItem] = Json.format[NotificationWorkItem]
    }
  }

  private[repositories] object Fields {
    val Item = "clientNotification"
    val ClientSubscriptionId = s"$Item._id"
    val ClientId = s"$Item.clientId"
    val WorkItemId = "_id"
    val ReceivedAt = "createdAt"
    val UpdatedAt = "lastUpdated"
    val AvailableAt = "availableAt"
    val Status = "status"
    val FailureCount = "failures"
  }

  // scalastyle:off method.length
  private def indexes(ttl: FiniteDuration): List[IndexModel] = List(
    MongoDbUtils.ttlIndexFor(Fields.ReceivedAt, ttl),

    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.getFailedAndBlockedCount]] */
    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.unblockCsid(ClientId)]] */
    IndexModel(
      keys = Indexes.descending(
        Fields.ClientId,
        Fields.Status
      ),
      indexOptions = IndexOptions()
        .partialFilterExpression(
          Filters.eq(Fields.Status, FailedAndBlocked.toBson)
        )
    ),

    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.blockCsid]] */
    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.unblockCsid(ClientSubscriptionId)]] */
    IndexModel(
      keys = Indexes.ascending(
        Fields.ClientSubscriptionId,
        Fields.Status
      )
    )
  )
}