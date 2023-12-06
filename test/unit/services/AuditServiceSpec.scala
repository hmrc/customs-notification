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

package unit.services

import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import util.TestData.Implicits._
import util.TestData._
import play.api.libs.json._
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.models.Auditable
import uk.gov.hmrc.customs.notification.services.{AuditService, DateTimeService}
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.time.ZonedDateTime
import scala.concurrent.Future

class AuditServiceSpec extends AsyncWordSpec
  with Matchers
  with AsyncMockitoSugar
  with Inside
  with ResetMocksAfterEachAsyncTest {

  private val mockAuditConnector = mock[AuditConnector]
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TimeNow
  }

  private val service = new AuditService(
    mockAuditConnector,
    mockDateTimeService)(Helpers.stubControllerComponents().executionContext)

  "sendSuccessfulInternalPushEvent" should {
    "make the correct request" in {
      val extendedDataEventCaptor = ArgCaptor[ExtendedDataEvent]

      when(mockAuditConnector.sendExtendedEvent(*)(eqTo(HeaderCarrier), *))
        .thenReturn(Future.successful(AuditResult.Success))

      val actualF = service.sendSuccessfulInternalPushEvent(PushCallbackData, Payload)

      actualF.map { _ =>
        verify(mockAuditConnector).sendExtendedEvent(extendedDataEventCaptor.capture)(eqTo(HeaderCarrier), *)

        val ExtendedDataEvent(auditSource, auditType, _, tags, detail, _, _, _) =
          extendedDataEventCaptor.value

        auditSource shouldBe "customs-notification"
        auditType shouldBe "DeclarationNotificationOutboundCall"

        tags shouldBe Map(
          TransactionName -> "customs-declaration-outbound-call",
          Auditable.KeyNames.ClientId -> ClientId.id,
          Auditable.KeyNames.NotificationId -> NotificationId.toString,
          Auditable.KeyNames.ClientSubscriptionId -> NewClientSubscriptionId.toString,
          Auditable.KeyNames.ConversationId -> ConversationId.toString)

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
