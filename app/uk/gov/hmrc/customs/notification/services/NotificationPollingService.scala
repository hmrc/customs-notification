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
import uk.gov.hmrc.customs.notification.domain.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


@Singleton
class NotificationPollingService @Inject()(config: CustomsNotificationConfig,
                                            actorSystem: ActorSystem,
                                            clientNotificationRepo: ClientNotificationRepo,
                                            notificationDispatcher: NotificationDispatcher,
                                            logger: CdsLogger)(implicit executionContext: ExecutionContext) {

  if (config.pushNotificationConfig.pollingEnabled) {
    val pollingDelay: FiniteDuration = config.pushNotificationConfig.pollingDelay

      actorSystem.scheduler.schedule(0.seconds, pollingDelay) {
        clientNotificationRepo.fetchDistinctNotificationCSIDsWhichAreNotLocked().map(csIdSet => {
          logger.debug(s"polling service about to process ${csIdSet.size} notifications")
          notificationDispatcher.process(csIdSet)
        })
      }
    } else {
      logger.info("push notification polling disabled")
  }
}
