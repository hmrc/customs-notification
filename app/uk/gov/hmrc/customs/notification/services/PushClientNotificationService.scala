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
import uk.gov.hmrc.customs.notification.connectors.{CustomsNotificationMetricsConnector, GoogleAnalyticsSenderConnector, PushNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._

import scala.concurrent.Await
import scala.concurrent.duration.Duration


@Singleton
class PushClientNotificationService @Inject() (pushNotificationServiceConnector: PushNotificationServiceConnector,
                                               gaConnector: GoogleAnalyticsSenderConnector,
                                               notificationLogger: NotificationLogger,
                                               metricsConnector: CustomsNotificationMetricsConnector,
                                               dateTimeService: DateTimeService) {


  private implicit val hc = HeaderCarrier()

  def send(declarantCallbackData: DeclarantCallbackData, clientNotification: ClientNotification): Boolean = {

    val pushNotificationRequest = pushNotificationRequestFrom(declarantCallbackData, clientNotification)

    clientNotification.metricsStartDateTime.fold() { startTime =>
      metricsConnector.post(CustomsNotificationsMetricsRequest(
        "NOTIFICATION", clientNotification.notification.conversationId, startTime.toZonedDateTime, dateTimeService.zonedDateTimeUtc))
    }

    val result = scala.concurrent.blocking {
      Await.ready(pushNotificationServiceConnector.send(pushNotificationRequest), Duration.apply(25, TimeUnit.SECONDS)).value.get.isSuccess
    }
    if (result) {
      notificationLogger.debug(s"${logMsgPrefix(clientNotification)} Notification has been pushed")
      gaConnector.send("notificationPushRequestSuccess", s"[ConversationId=${pushNotificationRequest.body.conversationId}] A notification has been pushed successfully")
    } else {
      notificationLogger.error(s"${logMsgPrefix(clientNotification)} Notification push failed")
      gaConnector.send("notificationPushRequestFailed", s"[ConversationId=${pushNotificationRequest.body.conversationId}] A notification Push request failed")
    }
    result
  }

  private def pushNotificationRequestFrom(declarantCallbackData: DeclarantCallbackData,
                                            clientNotification: ClientNotification): PushNotificationRequest = {

    PushNotificationRequest(
      clientNotification.csid.id.toString,
      PushNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        clientNotification.notification.conversationId.id.toString,
        clientNotification.notification.headers,
        clientNotification.notification.payload
      ))
  }

}
