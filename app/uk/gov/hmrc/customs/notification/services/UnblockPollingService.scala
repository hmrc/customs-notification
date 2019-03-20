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

import akka.actor.ActorSystem
import javax.inject._
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, CustomsNotificationConfig, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.workitem.{PermanentlyFailed, WorkItem}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class UnblockPollingService @Inject()(config: CustomsNotificationConfig,
                                      actorSystem: ActorSystem,
                                      notificationWorkItemRepo: NotificationWorkItemRepo,
                                      pushOrPullService: PushOrPullService,
                                      logger: CdsLogger)(implicit executionContext: ExecutionContext) {

  if (config.unblockPollingConfig.pollingEnabled) {
    val pollingDelay: FiniteDuration = config.unblockPollingConfig.pollingDelay

      actorSystem.scheduler.schedule(0.seconds, pollingDelay) {
        notificationWorkItemRepo.distinctPermanentlyFailedByCsid().map { permanentlyFailedCsids: Set[ClientSubscriptionId] =>
          logger.info(s"Un-blocker - discovered ${permanentlyFailedCsids.size} blocked csids (i.e. with status of ${PermanentlyFailed.name})")
          permanentlyFailedCsids.foreach { csid =>
            notificationWorkItemRepo.pullOutstandingWithPermanentlyFailedByCsid(csid).map {
              case Some(workItem) =>
                pushOrPull(workItem).foreach(ok =>
                  if (ok) {
                    // if we are able to push/pull we flip statues from PF -> F for this CsId by side effect - we do not wait for this to complete
                    notificationWorkItemRepo.toFailedByCsid(csid).foreach{count =>
                      logger.info(s"Un-blocker - number of notifications set from PermanentlyFailed to Failed = $count for CsId ${csid.toString}")
                    }
                  }
                )
              case None =>
                logger.info(s"Un-blocker found no PermanentlyFailed notifications for CsId ${csid.toString}")
              }
            }
          }

        }
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem]): Future[Boolean] = {

    implicit val loggingContext: NotificationWorkItem = workItem.item

    pushOrPullService.send(workItem.item).map[Boolean]{
      case Right(connector) =>
        logger.info(s"Un-blocker pilot retry succeeded for $connector for notification ${workItem.item}")
        true
      case Left(PushOrPullError(connector, resultError)) =>
        logger.info(s"Un-blocker pilot send for $connector failed with error $resultError. CsId = ${workItem.item.clientSubscriptionId.toString}")
        false
    }.recover{
      case NonFatal(e) => // Should never happen
        logger.error(s"Un-blocker - error with pilot unblock of notification ${workItem.item}", e)
        false
    }

  }

}
