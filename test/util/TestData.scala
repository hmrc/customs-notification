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

package util

import org.bson.types.ObjectId
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.http
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, RequestId}

import java.net.URL
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.xml.NodeSeq

object TestData {
  val BasicAuthTokenValue = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="
  val TimeNow = ZonedDateTime.of(2023, 12, 25, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:off magic.number
  val IssueDateTime = ZonedDateTime.of(2023, 1, 1, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:off magic.number
  val NewClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-8888-4444-2222-111111111111"))
  val OldClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-2222-4444-8888-161616161616"))
  val ClientId = models.ClientId("Client1")
  val ClientPushUrl = new URL("http://www.example.net")
  val PushSecurityToken = Authorization("SECURITY_TOKEN")
  val PushCallbackData = models.PushCallbackData(ClientPushUrl, PushSecurityToken)
  val ApiSubscriptionFields = models.ApiSubscriptionFields(ClientId, PushCallbackData)
  val ConversationId = models.ConversationId(UUID.fromString("00000000-4444-4444-AAAA-AAAAAAAAAAAA"))
  val NotificationId = models.NotificationId(UUID.fromString("00000000-9999-4444-9999-444444444444"))
  val BadgeId = "ABCDEF1234"
  val SubmitterId = "IAMSUBMITTER"
  val CorrelationId = "CORRID2234"
  val ValidXml: NodeSeq = <Foo>Bar</Foo>
  val ApiSubscriptionFieldsUrl = new URL("http://www.example.com")
  val MetricsUrl = new URL("http://www.example.org")

  val RequestMetadata: RequestMetadata = models.RequestMetadata(NewClientSubscriptionId, ConversationId, NotificationId,
    Some(Header(X_BADGE_ID_HEADER_NAME, BadgeId)), Some(Header(X_SUBMITTER_ID_HEADER_NAME, SubmitterId)), Some(Header(X_CORRELATION_ID_HEADER_NAME, CorrelationId)),
    Some(Header(ISSUE_DATE_TIME_HEADER_NAME, IssueDateTime.toString)), None, None, TimeNow)

  val ObjectId = new ObjectId("aaaaaaaaaaaaaaaaaaaaaaaa")

  val Notification: Notification = {
    val headers = (RequestMetadata.maybeBadgeId ++
      RequestMetadata.maybeSubmitterNumber ++
      RequestMetadata.maybeCorrelationId ++
      RequestMetadata.maybeIssueDateTime).toSeq

    models.Notification(
      ObjectId,
      NewClientSubscriptionId,
      ClientId,
      NotificationId,
      ConversationId,
      headers,
      ValidXml.toString,
      TimeNow)
  }

  val HeaderCarrier: HeaderCarrier = http.HeaderCarrier(requestId = Some(RequestId("some-request-id"))) // Not pure; encodes current point in time
  val Exception: Throwable = new RuntimeException("Some error happened")

  val InternalClientId = models.ClientId("InternalClientId1")
}

