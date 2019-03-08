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
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Singleton
class LogNotificationCountsPollingService @Inject()(clientNotificationRepo: ClientNotificationRepo,
                                                    actorSystem: ActorSystem,
                                                    configService: CustomsNotificationConfig,
                                                    logger: CdsLogger)(implicit executionContext: ExecutionContext){

  private val logCountsEnabled = configService.logNotificationCountsPollingConfig.pollingEnabled
  private val interval = configService.logNotificationCountsPollingConfig.pollingInterval

  if (logCountsEnabled) {
    actorSystem.scheduler.schedule(0.seconds, interval) {
      clientNotificationRepo.notificationCountByCsid().map { counts =>
        val notificationCounts = counts.mkString("\n")
        logger.info(s"current notification counts in descending order: \n$notificationCounts")
      }.recover{
        case e =>
          logger.error(s"failed to get notification counts due to ${e.getMessage}")
      }
    }
  }
}
