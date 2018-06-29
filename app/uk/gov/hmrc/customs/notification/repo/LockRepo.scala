/*
 * Copyright 2018 HM Revenue & Customs
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

import org.joda.time.Duration
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, NotificationLockRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

case class LockOwnerId(val id: String) extends AnyVal

trait LockRepo {

  val db: () => DB
  val repo = new NotificationLockRepository()(db)

  /*
    Calling lock will try to renew a lock but acquire a new lock if it doesn't exist
   */
  def lock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, duration: Duration): Future[Boolean] = {
    val lock: ExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, lockOwnerId, duration, db, repo)
    val eventualMaybeBoolean: Future[Option[Boolean]] = lock.tryToAcquireOrRenewLock(Future.successful(true))
    val eventualBoolean: Future[Boolean] = eventualMaybeBoolean.map {
      case Some(true) => true
      case _ => false
    }
    eventualBoolean

  }

  /*
   Calling release lock will call directly to the repository code
  */
  def release(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId): Future[Unit] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, lockOwnerId,  Duration.ZERO, db, repo)
    lock.releaseLock()
  }

  /*
    Calling refresh locks will call lock function so will try and renew first then acquire lock if unable to renew
  */
  // if it returns false, stop processing the client, abort abort abort
  def refreshLock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, duration: Duration): Future[Boolean] = {
    lock(csId,lockOwnerId, duration)
  }


  def isLocked(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId): Future[Boolean] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, lockOwnerId,  Duration.ZERO, db, repo)
    lock.isLocked()
  }

  def currentLocks(): Future[List[ClientSubscriptionId]] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(ClientSubscriptionId(UUID.randomUUID()), LockOwnerId("jhghjg"),  Duration.ZERO, db, repo)
    lock.findAllNonExpiredLocks()
  }
}

class NotificationExclusiveTimePeriodLock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, duration: Duration, mongoDb: () => DB, repository: NotificationLockRepository) extends ExclusiveTimePeriodLock{
  override val holdLockFor: Duration = duration
  private implicit val mongo: () => DB = mongoDb
  override val repo: NotificationLockRepository = repository
  override def lockId: String = csId.id.toString
  override lazy val serverId = lockOwnerId.id

  def releaseLock()(implicit ec : ExecutionContext): Future[Unit] = {
    repo.releaseLock(lockId, serverId)
  }

  def isLocked()(implicit ec : ExecutionContext): Future[Boolean] = {
    repo.isLocked(lockId, serverId)
  }

  def findAllNonExpiredLocks()(implicit ec : ExecutionContext): Future[List[ClientSubscriptionId]] = {
    repo.findAllNonExpiredLocks()
  }
}
