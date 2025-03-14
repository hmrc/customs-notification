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
import org.playframework.cachecontrol.Seconds
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.customs.notification.services.Debug.extractFunctionCode

import java.time.Instant
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.math.Ordered.orderingToOrdered
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

    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, pollerInterval) { () =>
      for {
        permanentlyFailedCsIds <- notificationWorkItemRepo.distinctPermanentlyFailedByCsId()
        csIdsInProgress <- notificationWorkItemRepo.distinctInProgressByCsId()
      } yield {
        val csIdsToActuallyRetry = permanentlyFailedCsIds -- csIdsInProgress

        if (permanentlyFailedCsIds.nonEmpty) {
          logger.info(s"""(scheduled every $pollerInterval) UnblockPollerService - there are [${permanentlyFailedCsIds.size}] blocked csids (i.e. with valid availableAt and status of [${PermanentlyFailed.name}] [${permanentlyFailedCsIds.mkString(",")}])""")

          if (csIdsToActuallyRetry.nonEmpty) {
            logger.info(s"Retrying for the following CsIds: [${csIdsToActuallyRetry.mkString(",")}]")
            csIdsToActuallyRetry.foreach(retry)
          } else {
            logger.info("none to actually retry")
          }
        }
      }
    }
  }

  private def retry(csid: ClientSubscriptionId): Future[Unit] = {
    logger.debug(s"Retrying for csid: ${ csid }")
    for {
      maybeWorkItem <- notificationWorkItemRepo.pullSinglePfFor(csid)
    } yield {
      maybeWorkItem match {
        case Some(workItem) =>
          logger.debug(s"Found a single item for csid: ${ csid }")
          if (workItem.availableAt <= Instant.now()) {
            logger.debug(s"workItem (${ workItem.id }) has an availableAt in the past, now proceeding with pushOrPull")
            pushOrPull(workItem).foreach(handleResponse(csid))
          } else {
            logger.debug(s"workItem (${ workItem.id }) has an availableAt in the future, so not retrying just yet...")
          }
        case None =>
          logger.info(s"Unblock found no PermanentlyFailed notifications for CsId [${csid.toString}]")
      }
    }
  }

  private def handleResponse(csid: ClientSubscriptionId)(response: Response): Unit = response match {
    case Success | ClientError =>
      notificationWorkItemRepo.fromPermanentlyFailedToFailedByCsId(csid)
        .foreach { count =>
          logger.info(s"Unblock - number of notifications set from PermanentlyFailed to Failed = [$count] for CsId [${csid.toString}]")
        }
    case ServerError => ()
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem]): Future[Response] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val payload = workItem.item.notification.payload
    val functionCode = extractFunctionCode(payload)

    logger.debug(s"UnblockPollerService - Function Code[$functionCode] - availableAt = [${workItem.availableAt}] - createdAt = [${workItem.receivedAt}]")

    pushOrPullService.send(workItem.item).flatMap {

      case Right(connector) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        logger.info(s"Unblock pilot for [$connector] succeeded. CsId = [${workItem.item.clientSubscriptionId.toString}]. Setting work item status [${Succeeded.name}] for [$workItem]")
        Future.successful(Success)

      case Left(PushOrPullError(connector, resultError)) =>
        logger.info(s"Unblock pilot for [$connector] failed with error $resultError. CsId = [${workItem.item.clientSubscriptionId.toString}]. Setting work item status back to [${PermanentlyFailed.name}] for [$workItem]")
        (for {
          _ <- notificationWorkItemRepo.incrementFailureCount(workItem.id)
          status <- {
            resultError match {
              case error@HttpResultError(status, _) if error.is3xx || error.is4xx =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusMinutes(customsNotificationConfig.notificationConfig.nonBlockingRetryAfterMinutes)
                logger.error(s"Status response [$status] received while trying unblock pilot, setting availableAt to [$availableAt] and status to Failed")

                //notificationWorkItemRepo.setCompletedStatus(workItem.id, Failed) // increase failure count
                notificationWorkItemRepo.setCompletedStatusWithAvailableAt(workItem.id, Failed, status, availableAt).map(_ => ClientError)

              case error@HttpResultError(status, _) if error.is5xx =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusSeconds(customsNotificationConfig.notificationConfig.retryPollerInProgressRetryAfter.toSeconds)
                val payload = workItem.item.notification.payload
                val functionCode = extractFunctionCode(payload)
                logger.info(s"Time: ${dateTimeService.zonedDateTimeUtc}  Hitting 500, Function Code[$functionCode]")

                //notificationWorkItemRepo.setCompletedStatus(workItem.id, Failed) // increase failure count
                notificationWorkItemRepo.setPermanentlyFailedWithAvailableAt(workItem.id, PermanentlyFailed, status, availableAt).map(_ => ServerError)

              case HttpResultError(status, _) =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusSeconds(customsNotificationConfig.notificationConfig.retryPollerAfterFailureInterval.toSeconds)
                val functionCode = extractFunctionCode(workItem.item.notification.payload)
                logger.error(s"Status response ${status} received while pushing notification, setting availableAt to $availableAt, FunctionCode: [$functionCode]")

                //notificationWorkItemRepo.setCompletedStatus(workItem.id, Failed) // increase failure count
                notificationWorkItemRepo.setPermanentlyFailedWithAvailableAt(workItem.id, PermanentlyFailed, status, availableAt).map(_ => ServerError)
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
