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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import org.joda.time.Duration
import play.api.libs.json.Json
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.lock.LockFormats.{Lock, expiryTime}
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockFormats, LockRepository}
import uk.gov.hmrc.mongo.CurrentTime
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class LockOwnerId(id: String) extends AnyVal

@Singleton
class LockRepo @Inject()(mongoDbProvider: MongoDbProvider,
                         notificationLogger: NotificationLogger) extends CurrentTime {

  val repo = new LockRepository()(mongoDbProvider.mongo)

  /*
    Calling lock will try to renew a lock but acquire a new lock if it doesn't exist
   */
  def tryToAcquireOrRenewLock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: Duration): Future[Boolean] = {

    val lock: ExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, lockOwnerId, lockDuration,mongoDbProvider.mongo, repo)
    val eventualMaybeBoolean: Future[Option[Boolean]] = lock.tryToAcquireOrRenewLock(Future.successful(true))
    val eventualBoolean: Future[Boolean] = eventualMaybeBoolean.map {
      case Some(true) => true
      case _ => false
    }
    eventualBoolean

  }
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  /*
   Calling release lock will call directly to the repository code
  */
  def release(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId): Future[Unit] = {
    repo.releaseLock(csId.id.toString, lockOwnerId.id)
  }

  def isLocked(csId: ClientSubscriptionId): Future[Boolean]  = withCurrentTime { now =>
    repo.find(LockFormats.id -> csId.id.toString, expiryTime -> Json.obj("$gte" -> now)).map(_.nonEmpty)
  }

  def lockedCSIds(): Future[Set[ClientSubscriptionId]] = withCurrentTime { now =>
    repo.find(expiryTime -> Json.obj("$gte" -> now.toDateTime())).map({
      listOfLocks => listOfLocks.toSet[Lock].map(lock => ClientSubscriptionId(UUID.fromString(lock.id)))
    })
  }

}

class NotificationExclusiveTimePeriodLock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, duration: Duration, mongoDb: () => DB, repository: LockRepository) extends ExclusiveTimePeriodLock {
  override val holdLockFor: Duration = duration
  private implicit val mongo: () => DB = mongoDb
  override val repo: LockRepository = repository

  override def lockId: String = csId.id.toString

  override lazy val serverId = lockOwnerId.id

}
