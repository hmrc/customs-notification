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
import uk.gov.hmrc.customs.notification.domain.PushNotificationRequest
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class InternalPushConnector @Inject()(http: HttpClient,
                                      logger: CdsLogger) {

  def send(pnr: PushNotificationRequest): Future[Unit] = {

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

    http.POSTString[HttpResponse](pnr.body.url, pnr.body.xmlPayload, headers).map(_ => ()).recoverWith {
      case httpError: HttpException =>
        logger.error(s"Call to internal push notification service failed. POST url=${pnr.body.url}", httpError)
        Future.failed(new RuntimeException(httpError))
    }
    .recoverWith {
      case e: Throwable =>
        logger.error(s"Call to internal push notification service failed. POST url=${pnr.body.url}", e)
        Future.failed(e)
    }
  }

}
