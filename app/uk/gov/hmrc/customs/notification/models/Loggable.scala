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
import play.api.mvc.Headers
import uk.gov.hmrc.customs.notification.util.HeaderNames._

import scala.collection.immutable.ListMap

trait Loggable[A] {
  def fieldsToLog(a: A): ListMap[String, Option[String]]
}

object Loggable {
  object KeyNames {
    val ConversationId = "conversationId"
    val ClientId = "clientId"
    val ClientSubscriptionId = "fieldsId"
    val NotificationId = "notificationId"
    val BadgeId = "badgeId"
    val Submitter = "submitterIdentifier"
    val CorrelationId = "correlationId"
    val FunctionCode = "functionCode"
    val IssueDateTime = "issueDateTime"
    val Mrn = "mrn"
    val WorkItemId = "workItemId"
  }

  object Implicits {

    implicit val loggableHeaders: Loggable[Headers] = (h: Headers) => {
      def map(replacementName: String, headerName: String): (String, Option[String]) = {
        replacementName -> h.get(headerName)
      }

      ListMap(
        map(KeyNames.ConversationId, X_CONVERSATION_ID_HEADER_NAME),
        map(KeyNames.ClientSubscriptionId, X_CLIENT_SUB_ID_HEADER_NAME),
        map(KeyNames.BadgeId, X_BADGE_ID_HEADER_NAME),
        map(KeyNames.Submitter, X_SUBMITTER_ID_HEADER_NAME),
        map(KeyNames.CorrelationId, X_CORRELATION_ID_HEADER_NAME),
        map(KeyNames.IssueDateTime, ISSUE_DATE_TIME_HEADER_NAME)
      )
    }

    implicit val loggableRequestMetadata: Loggable[RequestMetadata] = (r: RequestMetadata) =>
      ListMap(
        KeyNames.ConversationId -> Some(r.conversationId.toString),
        KeyNames.ClientSubscriptionId -> Some(r.clientSubscriptionId.toString),
        KeyNames.NotificationId -> Some(r.notificationId.toString),
        KeyNames.BadgeId -> r.maybeBadgeId.map(_.value),
        KeyNames.Submitter -> r.maybeSubmitterNumber.map(_.value),
        KeyNames.CorrelationId -> r.maybeCorrelationId.map(_.value),
        KeyNames.FunctionCode -> r.maybeFunctionCode.map(_.toString),
        KeyNames.IssueDateTime -> r.maybeIssueDateTime.map(_.value),
        KeyNames.Mrn -> r.maybeMrn.map(_.toString)
      )

    implicit val loggableNotificationWorkItem: Loggable[NotificationWorkItem] = (n: NotificationWorkItem) =>
      ListMap(
        KeyNames.ConversationId -> Some(n.notification.conversationId.toString),
        KeyNames.ClientSubscriptionId -> Some(n._id.toString)
      )

    implicit val loggableObjectId: Loggable[ObjectId] = (id: ObjectId) =>
      ListMap(
        KeyNames.WorkItemId -> Some(id.toString)
      )

    implicit val loggableConversationId: Loggable[ConversationId] = (id: ConversationId) =>
      ListMap(
        KeyNames.ConversationId -> Some(id.toString)
      )

    implicit val loggableClientId: Loggable[ClientId] = (id: ClientId) =>
      ListMap(
        KeyNames.ClientId -> Some(id.toString)
      )

    implicit def loggableTuple[A, B](implicit evA: Loggable[A], evB: Loggable[B]): Loggable[(A, B)] =
      (a: (A, B)) => evA.fieldsToLog(a._1) ++ evB.fieldsToLog(a._2)
  }
}
