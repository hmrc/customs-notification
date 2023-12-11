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
import play.api.libs.json.*
import uk.gov.hmrc.customs.notification.config.SendConfig
import uk.gov.hmrc.customs.notification.connectors.HttpConnector.*
import uk.gov.hmrc.customs.notification.connectors.SendConnector.Request.{ExternalPushDescriptor, InternalPushDescriptor, PullDescriptor}
import uk.gov.hmrc.customs.notification.connectors.SendConnector.*
import uk.gov.hmrc.customs.notification.models.Header.jsonFormat
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.services.AuditService
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.http.HeaderCarrier

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendConnector @Inject()(http: HttpConnector,
                              config: SendConfig,
                              auditService: AuditService)
                             (implicit ec: ExecutionContext) extends Logger {

  def send(notification: Notification,
           clientSendData: SendData)
          (implicit hc: HeaderCarrier,
           lc: LogContext,
           ac: AuditContext): Future[Either[SendError, SuccessfullySent]] = {
    val request = createRequestFrom(notification, clientSendData)

    val response = {
      http.post(
        url = request.callbackUrl,
        body = request.body,
        hc = request.headerCarrier,
        requestDescriptor = request.descriptor.value
      )
    }

    response.map {
      case Right(()) =>
        request.doIfSuccess()
        Right(SuccessfullySent(request.descriptor))
      case Left(httpError) =>
        request.doIfFailed(httpError.message)
        logger.error(httpError.message)
        val error = httpError match {
          case _: HttpClientError =>
            ClientError
          case ErrorResponse(_, r) if Status.isClientError(r.status) || Status.isRedirect(r.status) =>
            ClientError
          case _: ErrorResponse =>
            ServerError
        }
        Left(error)
    }
  }

  private def createRequestFrom(notification: Notification,
                                clientSendData: SendData)
                               (implicit hc: HeaderCarrier,
                                logContext: LogContext,
                                auditContext: AuditContext): Request = {
    clientSendData match {
      case p: PushCallbackData if config.internalClientIds.contains(notification.clientId) =>
        InternalPush(p, notification)
      case p: PushCallbackData =>
        ExternalPush(p, notification)
      case SendToPullQueue =>
        Pull(notification)
    }
  }

  private case class InternalPush(pushCallbackData: PushCallbackData,
                                  notification: Notification)
                                 (implicit prevHc: HeaderCarrier,
                                  logContext: LogContext,
                                  auditContext: AuditContext) extends Request {
    val descriptor: Request.Descriptor = InternalPushDescriptor
    val headerCarrier: HeaderCarrier = {
      val extraHeaders = {
        List(
          CONTENT_TYPE -> MimeTypes.XML,
          ACCEPT -> MimeTypes.XML,
          NOTIFICATION_ID_HEADER_NAME -> notification.id.toString,
          X_CONVERSATION_ID_HEADER_NAME -> notification.conversationId.toString
        ) ++ notification.headers.map(_.toTuple)
      }

      prevHc
        .copy(authorization = Some(pushCallbackData.securityToken))
        .withExtraHeaders(extraHeaders *)
    }

    val callbackUrl: URL = pushCallbackData.callbackUrl
    val body: RequestBody.Xml = RequestBody.Xml(notification.payload)

    override def doIfSuccess(): Unit = auditService.sendSuccessfulInternalPushEvent(pushCallbackData, notification.payload)

    override def doIfFailed(failureReason: String): Unit = auditService.sendFailedInternalPushEvent(pushCallbackData, failureReason)

  }

  private case class ExternalPush(pushCallbackData: PushCallbackData,
                                  notification: Notification)
                                 (implicit prevHc: HeaderCarrier) extends Request {
    val descriptor: Request.Descriptor = ExternalPushDescriptor
    val headerCarrier: HeaderCarrier = prevHc
      .withExtraHeaders(
        ACCEPT -> MimeTypes.JSON,
        CONTENT_TYPE -> MimeTypes.JSON,
        NOTIFICATION_ID_HEADER_NAME -> notification.notificationId.toString
      )
    val callbackUrl: URL = config.externalPushUrl
    val body: RequestBody.Json = RequestBody.Json {
      val updatedOutboundCallHeaders = notification.headers.filterNot(_.name.equals(ISSUE_DATE_TIME_HEADER_NAME))
      Json.obj(
        "conversationId" -> notification.conversationId.toString,
        "url" -> pushCallbackData.callbackUrl.toString,
        "authHeaderToken" -> pushCallbackData.securityToken.value,
        "outboundCallHeaders" -> updatedOutboundCallHeaders,
        "xmlPayload" -> notification.payload.toString
      )
    }
  }

  private case class Pull(notification: Notification)
                         (implicit prevHc: HeaderCarrier) extends Request {
    val descriptor: Request.Descriptor = PullDescriptor

    override def callbackUrl: URL = config.pullQueueUrl

    override val body: RequestBody.Xml = RequestBody.Xml(notification.payload)

    override def headerCarrier: HeaderCarrier = HeaderCarrier(
      requestId = prevHc.requestId,
      extraHeaders = List(
        CONTENT_TYPE -> MimeTypes.XML,
        X_CONVERSATION_ID_HEADER_NAME -> notification.conversationId.toString,
        SUBSCRIPTION_FIELDS_ID_HEADER_NAME -> notification.csid.toString,
        NOTIFICATION_ID_HEADER_NAME -> notification.notificationId.toString) ++
        notification.headers.collectFirst { case h@Header(X_BADGE_ID_HEADER_NAME, _) => h.toTuple } ++
        notification.headers.collectFirst { case h@Header(X_CORRELATION_ID_HEADER_NAME, _) => h.toTuple }
    )
  }
}


object SendConnector {
  sealed trait Request {
    def descriptor: Request.Descriptor

    def callbackUrl: URL

    def body: HttpConnector.RequestBody

    def headerCarrier: HeaderCarrier

    def doIfSuccess(): Unit = ()

    def doIfFailed(message: String): Unit = ()
  }

  object Request {
    sealed trait Descriptor {
      val value: String
    }

    case object InternalPushDescriptor extends Descriptor {
      val value: String = "internal push"
    }

    case object ExternalPushDescriptor extends Descriptor {
      val value: String = "external push"
    }

    case object PullDescriptor extends Descriptor {
      val value: String = "pull enqueue"
    }
  }

  case class SuccessfullySent(descriptor: Request.Descriptor)

  sealed trait SendError

  case object ClientError extends SendError

  case object ServerError extends SendError
}