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

import cats.implicits.toBifunctorOps
import play.api.http.Status
import uk.gov.hmrc.customs.notification.config.SendNotificationConfig
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotificationWorkItem
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.requests._
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services.AuditingService.AuditType
import uk.gov.hmrc.customs.notification.services.HttpConnector._
import uk.gov.hmrc.customs.notification.services.SendService._
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits.FutureEitherExtensions
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Succeeded

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendService @Inject()(implicit
                            connector: HttpConnector,
                            repo: NotificationRepo,
                            config: SendNotificationConfig,
                            dateTimeService: DateTimeService,
                            logger: NotificationLogger,
                            auditingService: AuditingService,
                            ec: ExecutionContext) {

  def send(notification: Notification,
           apiSubscriptionFields: ApiSubscriptionFields)
          (implicit hc: HeaderCarrier,
           auditEv: Auditable[Notification],
           logEv: Loggable[Notification]): Future[Either[SendServiceError.type, Unit]] =
    send(notification, notification, apiSubscriptionFields)(hc, auditEv, logEv)

  def send[A](notification: Notification,
              toAudit: A,
              apiSubscriptionFields: ApiSubscriptionFields)
             (implicit hc: HeaderCarrier,
              auditEv: Auditable[A],
              logEv: Loggable[A]): Future[Either[SendServiceError.type, Unit]] = {
    val request = createRequest(notification, apiSubscriptionFields, config)

    connector.post(request).flatMap {
      case Right(_) =>
        maybeAuditSuccessfulIfInternalPushFor(request)(apiSubscriptionFields, toAudit)
        repo.setStatus(notification.id, Succeeded) // OK to not flatMap from this as presuming re-sending later is idempotent
        logger.info(s"${request.descriptor} succeeded", notification)
        Future.successful(Right(()))
      case Left(e: HttpClientError[_]) =>
        maybeAuditFailureIfInternalPushFor(e)(apiSubscriptionFields, toAudit)
        repo.incrementFailureCount(notification.id)
        logger.error(e.message, notification)
        Future.successful(Left(SendServiceError))
      case Left(e@ErrorResponse(req, res)) =>
        repo.incrementFailureCount(notification.id)
        maybeAuditFailureIfInternalPushFor(e)(apiSubscriptionFields, toAudit)

        val repoSetStatusResult =
          if (Status.isServerError(res.status)) {
            logger.error(s"Status response ${res.status} received while pushing notification to ${req.url}, blocking notifications for client subscription ID", notification)
            repo.setFailedAndBlocked(notification.id, res.status)
            repo.blockFailedButNotBlocked(notification.clientSubscriptionId).map(_.map(_ => ()))
          } else {
            val whenToUnblock = dateTimeService.now().plusMinutes(config.nonBlockingRetryAfterMinutes)
            logger.error(s"Status response ${res.status} received while pushing notification to ${req.url}, setting availableAt to $whenToUnblock", notification)

            (for {
              _ <- repo.setFailedButNotBlocked(notification.id, res.status, whenToUnblock).toFutureCdsResult
              _ <- repo.blockFailedButNotBlocked(notification.clientSubscriptionId).toFutureCdsResult
            } yield ()).value
          }

        repoSetStatusResult.map(_.leftMap { e =>
          logger.error(s"Processing failed for notification due to: ${e.cause}", notification)
          SendServiceError
        })
    }
  }
}

object SendService {
  case object SendServiceError extends CdsError

  private def createRequest(n: Notification,
                            apiSubscriptionFields: ApiSubscriptionFields,
                            config: SendNotificationConfig): PushOrPullRequest = {
    apiSubscriptionFields.fields match {
      case PushCallbackData(Some(url), securityToken) if config.internalClientIds.contains(n.clientId) =>
        InternalPushNotificationRequest(
          n.conversationId,
          securityToken,
          n.payload,
          n.headers,
          url
        )
      case PushCallbackData(Some(url), securityToken) =>
        ExternalPushNotificationRequest(
          n.notificationId,
          url,
          securityToken,
          n.payload)
      case PushCallbackData(None, _) =>
        PullNotificationRequest(
          n.clientSubscriptionId,
          n,
          config.pullUrl)
    }
  }

  private def maybeAuditSuccessfulIfInternalPushFor[A](request: PushOrPullRequest)
                                                      (apiSubscriptionFields: ApiSubscriptionFields,
                                                       toAudit: A)
                                                      (implicit hc: HeaderCarrier,
                                                       auditEv: Auditable[A],
                                                       logEv: Loggable[A],
                                                       auditingService: AuditingService): Unit = request match {
    case r: InternalPushNotificationRequest =>
      auditingService.auditSuccess(
        apiSubscriptionFields.fields,
        r.xmlPayload,
        toAudit,
        AuditType.Outbound
      )
    case _ => ()
  }

  private def maybeAuditFailureIfInternalPushFor[A, R <: CdsRequest](error: PostHttpConnectorError[R])
                                                                    (apiSubscriptionFields: ApiSubscriptionFields,
                                                                     toAudit: A)
                                                                    (implicit hc: HeaderCarrier,
                                                                     auditEv: Auditable[A],
                                                                     logEv: Loggable[A],
                                                                     auditingService: AuditingService): Unit = {
    error.request match {
      case _: InternalPushNotificationRequest =>
        auditingService.auditFailure(
          apiSubscriptionFields.fields,
          error.message,
          toAudit,
          AuditType.Outbound
        )
      case _ => ()
    }
  }
}
