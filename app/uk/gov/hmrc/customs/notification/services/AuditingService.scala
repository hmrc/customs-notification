/*
 * Copyright 2024 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Singleton
class AuditingService @Inject()(logger: NotificationLogger, auditConnector: AuditConnector)
                               (implicit ec: ExecutionContext) {

  private val appName = "customs-notification"
  private val transactionNameValue = "customs-declaration-outbound-call"
  private val declarationNotificationOutboundCall = "DeclarationNotificationOutboundCall"
  private val declarationNotificationInboundCall = "DeclarationNotificationInboundCall"
  private val outboundCallUrl = "outboundCallUrl"
  private val outboundCallAuthToken = "outboundCallAuthToken"
  private val xConversationId = "x-conversation-id"
  private val result = "result"
  private val generatedAt = "generatedAt"
  private val clientId = "clientId"
  private val fieldsId = "fieldsId"
  private val notificationId = "notificationId"
  private val functionCode = "functionCode"
  private val issueDate = "issueDate"
  private val mrn = "mrn"
  private val failureReasonKey = "failureReason"
  private val payload = "payload"
  private val payloadHeaders = "payloadHeaders"

  def auditFailedNotification(pnr: PushNotificationRequest, failureReason: Option[String])(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    auditNotification(pnr, "FAILURE", failureReason)
  }

  def auditSuccessfulNotification(pnr: PushNotificationRequest)(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    auditNotification(pnr, "SUCCESS", None)
  }

  def auditNotificationReceived(pnr: PushNotificationRequest)(implicit rm: HasId, hc: HeaderCarrier): Unit = {
    auditNotification(pnr, "SUCCESS", None, declarationNotificationInboundCall, Some(pnr.pushNotificationRequestBody.xmlPayload))
  }

  private def auditNotification(pnr: PushNotificationRequest, successOrFailure: String, failureReason: Option[String], auditType: String = declarationNotificationOutboundCall, notificationPayload: Option[String] = None)(implicit rm: HasId, hc: HeaderCarrier): Unit = {

    val tags: Map[String, String] = Map(TransactionName -> transactionNameValue,
      xConversationId -> pnr.pushNotificationRequestBody.conversationId
    ) ++ getTags(rm)

    val headerNames: Seq[String] = HeaderNames.explicitlyIncludedHeaders
    val headers = hc.headers(headerNames) ++ hc.extraHeaders

    val detail: JsObject = failureReason.fold(
      JsObject(Map[String, JsValue](
        outboundCallUrl -> JsString(pnr.pushNotificationRequestBody.url.toString),
        outboundCallAuthToken -> JsString(pnr.pushNotificationRequestBody.authHeaderToken),
        result -> JsString(successOrFailure),
        payload -> JsString(notificationPayload.getOrElse("")),
        payloadHeaders -> JsString(headers.toString()),
        generatedAt -> JsString(timeNow().toString)
      )))(reason => {
      JsObject(Map[String, JsValue](
        outboundCallUrl -> JsString(pnr.pushNotificationRequestBody.url.toString),
        outboundCallAuthToken -> JsString(pnr.pushNotificationRequestBody.authHeaderToken),
        result -> JsString(successOrFailure),
        generatedAt -> JsString(timeNow().toString),
        failureReasonKey -> JsString(reason)
      ))
    }
    )

    auditConnector.sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = appName,
        auditType = auditType,
        tags = tags,
        detail = detail,
        generatedAt = timeNow()
      )).onComplete {
      case Success(auditResult) =>
        logger.info(s"successfully audited $successOrFailure event")
        logger.debug(
          s"""successfully audited $successOrFailure event with
             |payload url=${pnr.pushNotificationRequestBody.url}
             |payload headers=${pnr.pushNotificationRequestBody.outboundCallHeaders}
             |audit response=$auditResult""".stripMargin)
      case Failure(ex) =>
        logger.error(s"failed to audit $successOrFailure event", ex)
    }
  }

  private def getTags(rm: HasId): Map[String, String] = {
    rm match {
      case r: RequestMetaData =>
        Map(clientId -> r.maybeClientId.fold("")(_.id),
          fieldsId -> r.clientSubscriptionId.toString,
          notificationId -> r.notificationId.toString,
          functionCode -> r.maybeFunctionCode.fold("")(_.value),
          issueDate -> r.maybeIssueDateTime.fold("")(_.value),
          mrn -> r.maybeMrn.fold("")(_.value))
      case n: NotificationWorkItem =>
        Map(clientId -> n.clientId.id,
          fieldsId -> n.clientSubscriptionId.toString,
          notificationId -> n.notification.notificationId.fold("")(_.id.toString)
        )
      case _ =>
        Map()
    }
  }

  private def timeNow() = {
    java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
  }
}
