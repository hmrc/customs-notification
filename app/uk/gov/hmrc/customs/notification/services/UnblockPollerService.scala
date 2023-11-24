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
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.{loggableClientSubscriptionId, loggableNotification}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UnblockPollerService @Inject()(implicit
                                     actorSystem: ActorSystem,
                                     repo: NotificationRepo,
                                     sendNotificationService: SendNotificationService,
                                     hotfixService: ClientSubscriptionIdTranslationHotfixService,
                                     logger: NotificationLogger,
                                     config: AppConfig,
                                     apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                                     ec: ExecutionContext) {

  actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.retryFailedAndBlockedDelay) { () => processFailedAndBlocked() }

  actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.retryFailedAndNotBlockedDelay) { () => processFailedAndBlocked() }

  private def processFailedAndBlocked(): Unit = {
    repo.getSingleOutstandingFailedAndBlockedForEachCsId().map {
      case Left(mongoDbError) =>
        logger.error(mongoDbError.message)
      case Right(clientSubscriptionIdSet) =>
        clientSubscriptionIdSet.foreach { untranslatedCsid =>
          val clientSubscriptionId = hotfixService.translate(untranslatedCsid)

          repo.getOutstandingFailedAndBlocked(clientSubscriptionId).flatMap {
            case Left(_) =>
              Future.successful(())
            case Right(None) =>
              logger.info(s"Unblocker found no notifications to unblock for this client subscription ID", clientSubscriptionId)
              Future.successful(())
            case Right(Some(notification)) =>
              implicit val hc: HeaderCarrier = HeaderCarrier()

              apiSubscriptionFieldsConnector.get(clientSubscriptionId).flatMap {
                case Left(ApiSubscriptionFieldsConnector.DeclarantNotFound) =>
                  logger.error("not found", notification)
                  Future.successful(())
                case Left(ApiSubscriptionFieldsConnector.OtherError) =>
                  Future.successful(())
                case Right(ApiSubscriptionFieldsConnector.Success(apiSubscriptionFields)) =>
                  (for {
                    _ <- sendNotificationService.send(notification, apiSubscriptionFields.fields).toFutureCdsResult
                    _ <- repo.unblockFailedAndBlocked(clientSubscriptionId).toFutureCdsResult
                  } yield ()).value
              }
          }
        }
    }
  }

//  private def processFailedButNotBlocked(): Unit = {
//
//  }
}