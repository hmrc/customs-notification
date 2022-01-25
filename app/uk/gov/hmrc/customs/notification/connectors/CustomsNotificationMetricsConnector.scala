/*
 * Copyright 2022 HM Revenue & Customs
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
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, CustomsNotificationsMetricsRequest}
import uk.gov.hmrc.customs.notification.http.{NoAuditHttpClient, Non2xxResponseException}
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpException, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CustomsNotificationMetricsConnector @Inject()(http: NoAuditHttpClient,
                                                    logger: CdsLogger,
                                                    config: CustomsNotificationConfig)
                                                   (implicit ec: ExecutionContext) extends HttpErrorFunctions {

  private val headers = Seq(
    (CONTENT_TYPE, MimeTypes.JSON),
    (ACCEPT, MimeTypes.JSON)
  )

  def post[A](request: CustomsNotificationsMetricsRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    implicit val headerCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = headers)
    post(request, config.notificationMetricsConfig.baseUrl)(headerCarrier)
  }

  private def post[A](request: CustomsNotificationsMetricsRequest, url: String)(implicit hc: HeaderCarrier): Future[Unit] = {

    logger.debug(s"Sending request to customs notification metrics service. Url: $url Payload: ${request.toString}")
    http.POST[CustomsNotificationsMetricsRequest, HttpResponse](url, request).map{ response =>
      response.status match {
        case status if is2xx(status) =>
          logger.debug(s"[conversationId=${request.conversationId}]: customs notification metrics sent successfully")
          ()

        case status => //1xx, 3xx, 4xx, 5xx
          throw new Non2xxResponseException(status)
      }
    }.recoverWith {
      case httpError: HttpException =>
        logger.warn(s"[conversationId=${request.conversationId}]: Call to customs notification metrics service failed. url=$url httpError=${httpError.responseCode}", httpError)
        Future.failed(new RuntimeException(httpError))
      case e: Throwable =>
        logger.warn(s"[conversationId=${request.conversationId}]: Call to customs notification metrics service failed. url=$url", e)
        Future.failed(e)
    }
  }
}
