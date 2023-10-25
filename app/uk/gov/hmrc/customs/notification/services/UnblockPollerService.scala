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
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.models.repo.FailedAndBlocked
import uk.gov.hmrc.customs.notification.util.NotificationWorkItemRepo
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UnblockPollerService @Inject()(actorSystem: ActorSystem,
                                     notificationWorkItemRepo: NotificationWorkItemRepo,
                                     pushOrPullService: PushOrPullService,
                                     logger: CdsLogger,
                                     customsNotificationConfig: CustomsNotificationConfig,
                                     apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector)(implicit executionContext: ExecutionContext) {

  if (customsNotificationConfig.unblockPollerConfig.pollerEnabled) {
    val pollerInterval: FiniteDuration = customsNotificationConfig.unblockPollerConfig.pollerInterval

    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, pollerInterval) { () => {
      notificationWorkItemRepo.failedAndBlockedGroupedByDistinctCsId().map { failedAndBlockedCsids: Set[ClientSubscriptionId] =>
        logger.info(s"Unblock - discovered ${failedAndBlockedCsids.size} blocked csids (i.e. with status of ${FailedAndBlocked.name})")
        logger.debug(s"Unblock - discovered $failedAndBlockedCsids blocked csids (i.e. with status of ${FailedAndBlocked.name})")
        failedAndBlockedCsids.foreach { csid =>
          notificationWorkItemRepo.pullOutstandingWithFailedWith500ByCsId(csid).map {
            case Some(workItem) =>
              implicit val hc: HeaderCarrier = HeaderCarrier()
              val eventuallyMaybeApiSubscriptionFields: Future[Option[ApiSubscriptionFields]] = pushOrPullService.getApiSubscriptionFields(workItem.item, workItem.id, hc)

              eventuallyMaybeApiSubscriptionFields.map(maybeApiSubscriptionFields => maybeApiSubscriptionFields.fold(())(apiSubscriptionFields =>
                pushOrPullService.pushOrPull(workItem, apiSubscriptionFields, false).foreach(ok =>
                  if (ok) {
                    // if we are able to push/pull we flip statues from PF -> F for this CsId by side effect - we do not wait for this to complete
                    //changing status to failed makes the item eligible for retry
                    notificationWorkItemRepo.unblockFailedAndBlockedByCsId(csid).foreach { count =>
                      logger.info(s"Unblock - number of notifications set from PermanentlyFailed to Failed = $count for CsId ${csid.toString}")}})))
            case None =>
              logger.info(s"Unblock found no PermanentlyFailed notifications for CsId ${csid.toString}")
          }
        }
      }
      ()
    }
    }
  }
}
