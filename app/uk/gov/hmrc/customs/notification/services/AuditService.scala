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
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.services.AuditService._
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuditService @Inject()(auditConnector: AuditConnector,
                             dateTimeService: DateTimeService)
                            (implicit ec: ExecutionContext) extends Logger {

  def sendSuccessfulInternalPushEvent(pushCallbackData: PushCallbackData,
                                      payload: Payload)
                                     (implicit hc: HeaderCarrier,
                                      lc: LogContext,
                                      ac: AuditContext): Future[Unit] = {
    val successAuditEventDetail = SuccessAuditEventDetail(
      maybePushCallbackData = Some(pushCallbackData),
      payload = payload,
      generatedAt = dateTimeService.now(),
      headerCarrier = hc)

    sendAuditEvent(successAuditEventDetail, AuditType.Outbound)
  }

  def sendFailedInternalPushEvent(pushCallbackData: PushCallbackData,
                                  failureReason: String)
                                 (implicit hc: HeaderCarrier,
                                  lc: LogContext,
                                  ac: AuditContext): Future[Unit] = {
    val failedAuditEventDetail = FailedAuditEventDetail(
      maybePushCallbackData = Some(pushCallbackData),
      failureReason = failureReason,
      generatedAt = dateTimeService.now()
    )

    sendAuditEvent(failedAuditEventDetail, AuditType.Outbound)
  }

  def sendIncomingNotificationEvent(callbackData: SendData,
                                    payload: Payload)
                                   (implicit hc: HeaderCarrier,
                                    lc: LogContext,
                                    ac: AuditContext): Future[Unit] = {
    val maybePushCallbackData = callbackData match {
      case SendToPullQueue => None
      case p: PushCallbackData => Some(p)
    }
    val successAuditEventDetail = SuccessAuditEventDetail(
      maybePushCallbackData,
      payload,
      dateTimeService.now(),
      hc)

    sendAuditEvent(successAuditEventDetail, AuditType.Inbound)
  }

  private def sendAuditEvent(auditDetail: AuditEventDetail,
                             auditType: AuditType
                            )(implicit hc: HeaderCarrier,
                              lc: LogContext,
                              ac: AuditContext): Future[Unit] = {
    val tags = ac.fieldsToAudit.map { case (k, v) => k -> v.getOrElse("") }

    auditConnector.sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = "customs-notification",
        auditType = auditType.value,
        tags = Map(EventKeys.TransactionName -> "customs-declaration-outbound-call") ++ tags,
        detail = auditDetail.toJs
      )).map {
      case AuditResult.Success =>
        logger.info(s"Successfully audited ${auditDetail.result} event")
      case AuditResult.Disabled =>
        logger.info(s"Auditing disabled. Did not audit ${auditDetail.result} event")
      case AuditResult.Failure(msg, maybeThrowable) =>
        val message = s"Failed to audit ${auditDetail.result} event: $msg"
        maybeThrowable match {
          case Some(t) => logger.error(message, t)
          case None => logger.error(message)
        }
    }
  }
}

object AuditService {
  private sealed trait AuditEventDetail {
    def maybePushCallbackData: Option[PushCallbackData]

    def result: String

    def generatedAt: ZonedDateTime

    def extraFields: JsObject

    final def toJs: JsObject = Json.obj(
      "result" -> result,
      "generatedAt" -> generatedAt.toInstant.truncatedTo(ChronoUnit.MILLIS)) ++
      maybePushCallbackData.fold(Json.obj()) { pushCallbackData =>
        Json.obj(
          "outboundCallUrl" -> pushCallbackData.callbackUrl.toString,
          "outboundCallAuthToken" -> pushCallbackData.securityToken.value)
      } ++ extraFields
  }

  private case class FailedAuditEventDetail(maybePushCallbackData: Option[PushCallbackData],
                                            failureReason: String,
                                            generatedAt: ZonedDateTime) extends AuditEventDetail {
    val result: String = "FAILURE"

    val extraFields: JsObject = Json.obj("failureReason" -> failureReason)
  }

  private case class SuccessAuditEventDetail(maybePushCallbackData: Option[PushCallbackData],
                                             payload: Payload,
                                             generatedAt: ZonedDateTime,
                                             headerCarrier: HeaderCarrier
                                            ) extends AuditEventDetail {
    val result: String = "SUCCESS"

    val extraFields: JsObject = {
      val explicitHeaders = headerCarrier.headers(HeaderNames.explicitlyIncludedHeaders)
      Json.obj(
        "payload" -> payload.underlying,
        "payloadHeaders" -> (explicitHeaders ++ headerCarrier.extraHeaders).toString)
    }
  }

  private sealed trait AuditType {
    val value: String
  }

  private object AuditType {
    case object Inbound extends AuditType {
      val value = "DeclarationNotificationInboundCall"
    }

    case object Outbound extends AuditType {
      val value = "DeclarationNotificationOutboundCall"
    }
  }
}
