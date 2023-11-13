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
import uk.gov.hmrc.customs.notification.models.{Auditable, Loggable, PushCallbackData}
import uk.gov.hmrc.customs.notification.services.AuditingService._
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames}
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.net.URL
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
class AuditingService @Inject()(logger: NotificationLogger,
                                auditConnector: AuditConnector,
                                dateTimeService: DateTimeService)(implicit ec: ExecutionContext) {

  def auditFailure[A](pushCallbackData: PushCallbackData,
                      reason: String,
                      toAudit: A,
                      auditType: AuditType)(implicit logEv: Loggable[A],
                                            logAudit: Auditable[A],
                                            hc: HeaderCarrier): Unit = {
    val failureAuditDetail = FailureAuditDetail(
      pushCallbackData.callbackUrl,
      pushCallbackData.securityToken,
      reason,
      dateTimeService.now())

    buildAuditNotification(toAudit, failureAuditDetail, auditType)
  }

  def auditSuccess[A](pushCallbackData: PushCallbackData,
                      payload: String,
                      toAudit: A,
                      auditType: AuditType)(implicit logEv: Loggable[A],
                                            logAudit: Auditable[A],
                                            hc: HeaderCarrier): Unit = {
    val successAuditDetail = SuccessAuditDetail(
      pushCallbackData.callbackUrl,
      pushCallbackData.securityToken,
      payload,
      dateTimeService.now(),
      hc)

    buildAuditNotification(toAudit, successAuditDetail, auditType)
  }

  private def buildAuditNotification[A](toAudit: A,
                                        auditDetail: AuditDetail,
                                        auditType: AuditType
                                       )(implicit hc: HeaderCarrier,
                                         logEv: Loggable[A],
                                         auditEv: Auditable[A]): Unit = {
    val entityTags = auditEv.fieldsToAudit(toAudit).map { case (k, v) => k -> v.getOrElse("") }

    auditConnector.sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = "customs-notification",
        auditType = auditType.value,
        tags = Map(TransactionName -> "customs-declaration-outbound-call") ++ entityTags,
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

object AuditingService {
  private sealed trait AuditDetail {
    val outboundCallUrl: Option[URL]
    val outboundCallAuthToken: Authorization
    val result: String
    val generatedAt: ZonedDateTime
    protected val baseJs: JsObject = Json.obj(
      "outboundCallUrl" -> outboundCallUrl.fold("")(_.toString),
      "outboundCallAuthToken" -> outboundCallAuthToken.toString,
      "result" -> result,
      "generatedAt" -> generatedAt.toInstant.truncatedTo(ChronoUnit.MILLIS))
    val extraFields: JsObject
    final val toJs = baseJs ++ extraFields
  }

  private case class FailureAuditDetail(outboundCallUrl: Option[URL],
                                        outboundCallAuthToken: Authorization,
                                        failureReason: String,
                                        generatedAt: ZonedDateTime) extends AuditDetail {
    val result: String = "FAILURE"

    val extraFields: JsObject = Json.obj("failureReason" -> failureReason)
  }

  private case class SuccessAuditDetail(outboundCallUrl: Option[URL],
                                        outboundCallAuthToken: Authorization,
                                        payload: String,
                                        generatedAt: ZonedDateTime,
                                        headerCarrier: HeaderCarrier
                                       ) extends AuditDetail {
    val result: String = "SUCCESS"

    val extraFields: JsObject = Json.obj(
      "payload" -> payload,
      "payloadHeaders" ->
        (headerCarrier.headers(HeaderNames.explicitlyIncludedHeaders) ++ headerCarrier.extraHeaders).toString)
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
