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

package uk.gov.hmrc.customs.notification.domain

import play.api.libs.json.Format._
import play.api.libs.json._
import play.api.mvc.Headers

import java.util.UUID

case class Header(name: String, value: String)

object Header {
  implicit val jsonFormat: OFormat[Header] = Json.format[Header]
}

case class PushNotificationRequestBody(url: CallbackUrl, authHeaderToken: String, conversationId: String,
                                       outboundCallHeaders: Seq[Header], xmlPayload: String) {
  override def toString: String = s"[conversationId=$conversationId]" +
    s"[outboundCallHeaders=${outboundCallHeaders.toString()}]"
}

object PushNotificationRequestBody {

  implicit val callbackUrlFormat: DeclarantCallbackData.CallbackUrlFormat.type = DeclarantCallbackData.CallbackUrlFormat
  implicit val jsonFormat: OFormat[PushNotificationRequestBody] = Json.format[PushNotificationRequestBody]
}

case class PushNotificationRequest(clientSubscriptionId: String, pushNotificationRequestBody: PushNotificationRequestBody) {
  override def toString: String = {
    s"[csId=${ clientSubscriptionId }]" +
      s"[pushNotificationRequestBody=${ pushNotificationRequestBody }]"
  }
}

object PushNotificationRequest {

  def pushNotificationRequestFrom(declarantCallbackData: DeclarantCallbackData, n: NotificationWorkItem): PushNotificationRequest = {
    PushNotificationRequest(
      n._id.id.toString,
      PushNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        n.notification.conversationId.id.toString,
        n.notification.headers,
        n.notification.payload
      ))
  }
}

case class Notification(notificationId: Option[NotificationId],
                        conversationId: ConversationId,
                        headers: Seq[Header],
                        payload: String,
                        contentType: String,
                        mostRecentPushPullHttpStatus: Option[Int] = None) {

  private lazy val caseInsensitiveHeaders = Headers(headers.map { h => h.name -> h.value }: _*)

  def getHeader(name: String): Option[Header] = caseInsensitiveHeaders.get(name).map(Header(name, _))

  def getHeaderAsTuple(headerName: String): Option[(String, String)] = getHeader(headerName).map { h => h.name -> h.value }

  override def toString: String = s"[notificationId=${notificationId}]" +
    s"[conversationId=${conversationId}]" +
    s"[headers=${headers.mkString(",")}]" +
    s"[contentType=$contentType]" +
    s"[mostRecentPushPullHttpStatus=${mostRecentPushPullHttpStatus.getOrElse("[empty]")}]"
}

object Notification {
  implicit val notificationJF: Format[Notification] = Json.format[Notification]
}

case class ClientSubscriptionId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}

object ClientSubscriptionId {
  implicit val clientSubscriptionIdJF: Format[ClientSubscriptionId] =
    new Format[ClientSubscriptionId] {
      def writes(csid: ClientSubscriptionId) = JsString(csid.id.toString)

      def reads(json: JsValue): JsResult[ClientSubscriptionId] = json match {
        case JsNull => JsError()
        case _ => JsSuccess(ClientSubscriptionId(json.as[UUID]))
      }
    }
}
