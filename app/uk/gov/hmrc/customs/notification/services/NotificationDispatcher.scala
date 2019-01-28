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

package uk.gov.hmrc.customs.notification.services

import java.util.UUID

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{LockOwnerId, LockRepo}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@ImplementedBy(classOf[NotificationDispatcherImpl])
trait NotificationDispatcher {

  def process(csids: Set[ClientSubscriptionId]): Future[Unit]
}

@Singleton
class NotificationDispatcherImpl @Inject()(clientWorker: ClientWorker,
                                           lockRepo: LockRepo,
                                           configService: CustomsNotificationConfig,
                                           logger: NotificationLogger) extends NotificationDispatcher {

  private val duration = configService.pushNotificationConfig.lockDuration

  def process(csids: Set[ClientSubscriptionId]): Future[Unit] = {
    if(csids.size > 0) {
      logger.debugWithoutRequestContext(s"received $csids and about to process them")
    }

    Future {
      csids.foreach {
        csid =>
          val lockOwnerId = LockOwnerId(UUID.randomUUID().toString)
          lockRepo.tryToAcquireOrRenewLock(csid, lockOwnerId, duration).flatMap {
            case true =>
              logger.debugWithoutRequestContext(s"sending $csid to worker")
              clientWorker.processNotificationsFor(csid, lockOwnerId, duration)
            case false =>
              logger.debugWithoutRequestContext(s"Unable to acquire or renew Lock for ${csid}")
              Future.successful(())
          }
      }
    }
  }
}
