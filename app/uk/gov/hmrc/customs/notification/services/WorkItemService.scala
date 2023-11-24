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

import com.kenshoo.play.metrics.Metrics
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.connectors.HttpConnector
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import java.time.ZonedDateTime
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class WorkItemService @Inject()(repository: NotificationRepo,
                                httpConnector: HttpConnector,
                                logger: NotificationLogger,
                                metrics: Metrics,
                                config: AppConfig,
                                now: () => ZonedDateTime,
                                sendNotificationService: SendNotificationService)(implicit ec: ExecutionContext) {
  def processOne(): Future[Boolean] = {
    val before = now().toInstant
    val eventuallyProcessedOne: Future[Boolean] = repository.pullOutstanding(before, before).flatMap {
      case Some(firstOutstandingNotificationWorkItem) =>
        metrics.defaultRegistry.counter("declaration-digital-notification-retry-total-counter").inc()
        implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

//          httpConnector.get(apiSubsFieldsRequest).flatMap()
//        eventuallyMaybeApiSubscriptionFields.map { maybeApiSubscriptionFields =>
//          maybeApiSubscriptionFields.map(apiSubscriptionFields => SendNotificationService.send(firstOutstandingNotificationWorkItem, apiSubscriptionFields, false))
//        }
        Future.successful(true)
      case None =>
        Future.successful(false)
    }
    eventuallyProcessedOne
  }
}
