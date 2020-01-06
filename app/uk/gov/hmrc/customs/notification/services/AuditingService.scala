/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
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
  private val outboundCallUrl = "outboundCallUrl"
  private val outboundCallAuthToken = "outboundCallAuthToken"
  private val xConversationId = "x-conversation-id"
  private val result = "result"
  private val generatedAt = "generatedAt"

  private val failureReasonKey = "failureReason"

  def auditFailedNotification(pnr: PushNotificationRequest, failureReason: Option[String])
                             (implicit rm: HasId): Unit = {
    auditNotification(pnr, "FAILURE", failureReason)
  }

  def auditSuccessfulNotification(pnr: PushNotificationRequest)(implicit rm: HasId): Unit = {
    auditNotification(pnr, "SUCCESS", None)
  }

  private def auditNotification(pnr: PushNotificationRequest, successOrFailure: String, failureReason: Option[String])(implicit rm: HasId): Unit = {

    val tags = Map(TransactionName -> transactionNameValue,
    xConversationId -> pnr.body.conversationId )

    val detail: JsObject = failureReason.fold(
      JsObject(Map[String, JsValue](
      outboundCallUrl -> JsString(pnr.body.url),
      outboundCallAuthToken -> JsString(pnr.body.authHeaderToken),
      result -> JsString(successOrFailure),
      generatedAt -> JsString(DateTimeUtils.now.toString)
      )))(reason => {
        JsObject(Map[String, JsValue](
          outboundCallUrl -> JsString(pnr.body.url),
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
        auditType = declarationNotificationOutboundCall,
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
}
