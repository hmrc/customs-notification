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
import uk.gov.hmrc.workitem.{PermanentlyFailed, Succeeded, WorkItem}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class UnblockPollerService @Inject()(config: CustomsNotificationConfig,
                                     actorSystem: ActorSystem,
                                     notificationWorkItemRepo: NotificationWorkItemRepo,
                                     pushOrPullService: PushOrPullService,
                                     logger: CdsLogger)(implicit executionContext: ExecutionContext) {

  if (config.unblockPollerConfig.pollerEnabled) {
    val pollerInterval: FiniteDuration = config.unblockPollerConfig.pollerInterval

      actorSystem.scheduler.schedule(0.seconds, pollerInterval) {
        notificationWorkItemRepo.distinctPermanentlyFailedByCsId().map { permanentlyFailedCsids: Set[ClientSubscriptionId] =>
          logger.info(s"Unblock - discovered ${permanentlyFailedCsids.size} blocked csids (i.e. with status of ${PermanentlyFailed.name})")
          logger.debug(s"Unblock - discovered $permanentlyFailedCsids blocked csids (i.e. with status of ${PermanentlyFailed.name})")
          permanentlyFailedCsids.foreach { csid =>
            notificationWorkItemRepo.pullOutstandingWithPermanentlyFailedByCsId(csid).map {
              case Some(workItem) =>
                pushOrPull(workItem).foreach(ok =>
                  if (ok) {
                    // if we are able to push/pull we flip statues from PF -> F for this CsId by side effect - we do not wait for this to complete
                    //changing status to failed makes the item eligible for retry
                    notificationWorkItemRepo.fromPermanentlyFailedToFailedByCsId(csid).foreach{ count =>
                      logger.info(s"Unblock - number of notifications set from PermanentlyFailed to Failed = $count for CsId ${csid.toString}")
                    }
                  }
                )
              case None =>
                logger.info(s"Unblock found no PermanentlyFailed notifications for CsId ${csid.toString}")
              }
            }
          }

        }
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem]): Future[Boolean] = {

    implicit val loggingContext: NotificationWorkItem = workItem.item

    pushOrPullService.send(workItem.item).map[Boolean]{
      case Right(connector) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        logger.info(s"Unblock pilot send for $connector succeeded. CsId = ${workItem.item.clientSubscriptionId.toString}. Setting work item status ${Succeeded.name} for $workItem")
        true
      case Left(PushOrPullError(connector, resultError)) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, PermanentlyFailed)
        logger.info(s"Unblock pilot send for $connector failed with error $resultError. CsId = ${workItem.item.clientSubscriptionId.toString}. Setting work item status back to ${PermanentlyFailed.name} for $workItem")
        false
    }.recover{
      case NonFatal(e) => // Should never happen
        logger.error(s"Unblock - error with pilot unblock of work item $workItem", e)
        false
    }

  }

}
