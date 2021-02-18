/*
 * Copyright 2021 HM Revenue & Customs
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

import javax.inject.Singleton
import play.api.libs.json.{JsObject, JsString, JsValue}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.EventKeys.TransactionName
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent
import uk.gov.hmrc.time.DateTimeUtils

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
  private val requestId = "requestId"
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
    auditNotification(pnr, "SUCCESS", None, declarationNotificationInboundCall, Some(pnr.body.xmlPayload))
  }

  private def auditNotification(pnr: PushNotificationRequest, successOrFailure: String, failureReason: Option[String], auditType: String = declarationNotificationOutboundCall, notificationPayload: Option[String] = None)(implicit rm: HasId, hc: HeaderCarrier): Unit = {

    val tags: Map[String, String] = Map(TransactionName -> transactionNameValue,
    xConversationId -> pnr.body.conversationId
    ) ++ getTags(rm)

    val detail: JsObject = failureReason.fold(
      JsObject(Map[String, JsValue](
        outboundCallUrl -> JsString(pnr.body.url.toString),
        outboundCallAuthToken -> JsString(pnr.body.authHeaderToken),
        result -> JsString(successOrFailure),
        payload -> JsString(notificationPayload.getOrElse("")),
        payloadHeaders -> JsString(hc.headers.toString()),
        generatedAt -> JsString(DateTimeUtils.now.toString)
      )))(reason => {
        JsObject(Map[String, JsValue](
          outboundCallUrl -> JsString(pnr.body.url.toString),
          outboundCallAuthToken -> JsString(pnr.body.authHeaderToken),
          result -> JsString(successOrFailure),
          generatedAt -> JsString(DateTimeUtils.now.toString),
          failureReasonKey -> JsString(reason)
        ))
      }
    )

    auditConnector.sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = appName,
        auditType = auditType,
        tags = tags,
        detail = detail
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

  private def getTags(rm: HasId): Map[String, String] = {
    rm match {
      case r: RequestMetaData =>
        Map(clientId -> r.maybeClientId.fold("")(_.id),
          fieldsId -> r.clientSubscriptionId.toString,
          requestId -> r.requestId.toString,
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
}
