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

import java.util.concurrent.TimeUnit

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.http.HttpResponse

import scala.concurrent.Await
import scala.concurrent.duration.Duration


@Singleton
class PushClientNotificationService @Inject() (outboundSwitchService: OutboundSwitchService,
                                               logger: CdsLogger,
                                               metricsConnector: CustomsNotificationMetricsConnector,
                                               dateTimeService: DateTimeService) {

  def send(apiSubscriptionFields: ApiSubscriptionFields, clientNotification: ClientNotification): Boolean = {

    val pushNotificationRequest = pushNotificationRequestFrom(apiSubscriptionFields.fields, clientNotification)

    clientNotification.metricsStartDateTime.fold() { startTime =>
      metricsConnector.post(CustomsNotificationsMetricsRequest(
        "NOTIFICATION", clientNotification.notification.conversationId, startTime.toZonedDateTime, dateTimeService.zonedDateTimeUtc))
    }

    implicit val loggingContext: HasId = new HasId {
      override def idName: String = "conversationId"
      override def idValue: String = clientNotification.notification.conversationId.toString
    }

    val either: Either[ResultError, HttpResponse] = scala.concurrent.blocking {
      Await.result(outboundSwitchService.send(ClientId(apiSubscriptionFields.clientId), pushNotificationRequest), Duration.apply(25, TimeUnit.SECONDS))
    }
    either match {
      case Right(_) =>
        logger.debug(s"${logMsgPrefix(clientNotification)} Notification has been pushed")
        true
      case Left(_) =>
        logger.error(s"${logMsgPrefix(clientNotification)} Notification push failed")
        false
    }

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
