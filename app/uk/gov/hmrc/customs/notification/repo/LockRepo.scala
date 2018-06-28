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


import org.joda.time.Duration
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class OwnerId(val ownerId: String) extends AnyVal

trait LockRepo {

  val db: () => DB
  val repo = new LockRepository()(db)

  /*
    Calling lock will try to renew a lock but acquire a new lock if it doesn't exist
   */
  def lock(csId: ClientSubscriptionId, ownerId : OwnerId, duration: Duration): Future[Boolean] = {
    val lock: ExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, ownerId, duration, db, repo)
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
  def release(csId: ClientSubscriptionId, ownerId: OwnerId): Future[Unit] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, ownerId,  Duration.ZERO, db, repo)
    lock.releaseLock()
  }

  /*
    Calling refresh locks will call lock function so will try and renew first then acquire lock if unable to renew
  */
  // if it returns false, stop processing the client, abort abort abort
  def refreshLock(csId: ClientSubscriptionId,  ownerId: OwnerId, duration: Duration): Future[Boolean] = {
    lock(csId, ownerId, duration)
  }


  def isLocked(csId: ClientSubscriptionId, ownerId: OwnerId): Future[Boolean] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(csId, ownerId,  Duration.ZERO, db, repo)
    lock.isLocked()
  }
}

class NotificationExclusiveTimePeriodLock(csId: ClientSubscriptionId, lockOwnerId :OwnerId, duration: Duration, mongoDb: () => DB, repository: LockRepository) extends ExclusiveTimePeriodLock{
  override val holdLockFor: Duration = duration
  private implicit val mongo: () => DB = mongoDb
  override val repo: LockRepository = repository
  override def lockId: String = csId.id.toString
  override lazy val serverId = lockOwnerId.ownerId

  def releaseLock()(implicit ec : ExecutionContext): Future[Unit] = {
    repo.releaseLock(lockId, serverId)
  }

  def isLocked()(implicit ec : ExecutionContext): Future[Boolean] = {
    repo.isLocked(lockId, serverId)
  }

}

