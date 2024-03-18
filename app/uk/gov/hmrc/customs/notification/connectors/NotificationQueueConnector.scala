/*
 * Copyright 2024 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, CustomsNotificationConfig, NotificationId}
import uk.gov.hmrc.customs.notification.http.Non2xxResponseException
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpClient, HttpErrorFunctions, HttpException, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationQueueConnector @Inject()(http: HttpClient, logger: CdsLogger, configServices: CustomsNotificationConfig)
                                          (implicit ec: ExecutionContext) extends HttpErrorFunctions {

  def enqueue(request: ClientNotification)(implicit hc: HeaderCarrier): Future[HttpResponse] = {

    val url = configServices.notificationQueueConfig.url
    val maybeBadgeId: Option[(String, String)] = request.notification.getHeaderAsTuple(X_BADGE_ID_HEADER_NAME)
    val maybeCorrelationId: Option[(String, String)] = request.notification.getHeaderAsTuple(X_CORRELATION_ID_HEADER_NAME)

    val headers: Seq[(String, String)] = Seq(
      (CONTENT_TYPE, MimeTypes.XML),
      (X_CONVERSATION_ID_HEADER_NAME, request.notification.conversationId.toString()),
      (SUBSCRIPTION_FIELDS_ID_HEADER_NAME, request.csid.toString())
    ) ++ extract(maybeBadgeId) ++ extract(maybeCorrelationId) ++ maybeAddNotificationId(request.notification.notificationId)
    val headerCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = headers)

    val notification = request.notification

    val headerNames: Seq[String] = HeaderNames.explicitlyIncludedHeaders
    val headersToLog = hc.headers(headerNames) ++ hc.extraHeaders

    logger.debug(s"Attempting to send notification to queue\nheaders=${headersToLog}} \npayload=${notification.payload}")

    http.POSTString[HttpResponse](url, notification.payload)(readRaw, headerCarrier, ec).flatMap { response =>
        response.status match {
          case status if is2xx(status) =>
            Future.successful(response)

          case status =>
            Future.failed(new Non2xxResponseException(status))
        }
      }.recoverWith {
        case httpError: HttpException =>
          Future.failed(new RuntimeException(httpError))

        case e: Throwable =>
          logger.error(s"Call to notification queue failed. url=$url")
          Future.failed(e)
      }
  }

  private def extract(maybeValue: Option[(String, String)]) = maybeValue.fold(Seq.empty[(String, String)])(Seq(_))

  private def maybeAddNotificationId(maybeNotificationId: Option[NotificationId]): Seq[(String, String)] = {
    maybeNotificationId.fold(Seq.empty[(String, String)]){id => Seq((NOTIFICATION_ID_HEADER_NAME, id.toString)) }
  }

}
