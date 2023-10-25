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
import play.api.libs.json.{JsObject, JsString, JsValue}
import uk.gov.hmrc.customs.notification.models.requests.{MetaDataRequest, PushNotificationRequest}
import uk.gov.hmrc.customs.notification.models.HasId
import uk.gov.hmrc.customs.notification.models.repo.NotificationWorkItem
import uk.gov.hmrc.customs.notification.util.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.time.Instant
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Singleton
class AuditingService @Inject()(logger: NotificationLogger, auditConnector: AuditConnector)(implicit ec: ExecutionContext) {
  def auditFailedNotification(pushNotificationRequest: PushNotificationRequest, failureReason: Option[String])(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    buildAuditNotification(pushNotificationRequest, "FAILURE", failureReason)
  }

  def auditSuccessfulNotification(pushNotificationRequest: PushNotificationRequest)(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    buildAuditNotification(pushNotificationRequest, "SUCCESS", None)
  }

  def auditNotificationReceived(pushNotificationRequest: PushNotificationRequest)(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    buildAuditNotification(pushNotificationRequest, "SUCCESS", None, "DeclarationNotificationInboundCall", Some(pushNotificationRequest.body.xmlPayload))
  }

  private def buildAuditNotification(pnr: PushNotificationRequest, successOrFailure: String, failureReason: Option[String], auditType: String = "DeclarationNotificationOutboundCall", notificationPayload: Option[String] = None)(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    val tags: Map[String, String] = Map(TransactionName -> "customs-declaration-outbound-call", "x-conversation-id" -> pnr.body.conversationId) ++ getTags(rm)
    val headerNames: Seq[String] = HeaderNames.explicitlyIncludedHeaders
    val headers = hc.headers(headerNames) ++ hc.extraHeaders
    val outboundCallUrl = "outboundCallUrl"
    val outboundCallAuthToken = "outboundCallAuthToken"
    val result = "result"
    val generatedAt = "generatedAt"
    val timeNow: Instant = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
    val detail: JsObject = failureReason.fold(
      JsObject(Map[String, JsValue](
        outboundCallUrl -> JsString(pnr.body.url.toString),
        outboundCallAuthToken -> JsString(pnr.body.authHeaderToken),
        result -> JsString(successOrFailure),
        "payload" -> JsString(notificationPayload.getOrElse("")),
        "payloadHeaders"-> JsString(headers.toString()),
        generatedAt -> JsString(timeNow.toString))))(reason => {
      JsObject(Map[String, JsValue](
        outboundCallUrl -> JsString(pnr.body.url.toString),
        outboundCallAuthToken -> JsString(pnr.body.authHeaderToken),
        result -> JsString(successOrFailure),
        generatedAt -> JsString(timeNow.toString),
        "failureReason" -> JsString(reason)
      ))}
    )

    auditConnector.sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = "customs-notification",
        auditType = auditType,
        tags = tags,
        detail = detail,
        generatedAt = timeNow
      )).onComplete {
      case Success(auditResult) =>
        logger.info(s"successfully audited $successOrFailure event")
        logger.debug(
          s"""successfully audited $successOrFailure event with
             |payload url=${pnr.body.url}
             |payload headers=${pnr.body.outboundCallHeaders}
             |audit response=$auditResult""".stripMargin)
      case Failure(ex) =>
        logger.error(s"failed to audit $successOrFailure event", ex)
    }
  }

  private def getTags(hasId: HasId): Map[String, String] = {
    val clientId = "clientId"
    val fieldsId = "fieldsId"
    val notificationId = "notificationId"

    hasId match {
      case r: MetaDataRequest =>
        Map(clientId -> r.maybeClientId.fold("")(_.id),
          fieldsId -> r.clientSubscriptionId.toString,
          notificationId -> r.notificationId.toString,
          "functionCode" -> r.maybeFunctionCode.fold("")(_.value),
          "issueDate" -> r.maybeIssueDateTime.fold("")(_.value),
          "mrn" -> r.maybeMrn.fold("")(_.value))
      case n: NotificationWorkItem =>
        Map(clientId -> n.clientId.id,
          fieldsId -> n.clientSubscriptionId.toString,
          notificationId -> n.notification.notificationId.fold("")(_.id.toString)
        )
      case _ =>
        Map()
    }
  }
}
