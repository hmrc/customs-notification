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

import javax.inject.{Inject, Singleton}
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.mvc.Http.MimeTypes.XML
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{HttpResultError, NonHttpError, PushNotificationRequest, ResultError}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


@Singleton
class InternalPushConnector @Inject()(http: HttpClient,
                                      logger: CdsLogger)
                                     (implicit ec: ExecutionContext) extends MapResultError {

  def send(pnr: PushNotificationRequest)(implicit hc: HeaderCarrier): Future[Either[ResultError, HttpResponse]] = {
    val outBoundHeaders: Seq[(String, String)] = pnr.body.outboundCallHeaders.map(h => (h.name, h.value))

    val headers: Seq[(String, String)] = Seq(
      CONTENT_TYPE -> XML,
      ACCEPT -> XML,
      X_CONVERSATION_ID_HEADER_NAME -> pnr.body.conversationId
    ) ++ outBoundHeaders

    implicit val headerCarrier: HeaderCarrier = hc.copy(authorization = Some(Authorization(pnr.body.authHeaderToken))).withExtraHeaders(headers:_*)
    doSend(pnr)(headerCarrier)
  }

  private def doSend(pnr: PushNotificationRequest)(implicit hc: HeaderCarrier): Future[Either[ResultError, HttpResponse]] = {

    logger.debug(s"Calling internal push notification service url=${pnr.body.url} \nheaders=${hc.headers} \npayload= ${pnr.body.xmlPayload}")

    try {
      val eventualHttpResponse = http.POSTString[HttpResponse](pnr.body.url, pnr.body.xmlPayload)
      val eventualEither: Future[Either[ResultError, HttpResponse]] = eventualHttpResponse.map(httpResponse => Right(httpResponse))

      eventualEither.recoverWith {
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
          val resultError = mapResultError(e)
          Future.successful(Left(resultError))
      }
    } catch {
      case NonFatal(e) => // if pnr.body.url is a malformed URL then HTTP VERBs throws an exception before it generates a Future
        Future.successful(Left(NonHttpError(e)))
    }
  }
}
