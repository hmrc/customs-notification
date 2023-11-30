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
import play.api.libs.json._
import uk.gov.hmrc.customs.notification.util.HeaderNames.{ISSUE_DATE_TIME_HEADER_NAME, X_BADGE_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME, X_SUBMITTER_ID_HEADER_NAME}
import uk.gov.hmrc.http.Authorization

import java.net.URL
import java.time.ZonedDateTime
import java.util.UUID
import scala.util.{Failure, Success, Try}

case class ConversationId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}

object ConversationId {
  implicit val conversationIdJF: Format[ConversationId] = Json.valueFormat
}

case class NotificationId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}

object NotificationId {
  implicit val notificationIdJF: Format[NotificationId] = Json.valueFormat
}

case class ClientSubscriptionId(id: UUID) extends AnyVal {
  override def toString: String = id.toString
}

object ClientSubscriptionId {
  implicit val clientSubscriptionIdJF: Format[ClientSubscriptionId] = Json.valueFormat
}

case class ClientId(id: String) extends AnyVal {
  override def toString: String = id
}

object ClientId {
  implicit val clientIdJF: Format[ClientId] = Json.valueFormat
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
  def forBadgeId(value: String): Header = apply(X_BADGE_ID_HEADER_NAME, value)
  def forSubmitterId(value: String): Header = apply(X_SUBMITTER_ID_HEADER_NAME, value)
  def forCorrelationId(value: String): Header = apply(X_CORRELATION_ID_HEADER_NAME, value)
  def forIssueDateTime(value: String): Header = apply(ISSUE_DATE_TIME_HEADER_NAME, value)
}


sealed trait ClientSendData

object ClientSendData {
  implicit val urlReads: Reads[URL] = {
    case JsString(urlStr) => Try(new URL(urlStr)) match {
      case Success(url) => JsSuccess(url)
      case Failure(_) => JsError("error.malformed.url")
    }
    case _ => JsError("error.expected.url")
  }
  implicit val parentReads: Reads[ClientSendData] = {
    case o: JsObject => (o \ "callbackUrl").asOpt[URL] match {
      case Some(callbackUrl) => (o \ "securityToken").asOpt[String] match {
        case Some(securityToken) => JsSuccess(PushCallbackData(callbackUrl, Authorization(securityToken)))
        case None => JsError("error.expected.securityToken")
      }
      case None => JsSuccess(SendToPullQueue)
    }
    case _ => JsError("error.expected.ClientSendData")
  }
}

case object SendToPullQueue extends ClientSendData

case class PushCallbackData(callbackUrl: URL,
                            securityToken: Authorization) extends ClientSendData

object PushCallbackData {
  implicit val authReads: Reads[Authorization] = Json.valueReads[Authorization]

  import uk.gov.hmrc.customs.notification.models.ClientSendData.urlReads
  implicit val reads: Reads[PushCallbackData] = Json.reads[PushCallbackData]
}

case class ApiSubscriptionFields(clientId: ClientId,
                                 fields: ClientSendData)

object ApiSubscriptionFields {
  implicit val reads: Reads[ApiSubscriptionFields] = Json.reads[ApiSubscriptionFields]
}

case class RequestMetadata(clientSubscriptionId: ClientSubscriptionId,
                           conversationId: ConversationId,
                           notificationId: NotificationId,
                           maybeBadgeId: Option[Header],
                           maybeSubmitterId: Option[Header],
                           maybeCorrelationId: Option[Header],
                           maybeIssueDateTime: Option[Header],
                           maybeFunctionCode: Option[FunctionCode],
                           maybeMrn: Option[Mrn],
                           startTime: ZonedDateTime)

case class Notification(id: ObjectId,
                        clientSubscriptionId: ClientSubscriptionId,
                        clientId: ClientId,
                        notificationId: NotificationId,
                        conversationId: ConversationId,
                        headers: Seq[Header],
                        payload: String,
                        metricsStartDateTime: ZonedDateTime)
