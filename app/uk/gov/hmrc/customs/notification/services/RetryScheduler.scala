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

import akka.actor.ActorSystem
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.{loggableClientSubscriptionId, loggableNotification}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject._
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RetryScheduler @Inject()(actorSystem: ActorSystem,
                               retryService: RetryService,
                               config: AppConfig)(implicit ec: ExecutionContext) {
  if (config.enableRetryScheduler) {
    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.retryFailedAndBlockedDelay)(() => retryService.retryFailedAndBlocked())
    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.retryFailedAndNotBlockedDelay)(() => retryService.retryFailedAndNotBlocked())
  }
}

@Singleton
class RetryService @Inject()(repo: NotificationRepo,
                             sendNotificationService: SendNotificationService,
                             apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                             newHeaderCarrierService: HeaderCarrierService,
                             metrics: MetricsConnector,
                             logger: NotificationLogger)
                            (implicit ec: ExecutionContext) {
  def retryFailedAndNotBlocked(): Future[Unit] = {
    implicit val hc: HeaderCarrier = newHeaderCarrierService.newHc()

    repo.getSingleOutstanding().flatMap {
      case Right(Some(notification)) =>
        metrics.incrementRetryCounter()

        (for {
          s <- apiSubscriptionFieldsConnector.get(notification.clientSubscriptionId).toFutureCdsResult
          _ <- sendNotificationService.send(notification, s.apiSubscriptionFields.fields).toFutureCdsResult
          _ <- retryFailedAndNotBlocked().map(Right(_)).toFutureCdsResult
        } yield ()).value.map(_ => ())
      case _ =>
        Future.successful(())
    }
  }

  def retryFailedAndBlocked(): Future[Unit] = {
    implicit val hc: HeaderCarrier = newHeaderCarrierService.newHc()

    repo.getSingleOutstandingFailedAndBlockedForEachCsId().map {
      case Right(clientSubscriptionIdSet) =>
        Future.traverse(clientSubscriptionIdSet) { clientSubscriptionId =>
          repo.getOutstandingFailedAndBlocked(clientSubscriptionId).flatMap {
            case Right(Some(notification)) =>
              apiSubscriptionFieldsConnector.get(clientSubscriptionId).flatMap {
                case Right(ApiSubscriptionFieldsConnector.Success(apiSubscriptionFields)) =>
                  (for {
                    _ <- sendNotificationService.send(notification, apiSubscriptionFields.fields).toFutureCdsResult
                    count <- repo.unblockFailedAndBlocked(clientSubscriptionId).toFutureCdsResult
                    _ = logger.info(s"Unblocked and queued for retry $count FailedAndBlocked notifications for this client subscription ID", clientSubscriptionId)
                  } yield ()).value.map(_ => ())
                case Left(_) => Future.successful(())
              }
            case _ => Future.successful(())
          }
        }
      case Left(_) => Future.successful(())
    }
  }
}