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

package uk.gov.hmrc.customs.notification.connectors

import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.{MimeTypes, Status}
import play.api.libs.json.Writes.StringWrites
import play.api.libs.json._
import uk.gov.hmrc.customs.notification.config.SendConfig
import uk.gov.hmrc.customs.notification.connectors.HttpConnector._
import uk.gov.hmrc.customs.notification.connectors.SendConnector._
import uk.gov.hmrc.customs.notification.models.Header.jsonFormat
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.services.AuditService
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendConnector @Inject()(http: HttpConnector,
                              config: SendConfig,
                              auditService: AuditService,
                              logger: Logger)(implicit ec: ExecutionContext) {
  def send[A: Auditable : Loggable](notification: Notification,
                                    clientSendData: ClientSendData,
                                    toAuditIfInternalPush: A)(implicit hc: HeaderCarrier): Future[Either[SendError, SuccessfullySent]] = {
    clientSendData match {
      case p: PushCallbackData if config.internalClientIds.contains(notification.clientId) =>
        handleInternalPush(notification, p, toAuditIfInternalPush)
      case p: PushCallbackData =>
        handleExternalPush(notification, p)
      case SendToPullQueue =>
        handlePull(notification)
    }
  }

  private def handleInternalPush[A: Auditable : Loggable](notification: Notification,
                                                          pushCallbackData: PushCallbackData,
                                                          toAuditIfInternalPush: A)(implicit hc: HeaderCarrier): Future[Either[SendError, SuccessfullySent]] = {
    val updatedHc = hc
      .copy(authorization = Some(pushCallbackData.securityToken))
      .withExtraHeaders(
        List(
          CONTENT_TYPE -> MimeTypes.XML,
          ACCEPT -> MimeTypes.XML,
          NOTIFICATION_ID_HEADER_NAME -> notification.id.toString,
          X_CONVERSATION_ID_HEADER_NAME -> notification.conversationId.toString
        ) ++ notification.headers.map(_.toTuple): _*
      )

    val request = InternalPush(pushCallbackData)

    val body = notification.payload

    def sendToAuditIfSuccessful(): Unit = auditService.sendSuccessfulInternalPushEvent(pushCallbackData, body, toAuditIfInternalPush)

    def sendToAuditIfFailed(failureReason: String): Unit = auditService.sendFailedInternalPushEvent(pushCallbackData, failureReason, toAuditIfInternalPush)

    http.post(
      url = pushCallbackData.callbackUrl,
      body = body,
      hc = updatedHc,
      requestDescriptor = request.name,
      shouldSendRequestToAuditing = true
    ).map(processResult(notification, request, sendToAuditIfSuccessful _, sendToAuditIfFailed))

  }

  private def handleExternalPush(notification: Notification, pushCallbackData: PushCallbackData)(implicit hc: HeaderCarrier): Future[Either[SendError, SuccessfullySent]] = {
    val updatedHc =
      hc.withExtraHeaders(
        ACCEPT -> MimeTypes.JSON,
        CONTENT_TYPE -> MimeTypes.JSON,
        NOTIFICATION_ID_HEADER_NAME -> notification.notificationId.toString
      )

    val body = {
      val updatedOutboundCallHeaders = notification.headers.filterNot(_.name.equals(ISSUE_DATE_TIME_HEADER_NAME))
      Json.obj(
        "conversationId" -> notification.conversationId.toString,
        "url" -> pushCallbackData.callbackUrl.toString,
        "authHeaderToken" -> pushCallbackData.securityToken.value,
        "outboundCallHeaders" -> updatedOutboundCallHeaders,
        "xmlPayload" -> notification.payload
      )
    }

    val request = ExternalPush(pushCallbackData)

    http.post(
      url = config.externalPushUrl,
      body = body,
      hc = updatedHc,
      requestDescriptor = request.name,
      shouldSendRequestToAuditing = true
    ).map(processResult(notification, request))
  }

  private def handlePull(notification: Notification)(implicit hc: HeaderCarrier): Future[Either[SendError, SuccessfullySent]] = {
    val newHc = HeaderCarrier(
      requestId = hc.requestId,
      extraHeaders = List(
        CONTENT_TYPE -> MimeTypes.XML,
        X_CONVERSATION_ID_HEADER_NAME -> notification.conversationId.toString,
        SUBSCRIPTION_FIELDS_ID_HEADER_NAME -> notification.clientSubscriptionId.toString,
        NOTIFICATION_ID_HEADER_NAME -> notification.notificationId.toString) ++
        notification.headers.collectFirst { case h@Header(X_BADGE_ID_HEADER_NAME, _) => h.toTuple } ++
        notification.headers.collectFirst { case h@Header(X_CORRELATION_ID_HEADER_NAME, _) => h.toTuple }
    )

    http.post(
      url = config.pullQueueUrl,
      body = notification.payload,
      newHc,
      requestDescriptor = Pull.name,
      shouldSendRequestToAuditing = true
    ).map(processResult(notification, Pull))
  }

  private def processResult(notification: Notification,
                            request: SendRequest,
                            doIfSuccess: () => Unit = () => (),
                            doIfFailedWithErrorMsg: String => Unit = _ => ())(before: Either[PostHttpConnectorError, Unit]): Either[SendError, SuccessfullySent] = {
    before match {
      case Right(()) =>
        doIfSuccess()
        Right(SuccessfullySent(request))
      case Left(httpError) =>
        doIfFailedWithErrorMsg(httpError.message)
        logger.error(httpError.message, notification)
        val error = httpError match {
          case _: HttpClientError =>
            ClientSendError(None)
          case ErrorResponse(_, r) if Status.isClientError(r.status) || Status.isRedirect(r.status) =>
            ClientSendError(Some(r.status))
          case ErrorResponse(_, r) =>
            ServerSendError(r.status)
        }
        Left(error)
    }
  }
}

object SendConnector {
  sealed trait SendRequest {
    val name: String
  }

  case class InternalPush(pushCallbackData: PushCallbackData) extends SendRequest {
    val name: String = "internal push"
  }

  case class ExternalPush(pushCallbackData: PushCallbackData) extends SendRequest {
    val name: String = "external push"
  }

  case object Pull extends SendRequest {
    val name: String = "pull enqueue"
  }

  case class SuccessfullySent(requestType: SendRequest)

  sealed trait SendError

  case class ClientSendError(maybeStatus: Option[Int]) extends SendError

  case class ServerSendError(status: Int) extends SendError
}