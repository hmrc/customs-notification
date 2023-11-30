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

import _root_.util.TestData
import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.{AsyncMockitoSugar, ResetMocksAfterEachAsyncTest}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import play.api.libs.json._
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.models.Auditable
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.services.{AuditService, DateTimeService}
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.http.HeaderCarrier
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

  private val mockLogger = mock[Logger]
  private val mockAuditConnector = mock[AuditConnector]
  private val mockDateTimeService = new DateTimeService {
    override def now(): ZonedDateTime = TestData.TimeNow
  }

  private val service = new AuditService(
    mockLogger,
    mockAuditConnector,
    mockDateTimeService)(Helpers.stubControllerComponents().executionContext)

  private implicit val hc: HeaderCarrier = TestData.HeaderCarrier

  "sendSuccessfulInternalPushEvent" should {
    "make the correct request" in {
      val extendedDataEventCaptor = ArgCaptor[ExtendedDataEvent]

      when(mockAuditConnector.sendExtendedEvent(*)(eqTo(TestData.HeaderCarrier), *))
        .thenReturn(Future.successful(AuditResult.Success))

      val actualF = service.sendSuccessfulInternalPushEvent(TestData.PushCallbackData, TestData.ValidXml.toString, TestData.Notification)

      actualF.map { _ =>
        verify(mockAuditConnector).sendExtendedEvent(extendedDataEventCaptor.capture)(eqTo(TestData.HeaderCarrier), *)

        val ExtendedDataEvent(auditSource, auditType, _, tags, detail, _, _, _) =
          extendedDataEventCaptor.value

        auditSource shouldBe "customs-notification"
        auditType shouldBe "DeclarationNotificationOutboundCall"

        tags shouldBe Map(
          TransactionName -> "customs-declaration-outbound-call",
          Auditable.KeyNames.ClientId -> TestData.ClientId.id,
          Auditable.KeyNames.NotificationId -> TestData.NotificationId.toString,
          Auditable.KeyNames.ClientSubscriptionId -> TestData.NewClientSubscriptionId.toString,
          Auditable.KeyNames.ConversationId -> TestData.ConversationId.toString)

        inside(detail.asOpt[JsObject]) { case Some(JsObject(o)) =>
          o should contain allElementsOf Json.obj(
            "outboundCallUrl" -> TestData.ClientCallbackUrl.toString,
            "outboundCallAuthToken" -> TestData.PushSecurityToken.value,
            "payload" -> TestData.ValidXml.toString,
            "result" -> "SUCCESS"
          ).value

          inside(o.get("payloadHeaders")) { case Some(JsString(headersStr)) =>
            headersStr should include(s"X-Request-ID,${TestData.HeaderCarrier.requestId.get.value}")
          }
        }
        succeed
      }
    }
  }
}
