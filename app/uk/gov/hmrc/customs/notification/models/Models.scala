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

package uk.gov.hmrc.customs.notification.models

import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJodaFormats}

import java.time.ZonedDateTime
import java.util.UUID

case class ConversationId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}

object ConversationId {
  implicit val conversationIdJF: Format[ConversationId] = new Format[ConversationId] {
    def writes(conversationId: ConversationId) = JsString(conversationId.id.toString)

    def reads(json: JsValue): JsResult[ConversationId] = json match {
      case JsNull => JsError()
      case _ => JsSuccess(ConversationId(json.as[UUID]))
    }
  }
}

case class NotificationId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}

object NotificationId {
  implicit val notificationIdJF: Format[NotificationId] = new Format[NotificationId] {
    def writes(notificationId: NotificationId) = JsString(notificationId.id.toString)

    def reads(json: JsValue): JsResult[NotificationId] = json match {
      case JsNull => JsError()
      case _ => JsSuccess(NotificationId(json.as[UUID]))
    }
  }
}

case class ClientId(id: String) extends AnyVal {
  override def toString: String = id
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

object ClientId {
  implicit val clientIdJF: Format[ClientId] = new Format[ClientId] {
    def writes(clientId: ClientId) = JsString(clientId.id)

    def reads(json: JsValue): JsResult[ClientId] = json match {
      case JsNull => JsError()
      case _ => JsSuccess(ClientId(json.as[String]))
    }
  }
}

case class FunctionCode(value: String) extends AnyVal {
  override def toString: String = value
}

case class Mrn(value: String) extends AnyVal {
  override def toString: String = value
}

case class Header(name: String, value: String) {
  val toTuple: (String, String) = name -> value
}

object Header {
  implicit val jsonFormat: OFormat[Header] = Json.format[Header]
}

case class ApiSubscriptionFields(clientId: ClientId,
                                 fields: PushCallbackData)

object ApiSubscriptionFields {
  implicit val reads: Reads[ApiSubscriptionFields] = Json.reads[ApiSubscriptionFields]
}

case class RequestMetadata(clientSubscriptionId: ClientSubscriptionId,
                           conversationId: ConversationId,
                           notificationId: NotificationId,
                           maybeBadgeId: Option[Header],
                           maybeSubmitterNumber: Option[Header],
                           maybeCorrelationId: Option[Header],
                           maybeIssueDateTime: Option[Header],
                           maybeFunctionCode: Option[FunctionCode],
                           maybeMrn: Option[Mrn],
                           startTime: ZonedDateTime)

case class Notification(notificationId: NotificationId,
                        conversationId: ConversationId,
                        headers: Seq[Header],
                        payload: String,
                        contentType: String,
                        mostRecentPushPullHttpStatus: Option[Int] = None)

object Notification {
  implicit val notificationJF: Format[Notification] = Json.format[Notification]
}
case class NotificationWorkItem(_id: ClientSubscriptionId,
                                clientId: ClientId,
                                metricsStartDateTime: ZonedDateTime,
                                notification: Notification)

object NotificationWorkItem {
  implicit val dateFormats: Format[DateTime] = MongoJodaFormats.dateTimeFormat
  implicit val objectIdFormats: Format[ObjectId] = MongoFormats.objectIdFormat
  implicit val format: OFormat[NotificationWorkItem] = Json.format[NotificationWorkItem]
}
