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
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.util.Errors.Non2xxResponseException
import uk.gov.hmrc.customs.notification.models.requests.CustomsNotificationsMetricsRequest
import uk.gov.hmrc.customs.notification.util.NoAuditHttpClient
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpReads, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CustomsNotificationMetricsConnector @Inject()(http: NoAuditHttpClient,
                                                    logger: CdsLogger,
                                                    config: CustomsNotificationConfig)(implicit ec: ExecutionContext) {

  def post[A](request: CustomsNotificationsMetricsRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    val headers: Seq[(String, String)] = Seq((CONTENT_TYPE, MimeTypes.JSON), (ACCEPT, MimeTypes.JSON))
    val updatedHeaderCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = headers)
    val url = config.notificationMetricsConfig.baseUrl

    logger.debug(s"Sending request to customs notification metrics service. Url: $url Payload: ${request.toString}")
    http.POST[CustomsNotificationsMetricsRequest, HttpResponse](url, request)(wts = CustomsNotificationsMetricsRequest.format, rds = HttpReads[HttpResponse], hc = updatedHeaderCarrier, ec = ec).map { response =>
      val status: Int = response.status

      if(status >= 200 && status < 300){
        logger.debug(s"[conversationId=${request.conversationId}]: customs notification metrics sent successfully")
        ()
      } else{
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
