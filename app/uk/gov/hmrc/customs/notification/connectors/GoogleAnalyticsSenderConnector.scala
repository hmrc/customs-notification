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
import play.api.Configuration
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.libs.json.Json.parse
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class GoogleAnalyticsSenderConnector @Inject()(http: HttpClient,
                                               logger: NotificationLogger,
                                               configService: ConfigService) {


  private val gaSenderConfigs = configService.googleAnalyticsSenderConfig

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  private def payload(eventName: String, eventLabel: String) = {
    parse(
      s"""
         | {
         |   "payload": "v=1&t=event&tid=${gaSenderConfigs.gaTrackingId}&cid=${gaSenderConfigs.gaClientId}&ec=CDS&ea=$eventName&el=$eventLabel&ev=${gaSenderConfigs.gaEventValue}"
         | }""".stripMargin)
  }

  def send(eventName: String, message: String)(implicit hc: HeaderCarrier): Future[Unit] = {

    http.POST(gaSenderConfigs.url, payload(eventName, message), outboundHeaders)
      .map { _ =>
        logger.debug(s"Successfully sent GA event to ${gaSenderConfigs.url}, eventName= $eventName, eventLabel= $message, trackingId= ${gaSenderConfigs.gaTrackingId}")
        ()
      }.recover {
      case ex: Throwable =>
        logger.error(s"Call to GoogleAnalytics sender service failed. POST url= ${gaSenderConfigs.url}, eventName= $eventName, eventLabel= $message, reason= ${ex.getMessage}")
    }
  }
}
