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

import org.bson.types.ObjectId
import play.api.http.MimeTypes
import play.api.test.Helpers.*
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.TestData.*

import java.net.URL
import java.util.UUID

object IntegrationTestHelpers {

  val AnotherObjectId = new ObjectId("bbbbbbbbbbbbbbbbbbbbbbbb")
  val YetAnotherObjectId = new ObjectId("cccccccccccccccccccccccc")
  val FourthObjectId = new ObjectId("dddddddddddddddddddddddd")
  val AnotherClientId = models.ClientId("Client2")
  val AnotherCsid = models.ClientSubscriptionId(UUID.fromString("00000000-9999-4444-7777-555555555555"))
  val AnotherCallbackUrl = new URL("http://www.example.edu")

  object PathFor {
    def clientDataWithCsid(csid: models.ClientSubscriptionId = TranslatedCsid): String = s"$ClientData/${csid.toString}"

    val ClientData = "/field"
    val InternalPush = "/some-custom-internal-push-url"
    val ExternalPush = "/notify-customs-declarant"
    val Metrics = "/log-times"
    val PullQueue = "/queue"
  }

  object Endpoints {
    val Notify: String = "/customs-notification/notify"
    val BlockedCount: String = "/customs-notification/blocked-count"
    val BlockedFlag: String = "/customs-notification/blocked-flag"
  }

  def validControllerRequestHeaders(csid: models.ClientSubscriptionId): Map[String, String] =
    Map(
      X_CLIENT_SUB_ID_HEADER_NAME -> csid.toString,
      X_CONVERSATION_ID_HEADER_NAME -> ConversationId.toString,
      CONTENT_TYPE -> MimeTypes.XML,
      ACCEPT -> MimeTypes.XML,
      AUTHORIZATION -> BasicAuthTokenValue,
      X_BADGE_ID_HEADER_NAME -> BadgeId,
      X_SUBMITTER_ID_HEADER_NAME -> SubmitterId,
      X_CORRELATION_ID_HEADER_NAME -> CorrelationId,
      ISSUE_DATE_TIME_HEADER_NAME -> IssueDateTime.toString
    )
}
