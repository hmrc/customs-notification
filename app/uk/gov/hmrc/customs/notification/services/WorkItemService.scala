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

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import uk.gov.hmrc.customs.notification.config.{AppConfig, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector}
import uk.gov.hmrc.customs.notification.models.ApiSubscriptionFields
import uk.gov.hmrc.customs.notification.models.repo.NotificationWorkItem
import uk.gov.hmrc.customs.notification.util.HeaderNames.NOTIFICATION_ID_HEADER_NAME
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.customs.notification.util.{DateTimeHelper, NotificationLogger, NotificationWorkItemRepo}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class WorkItemService @Inject()(repository: NotificationWorkItemRepo,
                                logger: NotificationLogger,
                                metrics: Metrics,
                                apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                                customsNotificationConfig: CustomsNotificationConfig,
                                notificationQueueConnector: NotificationQueueConnector,
                                config: AppConfig,
                                pushOrPullService: PushOrPullService)(implicit ec: ExecutionContext){
  private lazy val registry: MetricRegistry = metrics.defaultRegistry
  private val metricName = "declaration-digital-notification-retry-total"

  def processOne(): Future[Boolean] = {
    val failedBefore = DateTimeHelper.zonedDateTimeUtc.toInstant
    val availableBefore = failedBefore
    val eventuallyProcessedOne: Future[Boolean] = repository.pullOutstanding(failedBefore, availableBefore).flatMap {
      case Some(firstOutstandingNotificationWorkItem) =>
        incrementCountMetric(metricName, firstOutstandingNotificationWorkItem)
        implicit val loggingContext = firstOutstandingNotificationWorkItem.item
        implicit val maybeUpdatedHeaderCarrier: HeaderCarrier = firstOutstandingNotificationWorkItem.item.notification.notificationId.fold(HeaderCarrier())(notificationId =>
          HeaderCarrier().withExtraHeaders(Seq((NOTIFICATION_ID_HEADER_NAME, notificationId.toString)): _*))
        logger.debug(s"attempting retry of $firstOutstandingNotificationWorkItem")

        val eventuallyMaybeApiSubscriptionFields: Future[Option[ApiSubscriptionFields]] = pushOrPullService.getApiSubscriptionFields(firstOutstandingNotificationWorkItem.item, firstOutstandingNotificationWorkItem.id, maybeUpdatedHeaderCarrier)
        eventuallyMaybeApiSubscriptionFields.map{ maybeApiSubscriptionFields =>
          maybeApiSubscriptionFields.fold(())(apiSubscriptionFields => pushOrPullService.pushOrPull(firstOutstandingNotificationWorkItem, apiSubscriptionFields, false))
        }
        Future.successful(true)
      case None =>
        Future.successful(false)
    }
    eventuallyProcessedOne
  }

  private def incrementCountMetric(metric: String, workItem: WorkItem[NotificationWorkItem]): Unit = {
    implicit val loggingContext = workItem.item
    logger.debug(s"incrementing counter for metric: $metric")
    registry.counter(s"$metric-counter").inc()
  }
}
