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

package uk.gov.hmrc.customs.notification.util

object HeaderNames {
  val X_CDS_CLIENT_ID_HEADER_NAME = "X-CDS-Client-ID"
  val X_CLIENT_ID_HEADER_NAME = "X-Client-ID"
  val X_CONVERSATION_ID_HEADER_NAME = "X-Conversation-ID"
  val X_CORRELATION_ID_HEADER_NAME = "X-Correlation-ID"
  val X_BADGE_ID_HEADER_NAME = "X-Badge-Identifier"
  val SUBSCRIPTION_FIELDS_ID_HEADER_NAME = "api-subscription-fields-id"
  val NOTIFICATION_ID_HEADER_NAME = "notification-id"
  val X_SUBMITTER_ID_HEADER_NAME = "X-Submitter-Identifier"
  val ISSUE_DATE_TIME_HEADER = "X-IssueDateTime"
}
