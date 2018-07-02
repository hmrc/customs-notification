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

import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.lock.NotificationLockRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

case class LockOwnerId(id: String) extends AnyVal

trait LockRepo {

  val db: () => DB
  val repo = new NotificationLockRepository()(db)

  /*
    Calling lock will try to renew a lock but acquire a new lock if it doesn't exist
   */
  def lock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, holdLockForInMilliSeconds: Long): Future[Boolean] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(db, repo)
    val eventualMaybeBoolean: Future[Option[Boolean]] = lock.tryToAcquireOrRenewLock(csId, lockOwnerId, holdLockForInMilliSeconds, Future.successful(true))
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
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(db, repo)
    lock.releaseLock(csId, lockOwnerId)
  }

  /*
    Calling refresh locks will call lock function so will try and renew first then acquire lock if unable to renew
  */
  // if it returns false, stop processing the client, abort abort abort
  def refreshLock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, renewLockForInMilliSeconds: Long): Future[Boolean] = {
    lock(csId,lockOwnerId, renewLockForInMilliSeconds)
  }


  def isLocked(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId): Future[Boolean] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(db, repo)
    lock.isLocked(csId, lockOwnerId)
  }

  def currentLocks(): Future[Set[ClientSubscriptionId]] = {
    val lock: NotificationExclusiveTimePeriodLock = new NotificationExclusiveTimePeriodLock(db, repo)
    lock.findAllNonExpiredLocks()
  }
}

class NotificationExclusiveTimePeriodLock(mongoDb: () => DB, repository: NotificationLockRepository) {
  private implicit val mongo: () => DB = mongoDb
  val repo: NotificationLockRepository = repository


  def releaseLock(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit ec : ExecutionContext): Future[Unit] = {
    repo.releaseLock(csId.id.toString, lockOwnerId.id)
  }

  def isLocked(csId: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit ec : ExecutionContext): Future[Boolean] = {
    repo.isLocked(csId.id.toString, lockOwnerId.id)
  }

  def findAllNonExpiredLocks()(implicit ec : ExecutionContext): Future[Set[ClientSubscriptionId]] = {
    repo.findAllNonExpiredLocks()
  }

  def tryToAcquireOrRenewLock[T](csId: ClientSubscriptionId, lockOwnerId: LockOwnerId, durationInMilliseconds : Long, body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
    val holdLockFor =  org.joda.time.Duration.millis(durationInMilliseconds)

    val myFutureLock = for {
      renewed <- repo.renew(csId.id.toString, lockOwnerId.id, holdLockFor)
      acquired <- if (!renewed) repo.lock(csId.id.toString, lockOwnerId.id, holdLockFor) else Future.successful(false)
    } yield renewed || acquired

    myFutureLock.flatMap {
      case true => body.map(x => Some(x))
      case false => Future.successful(None)
    } recoverWith {
      case ex => repo.releaseLock(csId.id.toString, lockOwnerId.id).flatMap(_ => Future.failed(ex))
    }
  }
}
