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

import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, DeclarantCallbackData, PublicNotificationRequest, PublicNotificationRequestBody}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Await
import scala.concurrent.duration.Duration


@Singleton
class PushClientNotificationService @Inject() (publicNotificationServiceConnector: PublicNotificationServiceConnector,
                                               notificationLogger: NotificationLogger) {

  private implicit val hc = HeaderCarrier()
  def send(declarantCallbackData: DeclarantCallbackData, clientNotification: ClientNotification): Boolean = {

    val publicNotificationRequest = publicNotificationRequestFrom(declarantCallbackData, clientNotification)

    Await.ready(publicNotificationServiceConnector.send(publicNotificationRequest), Duration.apply(25, TimeUnit.SECONDS)).value.get.isSuccess
  }

  private def publicNotificationRequestFrom(declarantCallbackData: DeclarantCallbackData,
                                            clientNotification: ClientNotification): PublicNotificationRequest = {

    PublicNotificationRequest(
      clientNotification.csid.id.toString,
      PublicNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        clientNotification.notification.conversationId.id.toString,
        clientNotification.notification.headers,
        clientNotification.notification.payload
      ))
  }

}
