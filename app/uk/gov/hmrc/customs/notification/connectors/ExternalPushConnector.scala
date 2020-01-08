/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.connectors.CustomsHttpReads.readRaw
import uk.gov.hmrc.customs.notification.domain.PushNotificationRequestBody.jsonFormat
import uk.gov.hmrc.customs.notification.domain.{HttpResultError, PushNotificationRequest, PushNotificationRequestBody, ResultError}
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse, Upstream4xxResponse, Upstream5xxResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ExternalPushConnector @Inject()(http: HttpClient,
                                      logger: CdsLogger,
                                      serviceConfigProvider: ServiceConfigProvider)
                                     (implicit ec: ExecutionContext) extends MapResultError {

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  def send(pushNotificationRequest: PushNotificationRequest)(implicit hc: HeaderCarrier): Future[Either[ResultError, HttpResponse]] = {
    implicit val headerCarrier: HeaderCarrier = hc.withExtraHeaders(outboundHeaders: _*)
    doSend(pushNotificationRequest)(headerCarrier)
  }

  private def doSend(pnr: PushNotificationRequest)(implicit hc: HeaderCarrier): Future[Either[ResultError, HttpResponse]] = {
    val url = serviceConfigProvider.getConfig("public-notification").url

    val msg = "Calling external push notification service"
    logger.debug(s"$msg url=${pnr.body.url} \nheaders=${hc.headers} \npayload= ${pnr.body.xmlPayload}")

    http.POST[PushNotificationRequestBody, HttpResponse](url, pnr.body)
      .map[Either[ResultError, HttpResponse]]{ httpResponse =>
      Right(httpResponse)
    }
    .recoverWith{
      case upstream4xx: Upstream4xxResponse =>
        logger.error(upstream4xx.message, upstream4xx)
        Future.successful(Left(HttpResultError(upstream4xx.upstreamResponseCode, upstream4xx)))
      case upstream5xx: Upstream5xxResponse =>
        logger.error(upstream5xx.message, upstream5xx)
        Future.successful(Left(HttpResultError(upstream5xx.upstreamResponseCode, upstream5xx)))
      case httpException: HttpException =>
        logger.error(httpException.message, httpException)
        Future.successful(Left(HttpResultError(httpException.responseCode, httpException)))
      case NonFatal(e) =>
        val error: ResultError = mapResultError(e)
        Future.successful(Left(error))
    }
  }
}
