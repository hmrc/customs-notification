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
import uk.gov.hmrc.customs.notification.config.RetryConfig

import javax.inject._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt


@Singleton
class RetryScheduler @Inject()(actorSystem: ActorSystem,
                               retryService: RetryService,
                               config: RetryConfig)(implicit ec: ExecutionContext) {
  if (config.enabled) {
    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.failedAndBlockedDelay)(() => retryService.retryFailedAndBlocked())
    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.failedButNotBlockedDelay)(() => retryService.retryFailedAndNotBlocked())
  }
}
