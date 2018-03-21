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
import play.api.http.MimeTypes
import play.mvc.Http.HeaderNames.{AUTHORIZATION, CONTENT_TYPE, USER_AGENT}
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.X_BADGE_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.WSPostImpl
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}

import scala.Option.empty
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class NotificationQueueConnector @Inject()(httpPost: WSPostImpl, logger: NotificationLogger, configServices: ConfigService) {

  //TODO: handle POST failure scenario after Trade Test
  def enqueue(request: PublicNotificationRequest): Future[HttpResponse] = {
    implicit val hc: HeaderCarrier = HeaderCarrier() // Note we do not propagate HeaderCarrier values
    val url = configServices.notificationQueueConfig.url
    val maybeBadgeId: Option[String] = Map(request.body.outboundCallHeaders.map(x => x.name -> x.value): _*).get(X_BADGE_ID_HEADER_NAME)

    val headers: Seq[(String, String)] = Seq(
      (CONTENT_TYPE, MimeTypes.XML),
      (AUTHORIZATION, request.body.authHeaderToken),
      (USER_AGENT, "Customs Declaration Service"),
      (CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME, request.body.conversationId),
      (CustomHeaderNames.SUBSCRIPTION_FIELDS_ID_HEADER_NAME, request.clientSubscriptionId)

    ) ++ maybeBadgeId.fold(empty[(String, String)]){ x: String => Some((X_BADGE_ID_HEADER_NAME, x))}


    logger.debug(s"Attempting to send notification to queue\npayload=\n${request.body.xmlPayload}", headers)

    httpPost.POSTString[HttpResponse](url, request.body.xmlPayload, headers)
      .recoverWith {
        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
      }
      .recoverWith {
        case e: Throwable =>
          logger.error(s"Call to notification queue failed. url=$url")
          Future.failed(e)
      }
  }

}
