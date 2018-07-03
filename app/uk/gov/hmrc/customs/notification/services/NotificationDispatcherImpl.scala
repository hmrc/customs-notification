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

package uk.gov.hmrc.customs.notification.services

import java.util.UUID

import javax.inject.{Inject, Singleton}
import org.joda.time.Duration
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{LockOwnerId, LockRepo}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class NotificationDispatcherImpl @Inject()(lockRepo: LockRepo, logger: NotificationLogger) extends NotificationDispatcher {

  private val duration = Duration.standardMinutes(2) //TODO MC this should be configurable property (Avinder will make this change)

  override def process(csids: Set[ClientSubscriptionId])(implicit hc: HeaderCarrier): Future[Unit] = {
    logger.debug(s"received $csids and about to process them")

    Future.successful {
      csids.foreach {
        csid =>
          val lockOwnerId = LockOwnerId(UUID.randomUUID().toString)
          lockRepo.tryToAcquireOrRenewLock(csid, lockOwnerId, duration).flatMap {
            case true =>
              logger.debug(s"sending $csid to worker")
              new DummyClientWorker().processNotificationsFor(csid)
            case false => Future.successful(())
          }
      }
    }
  }
}



