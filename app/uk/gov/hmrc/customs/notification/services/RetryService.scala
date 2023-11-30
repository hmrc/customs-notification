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

import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits._
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.util.FutureEither.Implicits._
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RetryService @Inject()(repo: Repository,
                             sendService: SendService,
                             apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                             newHeaderCarrierService: HeaderCarrierService,
                             metrics: MetricsConnector,
                             logger: Logger)
                            (implicit ec: ExecutionContext) {
  def retryFailedAndNotBlocked(): Future[Unit] = {
    implicit val hc: HeaderCarrier = newHeaderCarrierService.newHc()

    repo.getSingleOutstanding().flatMap {
      case Right(Some(notification)) =>
        metrics.incrementRetryCounter()
        logger.info("Attempting to retry InProgress/FailedAndBlocked notification", notification)

        (for {
          s <- apiSubscriptionFieldsConnector.get(notification.clientSubscriptionId).toFutureEitherIgnoreError
          _ <- sendService.send(notification, s.apiSubscriptionFields.fields).toFutureEitherIgnoreError
          _ <- retryFailedAndNotBlocked().map(Right(_)).toFutureEitherIgnoreError
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
          repo.getSingleOutstandingFailedAndBlocked(clientSubscriptionId).flatMap {
            case Right(Some(notification)) =>
              logger.info("Attempting to retry FailedAndBlocked notification", notification)
              apiSubscriptionFieldsConnector.get(clientSubscriptionId).flatMap {
                case Right(ApiSubscriptionFieldsConnector.Success(apiSubscriptionFields)) =>
                  (for {
                    _ <- sendService.send(notification, apiSubscriptionFields.fields).toFutureEither
                    count <- repo.unblockFailedAndBlocked(clientSubscriptionId).toFutureEither
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