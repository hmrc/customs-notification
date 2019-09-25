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

package uk.gov.hmrc.customs.notification.connectors

import javax.inject.{Inject, Singleton}
import play.api.http.MimeTypes
import play.mvc.Http.HeaderNames.{CONTENT_TYPE, USER_AGENT}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, CustomsNotificationConfig}
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationQueueConnector @Inject()(http: HttpClient, logger: CdsLogger, configServices: CustomsNotificationConfig)
                                          (implicit ec: ExecutionContext) {

  //TODO: handle POST failure scenario after Trade Test
  def enqueue(request: ClientNotification): Future[HttpResponse] = {

    implicit val hc: HeaderCarrier = HeaderCarrier() // Note we do not propagate HeaderCarrier values
    val url = configServices.notificationQueueConfig.url
    val maybeBadgeId: Option[(String, String)] = request.notification.getHeaderAsTuple(X_BADGE_ID_HEADER_NAME)
    val maybeCorrelationId: Option[(String, String)] = request.notification.getHeaderAsTuple(X_CORRELATION_ID_HEADER_NAME)

    val headers: Seq[(String, String)] = Seq(
      (CONTENT_TYPE, MimeTypes.XML),
      (USER_AGENT, "Customs Declaration Service"),
      (X_CONVERSATION_ID_HEADER_NAME, request.notification.conversationId.toString()),
      (SUBSCRIPTION_FIELDS_ID_HEADER_NAME, request.csid.toString())
    ) ++ extract(maybeBadgeId) ++ extract(maybeCorrelationId)

    val notification = request.notification

    logger.debug(s"Attempting to send notification to queue\nheaders=$headers}")

    http.POSTString[HttpResponse](url, notification.payload, headers)
      .recoverWith {
        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
      }
      .recoverWith {
        case e: Throwable =>
          logger.error(s"Call to notification queue failed. url=$url")
          Future.failed(e)
      }
  }

  private def extract(maybeValue: Option[(String, String)]) = maybeValue.fold(Seq.empty[(String, String)])(Seq(_))
}
