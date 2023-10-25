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

package uk.gov.hmrc.customs.notification.connectors

import javax.inject.{Inject, Singleton}
import play.api.http.MimeTypes
import play.mvc.Http.HeaderNames.CONTENT_TYPE
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.models.{ClientNotification, Notification}
import uk.gov.hmrc.customs.notification.util.HeaderNames.{NOTIFICATION_ID_HEADER_NAME, SUBSCRIPTION_FIELDS_ID_HEADER_NAME, X_BADGE_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME}
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpClient, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationQueueConnector @Inject()(http: HttpClient,
                                           logger: CdsLogger,
                                           notificationConfig: CustomsNotificationConfig)(implicit ec: ExecutionContext){
  def postToQueue(request: ClientNotification)(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    val url: String = notificationConfig.notificationQueueConfig.url
    val notification: Notification = request.notification
    val essentialHeaders: Seq[(String, String)] = Seq(
      (CONTENT_TYPE, MimeTypes.XML),
      (X_CONVERSATION_ID_HEADER_NAME, notification.conversationId.toString()),
      (SUBSCRIPTION_FIELDS_ID_HEADER_NAME, request.csid.toString))
    val essentialMaybeWithExtraHeaders: Seq[(String, String)] = addHeadersIfPresent(essentialHeaders, notification)
    val headerCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = essentialMaybeWithExtraHeaders)
    val notificationPayload: String = notification.payload

    logger.debug(s"Attempting to send notification to queue\nheaders=${hc.headers(HeaderNames.explicitlyIncludedHeaders) ++ hc.extraHeaders} \npayload=$notificationPayload")
    http.POSTString[HttpResponse](url, notificationPayload)(readRaw, headerCarrier, ec).recoverWith {
          //TODO put error here
      error => throw new RuntimeException(s"???")
    }
  }

  private def addHeadersIfPresent(essentialHeaders: Seq[(String, String)], notification: Notification): Seq[(String, String)] = {
    val maybeWithBadgeId: Seq[(String, String)] = notification.getHeaderAsTuple(X_BADGE_ID_HEADER_NAME).fold(essentialHeaders)(badgeIdKeyAndValue => essentialHeaders ++ Seq(badgeIdKeyAndValue))
    val maybeWithCorrelationId: Seq[(String, String)] = notification.getHeaderAsTuple(X_CORRELATION_ID_HEADER_NAME).fold(maybeWithBadgeId)(correlationIdKeyAndValue => maybeWithBadgeId ++ Seq(correlationIdKeyAndValue))
    notification.notificationId.fold(maybeWithCorrelationId)(notificationId => maybeWithCorrelationId ++ Seq((NOTIFICATION_ID_HEADER_NAME, notificationId.toString)))
  }
}
