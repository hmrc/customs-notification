/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@Singleton
class PullClientNotificationService @Inject() (notificationQueueConnector: NotificationQueueConnector,
                                               notificationsLogger: NotificationLogger) {

  def send(clientNotification: ClientNotification): Boolean = {

    Await.ready(
      notificationQueueConnector.enqueue(clientNotification),
      // This timeout value does not matter as the httpVerbs timeout is the real timeout for us which is currently set as 20Seconds.
      Duration.apply(25, TimeUnit.SECONDS)
    ).value.get.isSuccess
  }

}
