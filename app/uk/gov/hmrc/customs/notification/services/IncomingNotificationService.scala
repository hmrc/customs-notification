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

import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.DeclarantNotFound
import org.mongodb.scala.bson.ObjectId
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.customs.notification.config.{ApiSubscriptionFieldsUrlConfig, MetricsUrlConfig}
import uk.gov.hmrc.customs.notification.models.ApiSubscriptionFields.reads
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableRequestMetadata
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits._
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.requests.{ApiSubscriptionFieldsRequest, MetricsRequest}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services.AuditingService.AuditType
import uk.gov.hmrc.customs.notification.services.HttpConnector._
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService._
import uk.gov.hmrc.customs.notification.services.SendService.SendServiceError
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.repo.NotificationRepo.MongoDbError
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class IncomingNotificationService @Inject()(repo: NotificationRepo,
                                            sendService: SendService,
                                            dateTimeService: DateTimeService,
                                            apiSubsFieldsUrlConfig: ApiSubscriptionFieldsUrlConfig,
                                            metricsUrlConfig: MetricsUrlConfig,
                                            httpConnector: HttpConnector,
                                            auditingService: AuditingService,
                                            logger: NotificationLogger,
                                            objectIdService: ObjectIdService)(implicit ec: ExecutionContext) {
  def process(payload: NodeSeq)(implicit requestMetadata: RequestMetadata, hc: HeaderCarrier): Future[Either[IncomingNotificationServiceError, Unit]] = {
    val apiSubsFieldsRequest = ApiSubscriptionFieldsRequest(requestMetadata.clientSubscriptionId, apiSubsFieldsUrlConfig.url)

    httpConnector.get(apiSubsFieldsRequest).flatMap {
      case Left(error) =>
        handleApiSubscriptionsFieldsError(error)
      case Right(apiSubsFields) =>
        auditingService.auditSuccess(apiSubsFields.fields, payload.toString, requestMetadata, AuditType.Inbound)

        val newNotification = notificationFrom(objectIdService.newId(), payload, apiSubsFields.clientId, requestMetadata)
        (for {
          failedAndBlockedExist <- repo.checkFailedAndBlockedExist(requestMetadata.clientSubscriptionId).toFutureCdsResult
          notificationStatus = if (failedAndBlockedExist) FailedAndBlocked else SavedToBeSent
          _ <- repo.saveWithLock(newNotification, notificationStatus).toFutureCdsResult
          _ = sendMetrics(newNotification)
        } yield notificationStatus).value.flatMap {
          case Left(_: MongoDbError) =>
            Future.successful(Left(InternalServiceError))
          case Right(FailedAndBlocked) =>
            logger.info(s"Existing failed and blocked notifications found, incoming notification saved as failed and blocked", newNotification)
            Future.successful(Right(()))
          case Right(SavedToBeSent) =>
            logger.info("Saved notification", newNotification)

            sendService.send(newNotification, requestMetadata, apiSubsFields).flatMap {
                case Left(SendServiceError) =>
                  Future.successful(Left(InternalServiceError))
                case Right(_) =>
                  Future.successful(Right(()))
              }
        }
    }
  }

  private def handleApiSubscriptionsFieldsError(e: HttpConnectorError[ApiSubscriptionFieldsRequest])
                                               (implicit requestMetadata: RequestMetadata): Future[Left[IncomingNotificationServiceError, Nothing]] = e match {
    case ErrorResponse(ApiSubscriptionFieldsRequest(_, _), response) if response.status == NOT_FOUND =>
      logger.error("Declarant data not found for notification", requestMetadata)
      Future.successful(Left(DeclarantNotFound))
    case e: HttpConnectorError[_] =>
      logger.error(e.message, requestMetadata)
      Future.successful(Left(InternalServiceError))
  }

  private def sendMetrics(notification: Notification)(implicit hc: HeaderCarrier): Unit = {

    def metricsRequest = MetricsRequest(
      notification.conversationId,
      notification.metricsStartDateTime,
      dateTimeService.now(),
      metricsUrlConfig.url)

    httpConnector.post(metricsRequest)
      .map(_.left.foreach(e => logger.error(e.message, notification)))
  }

  private def notificationFrom(newId: ObjectId, xml: NodeSeq, clientId: ClientId, requestMetadata: RequestMetadata): Notification = {
    val headers =
      (requestMetadata.maybeBadgeId ++
        requestMetadata.maybeSubmitterNumber ++
        requestMetadata.maybeCorrelationId ++
        requestMetadata.maybeIssueDateTime).toSeq

    Notification(
      newId,
      requestMetadata.clientSubscriptionId,
      clientId,
      requestMetadata.notificationId,
      requestMetadata.conversationId,
      headers,
      xml.toString(),
      requestMetadata.startTime
    )
  }
}

object IncomingNotificationService {

  sealed trait IncomingNotificationServiceError extends CdsError

  case object InternalServiceError extends IncomingNotificationServiceError

  case object DeclarantNotFound extends IncomingNotificationServiceError
}