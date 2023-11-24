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

import com.google.inject.Inject
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableRequestMetadata
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableRequestMetadata
import uk.gov.hmrc.customs.notification.models.{Auditable, ClientSendData, Loggable, PushCallbackData, RequestMetadata, SendToPullQueue}
import uk.gov.hmrc.customs.notification.services.AuditService._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
class AuditService @Inject()(logger: NotificationLogger,
                             auditConnector: AuditConnector,
                             now: () => ZonedDateTime)(implicit ec: ExecutionContext) {

  def sendSuccessfulInternalPushEvent[A: Auditable : Loggable](pushCallbackData: PushCallbackData,
                                                               payload: String,
                                                               toAudit: A)(implicit headerCarrier: HeaderCarrier): Unit = {
    val successAuditEventDetail = SuccessAuditEventDetail(
      maybePushCallbackData = Some(pushCallbackData),
      payload = payload,
      generatedAt = now(),
      headerCarrier = headerCarrier)

    sendAuditEvent(toAudit, successAuditEventDetail, AuditType.Outbound)
  }

  def sendFailedInternalPushEvent[A: Auditable: Loggable](pushCallbackData: PushCallbackData,
                                                          failureReason: String,
                                                          toAudit: A)(implicit headerCarrier: HeaderCarrier): Unit = {
    val failedAuditEventDetail = FailedAuditEventDetail(
      maybePushCallbackData = Some(pushCallbackData),
      failureReason = failureReason,
      generatedAt = now()
    )

    sendAuditEvent(toAudit, failedAuditEventDetail, AuditType.Outbound)
  }

  def sendIncomingNotificationEvent(callbackData: ClientSendData,
                                    xmlPayload: String,
                                    toAudit: RequestMetadata)
                                   (implicit hc: HeaderCarrier): Unit = {
      val maybePushCallbackData = callbackData match {
        case SendToPullQueue => None
        case d: PushCallbackData => Some(d)
      }
      val successAuditEventDetail = SuccessAuditEventDetail(
        maybePushCallbackData,
        xmlPayload,
        now(),
        hc)

    sendAuditEvent(toAudit, successAuditEventDetail, AuditType.Inbound)
  }

  private def sendAuditEvent[A: Loggable](toAudit: A,
                                  auditDetail: AuditEventDetail,
                                  auditType: AuditType
                                 )(implicit auditEv: Auditable[A],
                                   hc: HeaderCarrier): Unit = {
    val tags = auditEv.fieldsToAudit(toAudit).map { case (k, v) => k -> v.getOrElse("") }

    auditConnector.sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = "customs-notification",
        auditType = auditType.value,
        tags = Map(TransactionName -> "customs-declaration-outbound-call") ++ tags,
        detail = auditDetail.toJs
      )).foreach {
      case AuditResult.Success =>
        logger.info(s"Successfully audited ${auditDetail.result} event", toAudit)
      case AuditResult.Disabled =>
        logger.info(s"Auditing disabled. Did not audit ${auditDetail.result} event", toAudit)
      case AuditResult.Failure(msg, maybeThrowable) =>
        val message = s"Failed to audit ${auditDetail.result} event: $msg"
        maybeThrowable match {
          case Some(t) => logger.error(message, t, toAudit)
          case None => logger.error(message, toAudit)
        }
    }
  }
}

object AuditService {
  sealed trait AuditEventDetail {
    def maybePushCallbackData: Option[PushCallbackData]

    def result: String

    def generatedAt: ZonedDateTime

    protected val baseJs: JsObject = Json.obj(
      "result" -> result,
      "generatedAt" -> generatedAt.toInstant.truncatedTo(ChronoUnit.MILLIS)) ++
      maybePushCallbackData.fold(Json.obj()) { pushCallbackData =>
        Json.obj(
          "outboundCallUrl" -> pushCallbackData.callbackUrl.toString,
          "outboundCallAuthToken" -> pushCallbackData.securityToken.toString)
      }

    def extraFields: JsObject

    final def toJs: JsObject = baseJs ++ extraFields
  }

  case class FailedAuditEventDetail(maybePushCallbackData: Option[PushCallbackData],
                                    failureReason: String,
                                    generatedAt: ZonedDateTime) extends AuditEventDetail {
    val result: String = "FAILURE"

    val extraFields: JsObject = Json.obj("failureReason" -> failureReason)
  }

  case class SuccessAuditEventDetail(maybePushCallbackData: Option[PushCallbackData],
                                     payload: String,
                                     generatedAt: ZonedDateTime,
                                     headerCarrier: HeaderCarrier
                                    ) extends AuditEventDetail {
    val result: String = "SUCCESS"

    val extraFields: JsObject = {
      val explicitHeaders = headerCarrier.headers(HeaderNames.explicitlyIncludedHeaders)
      Json.obj(
        "payload" -> payload,
        "payloadHeaders" -> (explicitHeaders ++ headerCarrier.extraHeaders).toString)
    }
  }

  sealed trait AuditType {
    val value: String
  }

  object AuditType {
    case object Inbound extends AuditType {
      val value = "DeclarationNotificationInboundCall"
    }

    case object Outbound extends AuditType {
      val value = "DeclarationNotificationOutboundCall"
    }
  }
}
