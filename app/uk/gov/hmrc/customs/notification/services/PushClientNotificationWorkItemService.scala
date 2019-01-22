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
import uk.gov.hmrc.customs.notification.connectors.{CustomsNotificationMetricsConnector, GoogleAnalyticsSenderConnector, PushNotificationServiceWorkItemConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class PushClientNotificationWorkItemService @Inject()(pushNotificationServiceWorkItemConnector: PushNotificationServiceWorkItemConnector,
                                                      gaConnector: GoogleAnalyticsSenderConnector,
                                                      notificationLogger: NotificationLogger,
                                                      metricsConnector: CustomsNotificationMetricsConnector,
                                                      dateTimeService: DateTimeService) {

  //TODO remove this after log refactoring
  private implicit val hc = HeaderCarrier()

  def send(apiSubscriptionFieldsResponse: ApiSubscriptionFieldsResponse, notificationWorkItem: NotificationWorkItem): Future[Boolean] = {
    val pushNotificationRequest = pushNotificationRequestFrom(apiSubscriptionFieldsResponse.fields, notificationWorkItem)

    notificationWorkItem.metricsStartDateTime.fold() { startTime =>
      metricsConnector.post(CustomsNotificationsMetricsRequest(
        "NOTIFICATION", notificationWorkItem.notification.conversationId, startTime.toZonedDateTime, dateTimeService.zonedDateTimeUtc))
    }

    notificationLogger.debug(s"pushing notification $notificationWorkItem")
    pushNotificationServiceWorkItemConnector.send(pushNotificationRequest).recover {
      case t: Throwable =>
        notificationLogger.error(s"failed to push $pushNotificationRequest due to: ${t.getMessage}")
        false
    }
  }

  private def pushNotificationRequestFrom(declarantCallbackData: DeclarantCallbackData,
                                          notificationWorkItem: NotificationWorkItem): PushNotificationRequest = {

    PushNotificationRequest(
      notificationWorkItem.id.id.toString,
      PushNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        notificationWorkItem.notification.conversationId.id.toString,
        notificationWorkItem.notification.headers,
        notificationWorkItem.notification.payload
      ))
  }

}
