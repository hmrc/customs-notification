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

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, DeclarantCallbackData}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

@Singleton
class PushClientNotificationService @Inject()(publicNotificationServiceConnector: PublicNotificationServiceConnector,
                                              notificationsLogger: NotificationLogger) {

  def send(declarantCallbackData: DeclarantCallbackData, clientNotification: ClientNotification): Boolean = {

    true
  }

}
