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
import play.api.libs.json.Json
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class GoogleAnalyticsSenderConnector @Inject()(http: HttpClient,
                                               logger: NotificationLogger,
                                               serviceConfigProvider: ServiceConfigProvider,
                                               configuration: Configuration) {


  private val url = serviceConfigProvider.getConfig("google-analytics-sender").url

  private val gaTrackingId = configuration.getString("googleAnalytics.trackingId").get
  private val gaClientId = configuration.getString("googleAnalytics.clientId").get
  private val gaEventValue = configuration.getInt("googleAnalytics.eventValue").get

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  def send(eventName: String, message: String)(implicit hc: HeaderCarrier): Future[Unit] = {

    http.POST(url, Json.parse(
      s"""
         | {
         |   "payload": "v=1&t=event&tid=$gaTrackingId&cid=$gaClientId&ec=CDS&ea=$eventName&el=$message&ev=$gaEventValue"
         | }
      """.stripMargin), outboundHeaders).map(_ => ()).recover {
      case ex: Throwable =>
        logger.error(s"Call to GoogleAnalytics sender service failed. POST url= $url")
    }
  }

  //  private def doSend(publicNotificationRequest: PublicNotificationRequest): Future[HttpResponse] = {
  //    val url = serviceConfigProvider.getConfig("public-notification").url
  //
  //    implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = outboundHeaders)
  //    val msg = "Calling public notification service"
  //    logger.debug(msg, url, payload = publicNotificationRequest.body.toString)
  //
  //    val postFuture = http
  //      .POST[PublicNotificationRequestBody, HttpResponse](url, publicNotificationRequest.body)
  //      .recoverWith {
  //        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
  //      }
  //      .recoverWith {
  //        case e: Throwable =>
  //          logger.error(s"Call to public notification service failed. POST url=$url")
  //          Future.failed(e)
  //      }
  //    postFuture
  //  }

}
