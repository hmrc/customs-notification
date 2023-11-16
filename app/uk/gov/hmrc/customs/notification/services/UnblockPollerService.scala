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
import uk.gov.hmrc.customs.notification.config.{ApiSubscriptionFieldsUrlConfig, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.models.ApiSubscriptionFields.reads
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.{loggableClientSubscriptionId, loggableNotificationWorkItem}
import uk.gov.hmrc.customs.notification.models.requests.ApiSubscriptionFieldsRequest
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UnblockPollerService @Inject()(implicit
                                     actorSystem: ActorSystem,
                                     repo: NotificationRepo,
                                     sendService: SendService,
                                     logger: NotificationLogger,
                                     customsNotificationConfig: CustomsNotificationConfig,
                                     apiSubsFieldsUrlConfig: ApiSubscriptionFieldsUrlConfig,
                                     httpConnector: HttpConnector,
                                     ec: ExecutionContext) {
  val pollerInterval: FiniteDuration = customsNotificationConfig.unblockPollerConfig.pollerInterval

  actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, pollerInterval) { () => processFailedAndBlocked() }

  actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, pollerInterval) { () => processFailedAndBlocked() }

  def processFailedAndBlocked(): Unit = {
    repo.getSingleOutstandingFailedAndBlockedForEachCsId().map {
      case Left(mongoDbError) =>
        logger.error(mongoDbError.message)
      case Right(clientSubscriptionIdSet) =>
        clientSubscriptionIdSet.foreach { clientSubscriptionId =>

          repo.getOutstandingFailedAndBlocked(clientSubscriptionId).flatMap {
            case Left(_) =>
              Future.successful(())
            case Right(None) =>
              logger.info(s"Unblocker found no notifications to unblock for this client subscription ID", clientSubscriptionId)
              Future.successful(())
            case Right(Some(notification)) =>
              implicit val hc: HeaderCarrier = HeaderCarrier()
              val apiSubsFieldsRequest = ApiSubscriptionFieldsRequest(notification.clientSubscriptionId, apiSubsFieldsUrlConfig.url)

              httpConnector.get(apiSubsFieldsRequest).flatMap {
                case Left(error) =>
                  logger.error(error.message, notification)
                  Future.successful(())
                case Right(apiSubsFields) =>
                  (for {
                    _ <- sendService.send(notification, apiSubsFields).toFutureCdsResult
                    _ <- repo.unblockFailedAndBlocked(clientSubscriptionId).toFutureCdsResult
                  } yield ()).value
              }
          }
        }
    }
  }
}