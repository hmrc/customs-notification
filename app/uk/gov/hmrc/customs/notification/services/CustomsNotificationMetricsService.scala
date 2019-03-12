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

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationsMetricsRequest, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._

import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CustomsNotificationMetricsService @Inject() (
  logger: NotificationLogger,
  metricsConnector: CustomsNotificationMetricsConnector,
  dateTimeService: DateTimeService
)(implicit ec: ExecutionContext) {

  def notificationMetric(notificationWorkItem: NotificationWorkItem): Future[Unit] = {
    implicit val hasId = notificationWorkItem

    notificationWorkItem.metricsStartDateTime.fold(Future.successful(())) { startTime =>
      lazy val request = CustomsNotificationsMetricsRequest(
        "NOTIFICATION",
        notificationWorkItem.notification.conversationId,
        startTime.toZonedDateTime,
        dateTimeService.zonedDateTimeUtc
      )
      metricsConnector.post(request).recover{
        case NonFatal(e) =>
          logger.error("Error calling customs metrics service", e)
      }
    }
  }

}
