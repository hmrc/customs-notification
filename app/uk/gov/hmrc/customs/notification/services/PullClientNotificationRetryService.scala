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
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


@Singleton
class PullClientNotificationRetryService @Inject()(notificationQueueConnector: NotificationQueueConnector,
                                                   logger: CdsLogger) {

  def send(notificationWorkItem: NotificationWorkItem): Future[Boolean] = {
    val clientNotification = clientNotificationFrom(notificationWorkItem)

    notificationQueueConnector.enqueue(clientNotification).map { _ =>
      logger.info(logMsgPrefix(clientNotification) + "Notification has been passed on to PULL service")
      true
    }
    .recover {
      case t: Throwable =>
        logger.error(logMsgPrefix(clientNotification) + "Failed to pass the notification to PULL service", t)
        false
    }
  }

  private def clientNotificationFrom(notificationWorkItem: NotificationWorkItem): ClientNotification = {
    ClientNotification(notificationWorkItem.id, notificationWorkItem.notification, None, None)
  }

}
