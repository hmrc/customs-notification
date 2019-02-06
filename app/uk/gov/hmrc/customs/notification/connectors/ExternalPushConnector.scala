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

import com.google.inject.Inject
import javax.inject.Singleton
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{PushNotificationRequest, PushNotificationRequestBody, ResultError}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class ExternalPushConnector @Inject()(http: HttpClient,
                                      logger: CdsLogger,
                                      serviceConfigProvider: ServiceConfigProvider) extends MapResultError {

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  // TODO: recover on failure to enqueue to notification queue
  def send(pushNotificationRequest: PushNotificationRequest): Future[Either[ResultError, HttpResponse]] = {
    doSend(pushNotificationRequest)
  }

  private def doSend(pnr: PushNotificationRequest): Future[Either[ResultError, HttpResponse]] = {
    val url = serviceConfigProvider.getConfig("public-notification").url

    implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = outboundHeaders)
    val msg = "Calling external push notification service"
    logger.debug(s"$msg url=${pnr.body.url} \nheaders=$outboundHeaders \npayload= ${pnr.body.xmlPayload}")

    http.POST[PushNotificationRequestBody, HttpResponse](url, pnr.body)
      .map[Either[ResultError, HttpResponse]]{ httpResponse =>
      Right(httpResponse)
    }
    .recoverWith{
      case NonFatal(e) =>
        val error: ResultError = mapResultError(e)
        Future.successful(Left(error))
    }
  }

}
