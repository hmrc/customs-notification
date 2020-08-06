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

package uk.gov.hmrc.customs.notification.domain

import java.net.URL
import java.util.UUID

import play.api.libs.json.Format._
import play.api.libs.json.{Format, Json, OFormat, _}
import play.api.mvc.Headers

import scala.util.Try

case class Header(name: String, value: String)
object Header {
  implicit val jsonFormat: OFormat[Header] = Json.format[Header]
}

case class PushNotificationRequestBody(url: Option[URL], authHeaderToken: String, conversationId: String,
                                       outboundCallHeaders: Seq[Header], xmlPayload: String)
object PushNotificationRequestBody {

  implicit object HttpOptionUrlFormat extends Format[Option[URL]] {

    override def reads(json: JsValue): JsResult[Option[URL]] = json match {
      case JsString(s) =>
        if(s.isEmpty) {
          JsSuccess(None)
        } else {
          parseUrl(s).map(url => JsSuccess(Some(url))).getOrElse(JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url")))))
        }
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url"))))
    }

    private def parseUrl(s: String): Option[URL] = Try(new URL(s)).toOption

    override def writes(o: Option[URL]): JsValue = JsString(o.getOrElse("").toString)
  }

  implicit object HttpUrlFormat extends Format[URL] {

    override def reads(json: JsValue): JsResult[URL] = json match {
      case JsString(s) => parseUrl(s).map(url => JsSuccess(url)).getOrElse(JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url")))))
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url"))))
    }

    private def parseUrl(s: String): Option[URL] = Try(new URL(s)).toOption

    override def writes(o: URL): JsValue = JsString(o.toString)
  }

  implicit val jsonFormat: OFormat[PushNotificationRequestBody] = Json.format[PushNotificationRequestBody]
}

case class PushNotificationRequest(clientSubscriptionId: String, body: PushNotificationRequestBody)

case class Notification(notificationId: Option[NotificationId], conversationId: ConversationId, headers: Seq[Header], payload: String, contentType: String) {

  private lazy val caseInsensitiveHeaders = Headers(headers.map { h => h.name -> h.value }: _*)

  def getHeader(name: String): Option[Header] = caseInsensitiveHeaders.get(name).map(Header(name, _))

  def getHeaderAsTuple(headerName: String): Option[(String, String)] = getHeader(headerName).map { h => h.name -> h.value }
}
object Notification {
  implicit val notificationJF: Format[Notification] = Json.format[Notification]
}

case class ClientSubscriptionId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}
object ClientSubscriptionId {
  implicit val clientSubscriptionIdJF: Format[ClientSubscriptionId] = new Format[ClientSubscriptionId] {
    def writes(csid: ClientSubscriptionId) = JsString(csid.id.toString)
    def reads(json: JsValue): JsResult[ClientSubscriptionId] = json match {
      case JsNull => JsError()
      case _ => JsSuccess(ClientSubscriptionId(json.as[UUID]))
    }
  }
}
