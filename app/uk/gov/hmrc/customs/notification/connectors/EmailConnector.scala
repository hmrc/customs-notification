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

import play.api.Logger
import play.api.libs.json.Json
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, SendEmailRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class EmailConnector @Inject()(http: HttpClient, logger: NotificationLogger, configService: CustomsNotificationConfig) {

  private val emailUrl = configService.pullExcludeConfig.emailUrl
  private implicit val hc = HeaderCarrier()

  def send(sendEmailRequest: SendEmailRequest): Future[Unit] = {

    logger.info(s"sending failed push notifications warnings email: ${Json.toJson(sendEmailRequest)}")

    http.POST[SendEmailRequest, HttpResponse](s"$emailUrl", sendEmailRequest).map { response =>
      Logger.debug(s"response status from email service was ${response.status}")
    }
    .recover {
      case e: Throwable =>
        logger.error(s"call to email service failed due to ${e.getMessage}. url=$emailUrl")
    }
  }
}
