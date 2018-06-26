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
import scala.concurrent.Future


trait LockRepo {

  val db: () => DB
  val repo = new LockRepository()(db)

  def lock(csid: ClientSubscriptionId, duration: Duration): Future[Boolean] = {
    val lock: ExclusiveTimePeriodLock = new AbstractNotificationLock(duration, db, repo) {
      override def lockId: String = csid.id.toString
    }
    val eventualMaybeBoolean: Future[Option[Boolean]] = lock.tryToAcquireOrRenewLock(Future.successful(true))
    val eventualBoolean: Future[Boolean] = eventualMaybeBoolean.map {
      case Some(true) => true
      case _ => false
    }
    eventualBoolean

  }

  //def release(csid: ClientSubscriptionId): Future[Unit]
  // if it returns false, stop processing the client, abort abort abort
  //def refreshLock(csid: ClientSubscriptionId, duration: Duration): Future[Boolean]
}

abstract class AbstractNotificationLock(duration: Duration, mongoDb: () => DB, repository: LockRepository) extends ExclusiveTimePeriodLock{
  override lazy val serverId = "customs-notification-locks"
  override val holdLockFor: Duration = duration
  private implicit val mongo: () => DB = mongoDb
  override val repo: LockRepository = repository
}


