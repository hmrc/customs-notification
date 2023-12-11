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
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.http
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, RequestId}

import java.net.URL
import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.xml.NodeSeq

object TestData {
  object Implicits {
    implicit val LogContext: LogContext = models.LogContext.empty
    implicit val EmptyAuditContext: models.AuditContext = models.AuditContext(())(_ => Map.empty)
    implicit val HeaderCarrier: HeaderCarrier = http.HeaderCarrier(requestId = Some(RequestId("some-request-id"))) // Not pure; encodes current point in time
  }
  val BasicAuthTokenValue = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="
  val TimeNow = ZonedDateTime.of(2023, 12, 25, 0, 0, 1, 0, ZoneId.of("UTC")) // scalastyle:off magic.number
  val IssueDateTime = TimeNow
  val TranslatedCsid = models.ClientSubscriptionId(UUID.fromString("00000000-8888-4444-2222-111111111111"))
  val UntranslatedCsid = models.ClientSubscriptionId(UUID.fromString("00000000-2222-4444-8888-161616161616"))
  val ClientId = models.ClientId("Client1")
  val ClientCallbackUrl = new URL("http://www.example.local")
  val PushSecurityToken = Authorization("SECURITY_TOKEN")
  val PushCallbackData = models.PushCallbackData(ClientCallbackUrl, PushSecurityToken)
  val ClientData = models.ClientData(ClientId, PushCallbackData)
  val ConversationId = models.ConversationId(UUID.fromString("00000000-4444-4444-AAAA-AAAAAAAAAAAA"))
  val SomeUuid = UUID.fromString("00000000-9999-4444-9999-444444444444")
  val NotificationId = models.NotificationId(SomeUuid)
  val BadgeId = "ABCDEF1234"
  val SubmitterId = "IAMSUBMITTER"
  val CorrelationId = "CORRID2234"
  val FunctionCode = models.FunctionCode("01")
  val Mrn = models.Mrn("19GB3955NQ36213969")
  val ValidXml: NodeSeq = <Too><Response><Declaration><ID>{Mrn.value}</ID><FunctionCode>{FunctionCode.value}</FunctionCode></Declaration></Response></Too>
  val Payload = models.Payload.from(ValidXml)
  val ApiSubscriptionFieldsUrl = new URL("http://www.example.com")
  val MetricsUrl = new URL("http://www.example.org")

  val RequestMetadata: RequestMetadata = models.RequestMetadata(TranslatedCsid, ConversationId, NotificationId,
    Some(Header.forBadgeId(BadgeId)), Some(Header.forSubmitterId(SubmitterId)), Some(Header.forCorrelationId(CorrelationId)),
    Some(Header.forIssueDateTime(IssueDateTime.toString)), Some(FunctionCode), Some(Mrn), TimeNow)

  val ObjectId = new ObjectId("aaaaaaaaaaaaaaaaaaaaaaaa")

  val Notification = notificationFor(TimeNow)
  def notificationFor(time: ZonedDateTime): Notification = {
    val headers = (RequestMetadata.maybeBadgeId ++
      RequestMetadata.maybeSubmitterId ++
      RequestMetadata.maybeCorrelationId ++
      RequestMetadata.maybeIssueDateTime).toSeq

    models.Notification(
      ObjectId,
      TranslatedCsid,
      ClientId,
      NotificationId,
      ConversationId,
      headers,
      Payload,
      time)
  }

  val Exception: Throwable = new RuntimeException("Some error happened")

  val InternalClientId = models.ClientId("InternalClientId1")
}

