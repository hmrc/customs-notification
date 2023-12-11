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

package uk.gov.hmrc.customs.notification.services

import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.{FutureOutcome, Inside}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.TestData.Implicits.{HeaderCarrier, LogContext}
import play.api.libs.json.*
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.models.Auditable.KeyNames
import uk.gov.hmrc.customs.notification.models.{AuditContext, Auditable}
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.Future

class AuditServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with Inside
  with ResetMocksAfterEachAsyncTest {

  private val appName = "customs-notification-test"
  private val someUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val mockAuditConnector = mock[AuditConnector]
  private val mockAppConfig = mock[AppConfig]
  private val mockUuidService = mock[UuidService]
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TimeNow
  }

  private val service = new AuditService(
    mockAuditConnector,
    mockDateTimeService,
    mockAppConfig,
    mockUuidService
  )

  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    when(mockAppConfig.name).thenReturn(appName)
    when(mockUuidService.randomUuid()).thenReturn(someUuid)

    super.withFixture(test)
  }
  "Audit Service" when {
    "sending a success event for an internal push event" should {
      "make the correct request for auditing request metadata (i.e. from a API request)" in {
        implicit val requestMetadataAuditContext: AuditContext =
          AuditContext(RequestMetadata)(Auditable.Implicits.auditableRequestMetadata)

        val extendedDataEventCaptor = ArgCaptor[ExtendedDataEvent]

        when(mockAuditConnector.sendExtendedEvent(*)(eqTo(HeaderCarrier), *))
          .thenReturn(Future.successful(AuditResult.Success))

        val actualF = service.sendSuccessfulInternalPushEvent(PushCallbackData, Payload)

        actualF.map { _ =>
          verify(mockAuditConnector).sendExtendedEvent(extendedDataEventCaptor.capture)(eqTo(HeaderCarrier), *)

          val ExtendedDataEvent(auditSource, auditType, _, tags, detail, _, _, _) =
            extendedDataEventCaptor.value

          auditSource shouldBe appName
          auditType shouldBe "DeclarationNotificationOutboundCall"

          tags shouldBe Map(
            TransactionName -> "customs-declaration-outbound-call",
            KeyNames.ConversationId -> ConversationId.toString,
            KeyNames.ClientSubscriptionId -> TranslatedCsid.toString,
            KeyNames.NotificationId -> NotificationId.toString,
            KeyNames.BadgeId -> BadgeId,
            KeyNames.FunctionCode -> FunctionCode.value,
            KeyNames.IssueDateTime -> TimeNow.toString,
            KeyNames.Mrn -> Mrn.value

          )

          inside(detail.asOpt[JsObject]) { case Some(JsObject(o)) =>
            o should contain allElementsOf Json.obj(
              "outboundCallUrl" -> ClientCallbackUrl.toString,
              "outboundCallAuthToken" -> PushSecurityToken.value,
              "payload" -> Payload.toString,
              "result" -> "SUCCESS"
            ).value

            inside(o.get("payloadHeaders")) { case Some(JsString(headersStr)) =>
              headersStr should include(s"X-Request-ID,${HeaderCarrier.requestId.get.value}")
            }
          }
          succeed
        }
      }

      "make the correct request for auditing a notification (i.e. from a retry)" in {
        implicit val notificationAuditContext: AuditContext =
          AuditContext(Notification)(Auditable.Implicits.auditableNotification)

        val extendedDataEventCaptor = ArgCaptor[ExtendedDataEvent]

        when(mockAuditConnector.sendExtendedEvent(*)(eqTo(HeaderCarrier), *))
          .thenReturn(Future.successful(AuditResult.Success))

        val actualF = service.sendSuccessfulInternalPushEvent(PushCallbackData, Payload)

        actualF.map { _ =>
          verify(mockAuditConnector).sendExtendedEvent(extendedDataEventCaptor.capture)(eqTo(HeaderCarrier), *)

          val ExtendedDataEvent(auditSource, auditType, _, tags, detail, _, _, _) =
            extendedDataEventCaptor.value

          auditSource shouldBe appName
          auditType shouldBe "DeclarationNotificationOutboundCall"

          tags shouldBe Map(
            TransactionName -> "customs-declaration-outbound-call",
            Auditable.KeyNames.ClientId -> ClientId.id,
            Auditable.KeyNames.NotificationId -> NotificationId.toString,
            Auditable.KeyNames.ClientSubscriptionId -> TranslatedCsid.toString,
            Auditable.KeyNames.ConversationId -> ConversationId.toString
          )

          inside(detail.asOpt[JsObject]) { case Some(JsObject(o)) =>
            o should contain allElementsOf Json.obj(
              "outboundCallUrl" -> ClientCallbackUrl.toString,
              "outboundCallAuthToken" -> PushSecurityToken.value,
              "payload" -> Payload.toString,
              "result" -> "SUCCESS"
            ).value

            inside(o.get("payloadHeaders")) { case Some(JsString(headersStr)) =>
              headersStr should include(s"X-Request-ID,${HeaderCarrier.requestId.get.value}")
            }
          }
          succeed
        }
      }
    }
  }
}
