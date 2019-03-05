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
import play.api.http.HeaderNames.{ACCEPT, AUTHORIZATION, CONTENT_TYPE, USER_AGENT}
import play.mvc.Http.MimeTypes.XML
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{NonHttpError, PushNotificationRequest, ResultError}
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal


@Singleton
class InternalPushConnector @Inject()(http: HttpClient,
                                      logger: CdsLogger) extends MapResultError {

  def send(pnr: PushNotificationRequest): Future[Either[ResultError, HttpResponse]] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val outBoundHeaders: Seq[(String, String)] = pnr.body.outboundCallHeaders.map(h => (h.name, h.value))

    val headers: Seq[(String, String)] = Seq(
      AUTHORIZATION -> pnr.body.authHeaderToken,
      CONTENT_TYPE -> XML,
      ACCEPT -> XML,
      X_CONVERSATION_ID_HEADER_NAME -> pnr.body.conversationId,
      USER_AGENT -> "Customs Declaration Service"
    ) ++ outBoundHeaders

    logger.debug(s"Calling internal push notification service url=${pnr.body.url} \nheaders=$headers \npayload= ${pnr.body.xmlPayload}")

    try {
      val eventualHttpResponse = http.POSTString[HttpResponse](pnr.body.url, pnr.body.xmlPayload, headers)
      val eventualEither: Future[Either[ResultError, HttpResponse]] = eventualHttpResponse.map(httpResponse => Right(httpResponse))

      eventualEither.recoverWith {
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
