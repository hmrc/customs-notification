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

case class AuditContext private(fieldsToAudit: Map[String, Option[String]])

object AuditContext {
  def apply[A](entity: A)(implicit a: Auditable[A]): AuditContext = {
    new AuditContext(a.fieldsToAudit(entity)) {}
  }
}

trait Auditable[A] {
  def fieldsToAudit(a: A): Map[String, Option[String]]
}

object Auditable {
  object KeyNames {
    val ConversationId = "x-conversation-id"
    val ClientId = "clientId"
    val ClientSubscriptionId = "fieldsId"
    val NotificationId = "notificationId"
    val BadgeId = "badgeId"
    val FunctionCode = "functionCode"
    val IssueDateTime = "issueDate"
    val Mrn = "mrn"
  }

  object Implicits {
    implicit val auditableRequestMetadata: Auditable[RequestMetadata] = (r: RequestMetadata) =>
      Map(
        KeyNames.ConversationId -> Some(r.conversationId.toString),
        KeyNames.ClientSubscriptionId -> Some(r.csid.toString),
        KeyNames.NotificationId -> Some(r.notificationId.toString),
        KeyNames.BadgeId -> r.maybeBadgeId.map(_.value),
        KeyNames.FunctionCode -> r.maybeFunctionCode.map(_.toString),
        KeyNames.IssueDateTime -> r.maybeIssueDateTime.map(_.value),
        KeyNames.Mrn -> r.maybeMrn.map(_.toString)
      )

    implicit val auditableNotification: Auditable[Notification] = (n: Notification) =>
      Map(
        KeyNames.ConversationId -> Some(n.conversationId.toString),
        KeyNames.ClientId -> Some(n.clientId.id),
        KeyNames.ClientSubscriptionId -> Some(n.csid.toString),
        KeyNames.NotificationId -> Some(n.notificationId.toString)
      )
  }
}
