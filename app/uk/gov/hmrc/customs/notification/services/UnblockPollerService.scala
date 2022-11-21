/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, CustomsNotificationConfig, HttpResultError, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.workitem.{PermanentlyFailed, Succeeded, WorkItem}

import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class UnblockPollerService @Inject()(config: CustomsNotificationConfig,
                                     actorSystem: ActorSystem,
                                     notificationWorkItemRepo: NotificationWorkItemRepo,
                                     pushOrPullService: PushOrPullService,
                                     logger: CdsLogger,
                                     dateTimeService: DateTimeService,
                                     customsNotificationConfig: CustomsNotificationConfig)(implicit executionContext: ExecutionContext) {

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

    implicit val hc: HeaderCarrier = HeaderCarrier()

    pushOrPullService.send(workItem.item).map[Boolean]{
      case Right(connector) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        logger.info(s"Unblock pilot for $connector succeeded. CsId = ${workItem.item.clientSubscriptionId.toString}. Setting work item status ${Succeeded.name} for $workItem")
        true
      case Left(PushOrPullError(connector, resultError)) =>
        logger.info(s"Unblock pilot for $connector failed with error $resultError. CsId = ${workItem.item.clientSubscriptionId.toString}. Setting work item status back to ${PermanentlyFailed.name} for $workItem")

        //TODO Delete
        def logAndUnblock(workItem: WorkItem[NotificationWorkItem]): Boolean = {
          logger.info(s"Marked WorkLog with ClientID: [${workItem.item.clientId.id}] and notificationId: [${workItem.item.notification.notificationId.get.id.toString}] was overridden to success and NOT Marked as ${PermanentlyFailed.name} for $WorkItem")
          notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
          true
        }

        val tempBoolian =
          if (workItem.item.notification.notificationId.nonEmpty) {
            workItem.item.clientId.id match {
              case "AfQuzcCQgbcYzhbftvzHQUmn1KKX" => {
                workItem.item.notification.notificationId.get.id.toString match {
                  case "4773f254-d48d-4723-9377-0228f88ab4f7" => logAndUnblock(workItem)
                  case "07b27b6a-9ea4-4d09-a00d-873a6e403f72" => logAndUnblock(workItem)
                  case _ => false
                }
              }
              case "CM2vPo0FvS9Og2Q5jvCuWwNBNAK2" => {
                workItem.item.notification.notificationId.get.id.toString match {
                  case "e50a8d13-2fd5-4035-8941-2ebd92072073" => logAndUnblock(workItem)
                  case "362fcb01-3d58-4c7e-b41e-ba3008ab9f76" => logAndUnblock(workItem)
                  case "71a62f98-ea2d-47b0-b582-eea3bd56ed70" => logAndUnblock(workItem)
                  case "8526edee-6674-4a39-81d5-d2f4b2a2f267" => logAndUnblock(workItem)
                  case "d799c382-3c3e-4cfe-9b1d-3aeea8999a1d" => logAndUnblock(workItem)
                  case _ => false
                }
              }
              case "1OyOn6E88U3LogqmBNv9xub73T6S" => {
                workItem.item.notification.notificationId.get.id.toString match {
                  case "7bfdaf3a-d469-4607-ad9c-bd141d45c989" => logAndUnblock(workItem)
                  case "2be1bf43-195b-40c6-93b9-98391da25a68" => logAndUnblock(workItem)
                  case "78dcf181-1371-4709-b575-6aef536a8216" => logAndUnblock(workItem)
                  case "a45e4a65-02f8-4b13-ae9d-7e76cae6f339" => logAndUnblock(workItem)
                  case "f0a1f6b9-e9bf-4090-a714-7133d3e39e13" => logAndUnblock(workItem)
                  case "1338e23e-c21b-43e1-85b1-3fc92444304f" => logAndUnblock(workItem)
                  case "71fe900c-5455-466f-be63-94a0684990e2" => logAndUnblock(workItem)
                  case "bc7303f1-4ba8-48f9-9700-c1b64dce6b70" => logAndUnblock(workItem)
                  case "1558b8cf-3452-4e9c-bc31-d2cec89db878" => logAndUnblock(workItem)
                  case "88497a5e-9677-4770-ad16-9d473122684e" => logAndUnblock(workItem)
                  case "8169c65b-4d63-49d0-8f03-c4254571774d" => logAndUnblock(workItem)
                  case "73781695-f710-48f2-a763-3880be5c45f9" => logAndUnblock(workItem)
                  case "48e897b6-d4b1-4cb4-aba3-00aee5372ffe" => logAndUnblock(workItem)
                  case "cdbd7308-208a-421a-ad9c-465fed2c6def" => logAndUnblock(workItem)
                  case "fffb6acc-f5e1-4753-98b5-481ec00fb30a" => logAndUnblock(workItem)
                  case _ => false
                }
              }
              case "7ljnv0UCa9b6UMQbhImW2fXKWw1m" => {
                workItem.item.notification.notificationId.get.id.toString match {
                  case "08a26cb8-64bf-497d-a074-682ba89a3b05" => logAndUnblock(workItem)
                  case _ => false
                }
              }
              case "gb05MdaMD0oZbFGNqwLYmP7KWuYn" => {
                workItem.item.notification.notificationId.get.id.toString match {
                  case "6a1f5613-e1ea-4674-a1b4-76dbc96d9ee8" => logAndUnblock(workItem)
                  case "bd17123c-2536-467b-87db-8275724563b3" => logAndUnblock(workItem)
                  case "dca2f40f-caaf-4bd6-9097-37eb96abf67b" => logAndUnblock(workItem)
                  case _ => false
                }
              }
              case _ => false
            }
          }else{
            false
          }
        (for {
          _ <- notificationWorkItemRepo.incrementFailureCount(workItem.id)
          _ <- {
            resultError match {
              case httpResultError: HttpResultError if httpResultError.is3xx || httpResultError.is4xx =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusMinutes(customsNotificationConfig.notificationConfig.nonBlockingRetryAfterMinutes)
                logger.error(s"Status response ${httpResultError.status} received while trying unblock pilot, setting availableAt to $availableAt")
                notificationWorkItemRepo.setCompletedStatusWithAvailableAt(workItem.id, PermanentlyFailed, availableAt)
              case _ =>
                notificationWorkItemRepo.setCompletedStatus(workItem.id, PermanentlyFailed)
            }
          }
        } yield ()).recover {
          case NonFatal(e) =>
            logger.error("Error updating database", e)
            false
        }
        tempBoolian
    }.recover{
      case NonFatal(e) => // Should never happen
        logger.error(s"Unblock - error with pilot unblock of work item $workItem", e)
        false
    }

  }

}
