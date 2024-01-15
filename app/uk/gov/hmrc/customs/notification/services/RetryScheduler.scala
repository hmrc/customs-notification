/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.config.RetrySchedulerConfig
import uk.gov.hmrc.customs.notification.connectors.{ClientDataConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedAndBlocked, FailedButNotBlocked}
import uk.gov.hmrc.customs.notification.models.{AuditContext, LogContext, Notification, ProcessingStatus}
import uk.gov.hmrc.customs.notification.repositories.utils.Errors.MongoDbError
import uk.gov.hmrc.customs.notification.repositories.{BlockedCsidRepository, NotificationRepository}
import uk.gov.hmrc.customs.notification.util.FutureEither.Ops.*
import uk.gov.hmrc.customs.notification.util.Helpers.ignoreResult
import uk.gov.hmrc.customs.notification.util.{FutureEither, Logger}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.*
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

@Singleton
class RetryScheduler @Inject()(actorSystem: ActorSystem,
                               retryService: RetryService,
                               config: RetrySchedulerConfig)(implicit ec: ExecutionContext) {
  implicit private val initialLc: LogContext = LogContext.empty
  if (config.enabled) {
    actorSystem.scheduler.scheduleWithFixedDelay(
      initialDelay = Random.between(0, config.failedButNotBlockedDelay.toSeconds).seconds,
      delay = config.failedButNotBlockedDelay)(
      runnable = () => retryService.retryFailedButNotBlocked()
    )

    actorSystem.scheduler.scheduleWithFixedDelay(
      initialDelay = Random.between(0, config.failedAndBlockedDelay.toSeconds).seconds,
      delay = config.failedAndBlockedDelay) (
      runnable = () => retryService.retryFailedAndBlocked()
    )
  }
}

@Singleton
class RetryService @Inject()(notificationRepo: NotificationRepository,
                             blockedCsidRepo: BlockedCsidRepository,
                             sendService: SendService,
                             clientDataConnector: ClientDataConnector,
                             headerCarrierService: HeaderCarrierService,
                             metrics: MetricsConnector)
                            (implicit ec: ExecutionContext) extends Logger {
  implicit val hc: HeaderCarrier = headerCarrierService.newHc()

  def retryFailedButNotBlocked()(implicit lc: LogContext): Future[Unit] =
    retry(
      status = FailedButNotBlocked,
      getOneToRetry = () => notificationRepo.pullOutstanding(),
      onSendResult = (_, _) => Future.unit
    )

  def retryFailedAndBlocked()(implicit lc: LogContext): Future[Unit] =
    retry(
      status = FailedAndBlocked,
      getOneToRetry = () => blockedCsidRepo.getSingleNotificationToUnblock(),
      onSendResult = {
        case (result, notification) => result match {
          case SendService.Success | SendService.ClientError =>
            blockedCsidRepo.unblockCsid(notification.csid)
              .map(_.map(ignoreResult))
          case SendService.ServerError =>
            blockedCsidRepo.blockCsid(notification)
              .map(_.map(ignoreResult))
        }
      }
    )

  private def retry(status: ProcessingStatus,
                    getOneToRetry: () => Future[Either[MongoDbError, Option[Notification]]],
                    onSendResult: (SendService.Result, Notification) => Future[Unit],
                    retryCount: RetryCount = RetryCount()): Future[Unit] = {
    metrics.incrementRetryCounter()

    getOneToRetry()
      .toFutureEither
      .ignoreError
      .flatMap {
        case None => FutureEither.pure {
          val rc = retryCount
          implicit val lc: LogContext = LogContext.empty
          if (rc.clientError + rc.serverError + rc.success == 0) {
            logger.info(s"No ${status.name} notifications found to retry")
          } else {
            logger.info(s"Retry complete ([${rc.success}] succeeded, [${rc.clientError}] client errors, [${rc.serverError}] server errors)")
          }
        }
        case Some(toRetry) =>
          implicit val lc: LogContext = LogContext(toRetry)
          implicit val ac: AuditContext = AuditContext(toRetry)
          logger.info(s"About to retry ${status.name} notification")(lc)
          for {
            clientData <- clientDataConnector.get(toRetry.csid).toFutureEither.ignoreError
            res <- sendService.send(toRetry, clientData.sendData)(hc, lc, ac).toFutureEither.ignoreError
            rc = res match {
              case SendService.Success =>
                retryCount.copy(success = retryCount.success + 1)
              case SendService.ClientError =>
                retryCount.copy(clientError = retryCount.clientError + 1)
              case SendService.ServerError =>
                retryCount.copy(serverError = retryCount.serverError + 1)
            }
            _ <- FutureEither.liftF(
              onSendResult(res, toRetry)
            )
            _ <- FutureEither.liftF(
              retry(status, getOneToRetry, onSendResult, rc)
            )
          }
          yield ()
      }
      .value
      .map(ignoreResult)
  }

  private case class RetryCount(clientError: Int = 0, serverError: Int = 0, success: Int = 0)
}

