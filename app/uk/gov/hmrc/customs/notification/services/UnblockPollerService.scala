/*
 * Copyright 2024 HM Revenue & Customs
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

import org.apache.pekko.actor.ActorSystem
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.WorkItem

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

    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, pollerInterval) { () => {
      notificationWorkItemRepo.distinctPermanentlyFailedByCsId()
        .foreach { permanentlyFailedCsids: Set[ClientSubscriptionId] =>
          logger.info(s"Unblock - discovered [${permanentlyFailedCsids.size}] blocked csids (i.e. with status of [${PermanentlyFailed.name}]: [$permanentlyFailedCsids])")

          permanentlyFailedCsids.foreach(retry)
        }
    }
    }
  }

  private def retry(csid: ClientSubscriptionId): Future[Unit] = {
    for {
      maybeWorkItem <- notificationWorkItemRepo.pullSinglePfFor(csid)
    } yield {
      maybeWorkItem match {
        case Some(workItem) =>
          pushOrPull(workItem).foreach(handleResponse(csid))
        case None =>
          logger.info(s"Unblock found no ${ PermanentlyFailed.name } notifications for CsId [${csid.toString}]")
      }
    }
  }

  private def handleResponse(csid: ClientSubscriptionId)(response: Response): Unit = response match {
    case Success | ClientError =>
      notificationWorkItemRepo.fromPermanentlyFailedToFailedByCsId(csid)
        .foreach { count =>
          logger.info(s"Unblock - number of notifications set from ${ PermanentlyFailed.name } to ${ Failed.name } = [$count] for CsId [${csid.toString}]")
        }
    case ServerError => ()
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem]): Future[Response] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    pushOrPullService.send(workItem.item).flatMap {
      case Right(connector) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        logger.info(s"${ workItem } Unblock pilot for [$connector] succeeded. Setting work item status [${Succeeded.name}]")
        Future.successful(Success)
      case Left(PushOrPullError(connector, resultError)) =>
        logger.info(s"${ workItem } Unblock pilot for [$connector] failed with error $resultError. Setting work item status back to [${PermanentlyFailed.name}]")
        (for {
          _ <- notificationWorkItemRepo.incrementFailureCount(workItem.id)
          status <- {
            resultError match {
              case error@HttpResultError(status, _) if error.is3xx || error.is4xx =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusMinutes(customsNotificationConfig.notificationConfig.nonBlockingRetryAfterMinutes)
                logger.error(s"Status response [$status] received while trying unblock pilot, setting availableAt to [$availableAt] and status to Failed")
                notificationWorkItemRepo.setCompletedStatusWithAvailableAt(workItem.id, Failed, status, availableAt)
                  .map(_ => ClientError)
              case HttpResultError(status, _) =>
                notificationWorkItemRepo.setPermanentlyFailed(workItem.id, status)
                  .map(_ => ServerError)
              case NonHttpError(cause) =>
                logger.error(s"Error received while unblocking notification: [${cause.getMessage}]")
                Future.successful(ServerError)
            }
          }
        } yield status).recover {
          case NonFatal(e) => logger.error("Error updating database", e)
            ServerError
        }
    }.recover {
      case NonFatal(e) => // Should never happen
        logger.error(s"Unblock - error with pilot unblock of work item [$workItem]", e)
        ServerError
    }
  }

  sealed trait Response

  case object ClientError extends Response

  case object ServerError extends Response

  case object Success extends Response
}
