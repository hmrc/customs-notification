/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import play.api.libs.json.Format._
import play.api.libs.json.{Format, Json, OFormat, _}

case class Header(name: String, value: String)

object Header {
  implicit val jsonFormat: OFormat[Header] = Json.format[Header]
}

case class PublicNotificationRequestBody(
                                          url: String,
                                          authHeaderToken: String,
                                          conversationId: String,
                                          outboundCallHeaders: Seq[Header],
                                          xmlPayload: String
                                        )

object PublicNotificationRequestBody {
  implicit val jsonFormat: OFormat[PublicNotificationRequestBody] = Json.format[PublicNotificationRequestBody]
}

case class PublicNotificationRequest(
                                      clientSubscriptionId: String,
                                      body: PublicNotificationRequestBody
                                    )


case class Notification(headers: Seq[Header], payload: String, contentType: String)
object Notification {
  implicit val notificationJF = Json.format[Notification]
}

case class ClientSubscriptionId(id: UUID)
object ClientSubscriptionId {
  implicit val clientSubscriptionIdJF = new Format[ClientSubscriptionId] {
    def writes(csid: ClientSubscriptionId) = JsString(csid.id.toString)
    def reads(json: JsValue) = json match {
      case JsNull => JsError()
      case _ => JsSuccess(ClientSubscriptionId(json.as[UUID]))
    }
  }
}
