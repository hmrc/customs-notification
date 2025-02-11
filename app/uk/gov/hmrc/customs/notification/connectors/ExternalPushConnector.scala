/*
 * Copyright 2024 HM Revenue & Customs
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
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.libs.json.Json
import uk.gov.hmrc.customs.notification.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.ISSUE_DATE_TIME_HEADER
import uk.gov.hmrc.customs.notification.domain.PushNotificationRequestBody.jsonFormat
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.http.Non2xxResponseException
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2
import play.api.libs.ws.JsonBodyWritables._

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ExternalPushConnector @Inject()(http: HttpClientV2,
                                      logger: NotificationLogger,
                                      serviceConfigProvider: ServiceConfigProvider)
                                     (implicit ec: ExecutionContext) extends MapResultError with HttpErrorFunctions {

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  def send(pushNotificationRequest: PushNotificationRequest)(implicit hc: HeaderCarrier, rm: HasId): Future[Either[ResultError, HttpResponse]] = {
    implicit val headerCarrier: HeaderCarrier = hc.withExtraHeaders(outboundHeaders: _*)
    doSend(removeDateHeaderFromRequestBody(pushNotificationRequest))(headerCarrier, rm)
  }

  private def doSend(pnr: PushNotificationRequest)(implicit hc: HeaderCarrier, rm: HasId): Future[Either[ResultError, HttpResponse]] = {
    val url = serviceConfigProvider.getConfig("public-notification").url
    val headerNames = HeaderNames.explicitlyIncludedHeaders
    logger.debug(s"hc.extraHeaders [${hc.extraHeaders}]")
    val headers = hc.headers(headerNames) ++ hc.extraHeaders
//    val headers = Seq()
    logger.debug(s"Calling external push notification service [$url] \nheaders=[$headers] \npayload=[${pnr.body}]")
    logger.info(s" ######## url[$url]")
    http
      .post(url"$url")
      .setHeader(headers: _*)
      .withBody(Json.toJson(pnr.body))
//      .withBody(Json.toJson("this ain't testing properly"))
      .execute[HttpResponse]
      .map[Either[ResultError, HttpResponse]] { response =>
        response.status match {
          case status if is2xx(status) =>
            Right(response)

          case status => //1xx, 3xx, 4xx, 5xx
            val httpException = new Non2xxResponseException(status)
            logger.warn(s"Failed to push notification. Response status ${response.status} and response body ${response.body}")
            Left(HttpResultError(status, httpException))
        }
      }
      .recoverWith {
        case httpException: HttpException =>
          logger.error(httpException.message, httpException)
          Future.successful(Left(HttpResultError(httpException.responseCode, httpException)))
        case NonFatal(e) =>
          val error: ResultError = mapResultError(e)
          Future.successful(Left(error))
      }
  }

  private def removeDateHeaderFromRequestBody(pushNotificationRequest: PushNotificationRequest): PushNotificationRequest = {
    val headersWithoutDate: Seq[Header] = pushNotificationRequest.body.outboundCallHeaders.filterNot(h => h.name.equals(ISSUE_DATE_TIME_HEADER))
    pushNotificationRequest.copy(body = pushNotificationRequest.body.copy(outboundCallHeaders = headersWithoutDate))
  }
}
