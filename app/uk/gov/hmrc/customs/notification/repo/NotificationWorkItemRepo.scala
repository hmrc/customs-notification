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
import org.joda.time.DateTime
import play.api.libs.functional.syntax.{unlift, _}
import play.api.libs.json.{Format, Reads, __}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONLong, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers.ClockJodaExtensions
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.workitem._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@ImplementedBy(classOf[NotificationWorkItemMongoRepo])
trait NotificationWorkItemRepo {

  def saveWithLock(notificationWorkItem: NotificationWorkItem): Future[WorkItem[NotificationWorkItem]]

  def setCompletedStatus(id: BSONObjectID, status: ResultStatus): Future[Unit]
}

@Singleton
class NotificationWorkItemMongoRepo @Inject()(mongoDbProvider: MongoDbProvider,
                                              clock: Clock,
                                              customsNotificationConfig: CustomsNotificationConfig,
                                              logger: CdsLogger) //TODO use NotificationLogger
extends WorkItemRepository[NotificationWorkItem, BSONObjectID] (
        collectionName = "notifications-work-item",
        mongo = mongoDbProvider.mongo,
        itemFormat = WorkItemFormat.workItemMongoFormat[NotificationWorkItem]) with NotificationWorkItemRepo {

  override def workItemFields: WorkItemFieldNames = new WorkItemFieldNames {
    val receivedAt = "createdAt"
    val updatedAt = "lastUpdated"
    val availableAt = "availableAt"
    val status = "status"
    val id = "_id"
    val failureCount = "failures"
  }

  override def now: DateTime = clock.nowAsJoda

  override def inProgressRetryAfterProperty: String = ???

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
      unique = false)
  )

  def saveWithLock(notificationWorkItem: NotificationWorkItem): Future[WorkItem[NotificationWorkItem]] = {
    logger.debug(s"saving a new notification work item in locked state $notificationWorkItem")

    def inProgress(item: NotificationWorkItem): ProcessingStatus = InProgress

    pushNew(notificationWorkItem, now, inProgress _)
  }

  def setCompletedStatus(id: BSONObjectID, status: ResultStatus): Future[Unit] = {
    logger.debug(s"setting completed status of $status for notification work item id: ${id.stringify}")
    complete(id, status).map(_ => () )
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
