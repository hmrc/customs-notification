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

import javax.inject.Singleton

import com.google.inject.Inject
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.domain.{PublicNotificationRequest, PublicNotificationRequestBody}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.WSPostImpl
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.config.inject.AppName

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class PublicNotificationServiceConnector @Inject()(httpPost: WSPostImpl,
                                                   appName: AppName,
                                                   logger: NotificationLogger,
                                                   serviceConfigProvider: ServiceConfigProvider) {

  private def outboundHeaders(conversationId: String) = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON),
    (X_CONVERSATION_ID_HEADER_NAME, conversationId)
  )

  // TODO: recover on failure to enqueue to notification queue
  def send(publicNotificationRequest: PublicNotificationRequest): Future[Unit] = {
    doSend(publicNotificationRequest) map ( _ => () )
  }

  private def doSend(publicNotificationRequest: PublicNotificationRequest): Future[HttpResponse] = {
    val url = serviceConfigProvider.getConfig("public-notification").url

    implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = outboundHeaders(publicNotificationRequest.conversationId))
    val msg = "Calling public notification service"
    logger.info(msg)
    logger.debug(msg, url, payload = publicNotificationRequest.body.toString)

    val postFuture = httpPost
      .POST[PublicNotificationRequestBody, HttpResponse](url, publicNotificationRequest.body)
      .recoverWith {
        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
      }
      .recoverWith {
        case e: Throwable =>
          logger.error(s"Call to public notification service failed. POST url=$url. Payload=${publicNotificationRequest.body}", e)
          Future.failed(e)
      }
    postFuture
  }

}
