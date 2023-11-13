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

import play.api.http.Status
import uk.gov.hmrc.customs.notification.config.SendNotificationConfig
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.{loggableNotificationWorkItem, loggableObjectId, loggableTuple}
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.requests.{ExternalPushNotificationRequest, InternalPushNotificationRequest, PostRequest, PullNotificationRequest}
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, FailedButNotBlocked, NotificationWorkItem, PushCallbackData}
import uk.gov.hmrc.customs.notification.services.HttpConnector._
import uk.gov.hmrc.customs.notification.services.SendService.{SendServiceError, createRequest}
import uk.gov.hmrc.customs.notification.util.FutureCdsResult.Implicits._
import uk.gov.hmrc.customs.notification.util.NotificationWorkItemRepo.MongoDbError
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Succeeded
import uk.gov.hmrc.mongo.workitem.WorkItem

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendService @Inject()(connector: HttpConnector,
                            repo: NotificationWorkItemRepo,
                            config: SendNotificationConfig,
                            dateTimeService: DateTimeService,
                            logger: NotificationLogger)(implicit ec: ExecutionContext) {

  def send(mongoWorkItem: WorkItem[NotificationWorkItem],
           apiSubscriptionFields: ApiSubscriptionFields)(implicit hc: HeaderCarrier): Future[Either[SendServiceError.type , Unit]] = {
    val request = createRequest(mongoWorkItem, apiSubscriptionFields, config)
    (for {
      _ <- connector.post(request).toFutureCdsResult
      _ = logger.info(s"${request.descriptor} succeeded", mongoWorkItem.item)
      _ <- repo.setCompletedStatus(mongoWorkItem, Succeeded).toFutureCdsResult
    } yield ()).value.flatMap {
      case Right(_) =>
        Future.successful(Right(()))
      case Left(HttpClientError(r, exception)) =>
        logger.error(s"${request.descriptor} failed with URL ${r.url.toString} and exception ${exception.getMessage}.", mongoWorkItem.item)
        repo.incrementFailureCount(mongoWorkItem.id)
        Future.successful(Left(SendServiceError))
      case Left(ErrorResponse(req, res)) =>
        repo.incrementFailureCount(mongoWorkItem.id)

        (if (Status.isServerError(res.status)) {
          logger.error(s"Status response ${res.status} received while pushing notification to ${req.url}," +
            s" blocking notifications for client subscriptionID", mongoWorkItem.item)
          repo.setFailedAndBlocked(mongoWorkItem.id, res.status)
        } else {
          val availableAt = dateTimeService.now().plusMinutes(config.nonBlockingRetryAfterMinutes)
          logger.error(s"Status response ${res.status} received while pushing notification to ${req.url}, setting availableAt to $availableAt", mongoWorkItem.item)
          repo.setCompletedStatusWithAvailableAt(mongoWorkItem.id, FailedButNotBlocked.convertToHmrcProcessingStatus, res.status, availableAt)
        }).map {
          case Right(_) =>
            Right(())
          case Left(e: MongoDbError) =>
            logger.error(s"Processing failed for notification due to: ${e.cause}", mongoWorkItem.id -> mongoWorkItem.item)
            Left(SendServiceError)
        }
      case Left(e: MongoDbError) =>
        logger.error(s"Processing failed for notification due to: ${e.cause}", mongoWorkItem.id -> mongoWorkItem.item)
        Future.successful(Left(SendServiceError))
    }
  }
}

object SendService {
  case object SendServiceError extends CdsError

  private def createRequest(mongoWorkItem: WorkItem[NotificationWorkItem],
                            apiSubscriptionFields: ApiSubscriptionFields,
                            config: SendNotificationConfig): PostRequest = {
    val n = mongoWorkItem.item.notification
    apiSubscriptionFields.fields match {
      case PushCallbackData(Some(url), securityToken) if config.internalClientIds.contains(mongoWorkItem.item.clientId) =>
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
          mongoWorkItem.item._id,
          n,
          config.pullUrl)
    }
  }
}
