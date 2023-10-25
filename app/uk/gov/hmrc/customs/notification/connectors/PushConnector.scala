/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.http.MimeTypes
import play.mvc.Http.MimeTypes.XML
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.models.requests.{PushNotificationRequest, PushNotificationRequestBody}
import uk.gov.hmrc.customs.notification.util.HeaderNames.X_CONVERSATION_ID_HEADER_NAME
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PushConnector @Inject()(http: HttpClient,
                              logger: CdsLogger,
                              config: AppConfig)(implicit ec: ExecutionContext){
  def postInternalPush(pushNotificationRequest: PushNotificationRequest)(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    val internalOrExternal = "internal"
    val url = pushNotificationRequest.body.url.toString
    val notificationBody: PushNotificationRequestBody = pushNotificationRequest.body
    val extraHeaders: Seq[(String, String)] = Seq(CONTENT_TYPE -> XML, ACCEPT -> XML, X_CONVERSATION_ID_HEADER_NAME -> notificationBody.conversationId) ++ notificationBody.outboundCallHeaders.map(nextHeader => (nextHeader.name, nextHeader.value))
    val updatedHeaderCarrier = hc.copy(authorization = Some(Authorization(notificationBody.authHeaderToken))).withExtraHeaders(extraHeaders: _*)
    val xmlPayload = notificationBody.xmlPayload

    logPushCall(internalOrExternal, url, hc, xmlPayload)
    //TODO error message
    http.POSTString[HttpResponse](url, xmlPayload)(rds = HttpReads[HttpResponse], hc = updatedHeaderCarrier, ec = ec).recoverWith{
      error => throw new RuntimeException(s"???")}
  }

  def postExternalPush(pushNotificationRequest: PushNotificationRequest)(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    //TODO make below call a val from config rather than string search
    val internalOrExternal = "external"
    //val url = config.getConfig("public-notification").url
    val url = "TODO"
    val notificationBody: PushNotificationRequestBody = pushNotificationRequest.body
    val extraHeaders: Seq[(String, String)] = Seq((ACCEPT, MimeTypes.JSON), (CONTENT_TYPE, MimeTypes.JSON))
    val updatedHeaderCarrier = hc.withExtraHeaders(extraHeaders: _*)

    logPushCall(internalOrExternal, url, hc, notificationBody.toString)
    //TODO error message
    http.POST[PushNotificationRequestBody, HttpResponse](url, notificationBody)(wts = PushNotificationRequestBody.jsonFormat, rds = HttpReads[HttpResponse], hc = updatedHeaderCarrier, ec = ec).recoverWith {
      error => throw new RuntimeException(s"???")
    }
  }

  private def logPushCall(internalOrExternal: String, url: String, hc: HeaderCarrier, body: String): Unit = {
    logger.debug(s"Calling $internalOrExternal push notification service url=$url \nheaders=${hc.headers(HeaderNames.explicitlyIncludedHeaders) ++ hc.extraHeaders} \npayload= $body")
  }
}