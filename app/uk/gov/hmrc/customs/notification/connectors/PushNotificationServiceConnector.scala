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

import com.google.inject.Inject
import javax.inject.Singleton
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.domain.{PushNotificationRequest, PushNotificationRequestBody}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class PushNotificationServiceConnector @Inject()(http: HttpClient,
                                                 logger: NotificationLogger,
                                                 serviceConfigProvider: ServiceConfigProvider) {

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  // TODO: recover on failure to enqueue to notification queue
  def send(pushNotificationRequest: PushNotificationRequest): Future[Unit] = {
    doSend(pushNotificationRequest) map (_ => () )
  }

  private def doSend(pushNotificationRequest: PushNotificationRequest): Future[HttpResponse] = {
    val url = serviceConfigProvider.getConfig("public-notification").url

    implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = outboundHeaders)
    val msg = "Calling public notification service"
    logger.debug(msg, url, payload = pushNotificationRequest.body.toString)

    val postFuture = http
      .POST[PushNotificationRequestBody, HttpResponse](url, pushNotificationRequest.body)
      .recoverWith {
        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
      }
      .recoverWith {
        case e: Throwable =>
          logger.error(s"Call to public notification service failed. POST url=$url")
          Future.failed(e)
      }
    postFuture
  }

}
