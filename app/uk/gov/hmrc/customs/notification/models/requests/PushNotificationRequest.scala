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

package uk.gov.hmrc.customs.notification.models.requests

import play.api.libs.json._
import uk.gov.hmrc.customs.notification.models._

case class PushNotificationRequest(clientSubscriptionId: String, body: PushNotificationRequestBody)


object PushNotificationRequest{
  //TODO refactor out so we pass in params directly and use constructors if apt
  def buildPushNotificationRequest(declarantCallbackData: DeclarantCallbackData, notification: Notification, clientSubscriptionId: ClientSubscriptionId): PushNotificationRequest = {
    val pushNotificationRequestBody: PushNotificationRequestBody = PushNotificationRequestBody(
      url = declarantCallbackData.callbackUrl,
      authHeaderToken = declarantCallbackData.securityToken,
      conversationId = notification.conversationId.id.toString,
      outboundCallHeaders = notification.headers,
      xmlPayload = notification.payload)

    PushNotificationRequest(
      clientSubscriptionId = clientSubscriptionId.toString,
      body = pushNotificationRequestBody)
  }
}

case class PushNotificationRequestBody(url: CallbackUrl,
                                       authHeaderToken: String,
                                       conversationId: String,
                                       outboundCallHeaders: Seq[Header],
                                       xmlPayload: String) {
  override def toString: String = s"url: ${url.url.toString}, conversationId: $conversationId, outboundCallHeaders: ${outboundCallHeaders.toString()}"
}

object PushNotificationRequestBody {
  implicit val callbackUrlFormat: DeclarantCallbackData.CallbackUrlFormat.type = DeclarantCallbackData.CallbackUrlFormat
  implicit val jsonFormat: OFormat[PushNotificationRequestBody] = Json.format[PushNotificationRequestBody]
}