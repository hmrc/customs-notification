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

package uk.gov.hmrc.customs.notification.connectors

import javax.inject.{Inject, Singleton}
import play.mvc.Http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.mvc.Http.MimeTypes.JSON
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, CustomsNotificationMetricsRequest}
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CustomsNotificationMetricsConnector @Inject()(http: HttpClient,
                                                    logger: CdsLogger,
                                                    config: CustomsNotificationConfig) {

  private implicit val hc: HeaderCarrier = HeaderCarrier(
    extraHeaders = Seq(ACCEPT -> JSON, CONTENT_TYPE -> JSON)
  )

  def post[A](request: CustomsNotificationMetricsRequest): Future[Unit] = {
    post(request, config.notificationMetricsConfig.baseUrl)
  }

  private def post[A](request: CustomsNotificationMetricsRequest, url: String): Future[Unit] = {

    logger.debug(s"Sending request to customs notification metrics service. Url: $url Payload: ${request.toString}")
    http.POST[CustomsNotificationMetricsRequest, HttpResponse](url, request).map{ _ =>
      logger.debug(s"[conversationId=${request.conversationId}]: customs notification metrics sent successfully")
      ()
    }.recoverWith {
      case httpError: HttpException => Future.failed(new RuntimeException(httpError))
      case e: Throwable =>
        logger.warn(s"Call to customs notification metrics service failed. url=$url")
        Future.failed(e)
    }
  }
}
