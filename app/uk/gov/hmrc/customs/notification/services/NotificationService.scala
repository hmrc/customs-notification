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

import play.api.http.MimeTypes
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.customs.notification.config.{ApiSubscriptionFieldsUrlConfig, MetricsUrlConfig}
import uk.gov.hmrc.customs.notification.models.ApiSubscriptionFields.reads
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableRequestMetadata
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits._
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.requests.{ApiSubscriptionFieldsRequest, MetricsRequest}
import uk.gov.hmrc.customs.notification.services.AuditingService.AuditType
import uk.gov.hmrc.customs.notification.services.HttpConnector._
import uk.gov.hmrc.customs.notification.services.NotificationService._
import uk.gov.hmrc.customs.notification.services.SendService.SendServiceError
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class NotificationService @Inject()(logger: NotificationLogger,
                                    workItemRepo: NotificationWorkItemRepo,
                                    pushOrPullService: SendService,
                                    dateTimeService: DateTimeService,
                                    apiSubsFieldsUrlConfig: ApiSubscriptionFieldsUrlConfig,
                                    metricsUrlConfig: MetricsUrlConfig,
                                    httpConnector: HttpConnector,
                                    auditingService: AuditingService)(implicit ec: ExecutionContext) {
  def handleNotification(xml: NodeSeq, requestMetadata: RequestMetadata)(implicit hc: HeaderCarrier): Future[Either[NotificationServiceError, Unit]] = {
    val apiSubsFieldsRequest = ApiSubscriptionFieldsRequest(
      requestMetadata.clientSubscriptionId,
      apiSubsFieldsUrlConfig.url)

    def metricsRequest = MetricsRequest(
      requestMetadata.conversationId,
      requestMetadata.startTime,
      dateTimeService.now(),
      metricsUrlConfig.url)

    (for {
      apiSubsFields <- FutureCdsResult(httpConnector.get[ApiSubscriptionFields](apiSubsFieldsRequest))
      clientId = apiSubsFields.clientId
      workItem = workItemFrom(xml, clientId, requestMetadata)
      _ = auditingService.auditSuccess(apiSubsFields.fields, xml.toString, requestMetadata, AuditType.Inbound)
      isAnyPF <- workItemRepo.failedAndBlockedWithHttp5xxByCsIdExists(workItem._id).toFutureCdsResult
      status = statusFrom(isAnyPF, clientId, requestMetadata, logger)
      workItem <- workItemRepo.saveWithLock(workItem, status).toFutureCdsResult
      _ <- if (status == SuccessfullyCommunicated) {
        pushOrPullService.send(workItem, apiSubsFields).toFutureCdsResult
      } else {
        Future.successful(Right(())).toFutureCdsResult
      }
      _ = logger.info("Saved notification", clientId -> requestMetadata)
      _ <- httpConnector.post(metricsRequest).toFutureCdsResult
    } yield ()).value.map {
      case Right(_) => Right(())
      case Left(ErrorResponse(ApiSubscriptionFieldsRequest(_, _), response)) if response.status == NOT_FOUND =>
        logger.error(s"Declarant data not found for notification", requestMetadata)
        Left(DeclarantNotFound)
      case Left(SendServiceError) =>
        Left(NotificationServiceGenericError)
    }
  }
}

object NotificationService {

  sealed trait NotificationServiceError extends CdsError

  case object NotificationServiceGenericError extends NotificationServiceError

  case object DeclarantNotFound extends NotificationServiceError

  private def statusFrom(isAnyPF: Boolean, clientId: ClientId, requestMetadata: RequestMetadata, logger: NotificationLogger): CustomProcessingStatus = {
    if (isAnyPF) {
      logger.info(s"Existing permanently failed notifications found for client id: $clientId. Setting notification to permanentlyFailed", clientId -> requestMetadata)
      FailedAndBlocked
    } else {
      SuccessfullyCommunicated
    }
  }

  private def workItemFrom(xml: NodeSeq, clientId: ClientId, requestMetadata: RequestMetadata): NotificationWorkItem = {
    NotificationWorkItem(
      requestMetadata.clientSubscriptionId,
      clientId,
      requestMetadata.startTime,
      Notification(requestMetadata.notificationId,
        requestMetadata.conversationId,
        (requestMetadata.maybeBadgeId ++
          requestMetadata.maybeSubmitterNumber ++
          requestMetadata.maybeCorrelationId ++
          requestMetadata.maybeIssueDateTime).toSeq,
        xml.toString,
        MimeTypes.XML))
  }
}