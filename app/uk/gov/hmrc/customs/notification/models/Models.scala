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

import play.api.libs.json._

import java.net.URL
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
object ClientId {
  implicit val clientIdJF: Format[ClientId] = new Format[ClientId] {
    def writes(clientId: ClientId) = JsString(clientId.id)
    def reads(json: JsValue): JsResult[ClientId] = json match {
      case JsNull => JsError()
      case _ => JsSuccess(ClientId(json.as[String]))
    }
  }
}

case class Submitter(id: String) extends AnyVal {
  override def toString: String = id
}

case class BadgeId(id: String) extends AnyVal {
  override def toString: String = id
}

case class CorrelationId(id: String) extends AnyVal {
  override def toString: String = id
}

case class FunctionCode(value: String) extends AnyVal {
  override def toString: String = value
}

case class IssueDateTime(value: String) extends AnyVal {
  override def toString: String = value
}

case class Mrn(value: String) extends AnyVal {
  override def toString: String = value
}

case class Header(name: String, value: String)

object Header {
  implicit val jsonFormat: OFormat[Header] = Json.format[Header]
}

case class ApiSubscriptionFields(clientId: String,
                                 fields: DeclarantCallbackData) {
  def isPush: Boolean = fields.callbackUrl.isPush
}

object ApiSubscriptionFields {
  implicit val jsonFormat = Json.format[ApiSubscriptionFields]
}

case class CallbackUrl(url: Option[URL]) extends AnyVal {
  override def toString: String = url.fold("")(_.toString)
  def isPush: Boolean = url.isDefined
  def isPull: Boolean = url.isEmpty
}

trait HasId {
  def idName: String
  def idValue: String
}

trait HasClientSubscriptionId {
  def clientSubscriptionId: ClientSubscriptionId
}

trait HasNotificationId {
  def notificationId: NotificationId
}

trait HasMaybeClientId {
  def maybeClientId: Option[ClientId]
}

trait HasMaybeBadgeId {
  def maybeBadgeId: Option[BadgeId]
}

trait HasMaybeSubmitter {
  def maybeSubmitterNumber: Option[Submitter]
}

trait HasMaybeCorrelationId {
  def maybeCorrelationId: Option[CorrelationId]
}

trait HasMaybeFunctionCode {
  def maybeFunctionCode: Option[FunctionCode]
}

trait HasMaybeIssueDateTime {
  def maybeIssueDateTime: Option[IssueDateTime]
}

trait HasMaybeMrn {
  def maybeMrn: Option[Mrn]
}
