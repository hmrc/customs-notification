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

import org.bson.types.ObjectId
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.models.repo.{CustomProcessingStatus, FailedAndBlocked, NotificationWorkItem, SuccessfullyCommunicated}
import uk.gov.hmrc.customs.notification.models.requests.{CustomsNotificationsMetricsRequest, MetaDataRequest, PushNotificationRequest}
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, ClientId,  HasId, Header, Notification}
import uk.gov.hmrc.customs.notification.util.{DateTimeHelper, NotificationLogger, NotificationWorkItemRepo}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationService @Inject()(logger: NotificationLogger,
                                           repo: NotificationWorkItemRepo,
                                           pushOrPullService: PushOrPullService,
                                           config: CustomsNotificationConfig,
                                           auditingService: AuditingService,
                                           metricsConnector: CustomsNotificationMetricsConnector)(implicit ec: ExecutionContext) {
  def handleNotification(xml: NodeSeq,
                         metaDataRequest: MetaDataRequest)(implicit hc: HeaderCarrier): Future[Boolean] = {
    implicit val hasId: MetaDataRequest = metaDataRequest
    val clientId: ClientId = metaDataRequest.maybeClientId.getOrElse(ClientId("Unknown"))
    val notificationWorkItem: NotificationWorkItem = NotificationWorkItem(
                                                       metaDataRequest.clientSubscriptionId,
                                                       clientId,
                                                       Some(DateTimeHelper.toDateTime(metaDataRequest.startTime)),
                                                       Notification(Some(metaDataRequest.notificationId),
                                                       metaDataRequest.conversationId,
                                                       buildHeaders(metaDataRequest),
                                                       xml.toString,
                                                       MimeTypes.XML))

    val eventuallyMaybeApiSubscriptionFields: Future[Option[ApiSubscriptionFields]] = pushOrPullService.getApiSubscriptionFields(notificationWorkItem, new ObjectId(), hc)

    eventuallyMaybeApiSubscriptionFields.flatMap(maybeApiSubscriptionFields => maybeApiSubscriptionFields.fold(Future.successful(false)) { apiSubscriptionFields =>
      auditingService.auditNotificationReceived(PushNotificationRequest.buildPushNotificationRequest(apiSubscriptionFields.fields, notificationWorkItem.notification, notificationWorkItem.clientSubscriptionId))
      for {
        isAnyPF <- repo.failedAndBlockedWithHttp5xxByCsIdExists(notificationWorkItem.clientSubscriptionId)
        hasSaved <- saveNotificationToDatabaseAndPushOrPullIfNotAnyPF(notificationWorkItem, isAnyPF, apiSubscriptionFields)
      } yield hasSaved
    })
  }

//TODO rename and move potentially
  private def buildHeaders(metaData: MetaDataRequest): Seq[Header] = {
    (metaData.maybeBadgeIdHeader ++ metaData.maybeSubmitterHeader ++ metaData.maybeCorrelationIdHeader ++ metaData.maybeIssueDateTimeHeader).toSeq
  }

  private def saveNotificationToDatabaseAndPushOrPullIfNotAnyPF(notificationWorkItem: NotificationWorkItem,
                                                                permanentlyFailedNotificationsExist: Boolean,
                                                                apiSubscriptionFields: ApiSubscriptionFields)(implicit rm: HasId, hc: HeaderCarrier): Future[Boolean] = {
    val status: CustomProcessingStatus = if (permanentlyFailedNotificationsExist) {
      logger.info(s"Existing permanently failed notifications found for client id: ${notificationWorkItem.clientId.toString}. " +
        "Setting notification to permanently failed")
      FailedAndBlocked
    }
    else {
      SuccessfullyCommunicated
    }

    repo.saveWithLock(notificationWorkItem, status).map(
      workItem => {
        recordNotificationEndTimeMetric(workItem)
        if (status == SuccessfullyCommunicated) pushOrPullService.pushOrPull(workItem, apiSubscriptionFields, true)
        true
      }
    )
  }


  private def recordNotificationEndTimeMetric(workItem: WorkItem[NotificationWorkItem])(implicit hc: HeaderCarrier): Unit = {
    workItem.item.metricsStartDateTime.fold(Future.successful(())) { startTime =>
      val request = CustomsNotificationsMetricsRequest(
        "NOTIFICATION",
        workItem.item.notification.conversationId,
        DateTimeHelper.toZonedDateTime(startTime),
        DateTimeHelper.zonedDateTimeUtc
      )
      metricsConnector.post(request).recover {
        case NonFatal(e) =>
          logger.error("Error calling customs metrics service", e)(workItem.item)
      }
    }

  }

  def notificationMetric(notificationWorkItem: NotificationWorkItem)(implicit hc: HeaderCarrier): Future[Unit] = {
    implicit val hasId = notificationWorkItem

    notificationWorkItem.metricsStartDateTime.fold(Future.successful(())) { startTime =>
      val request: CustomsNotificationsMetricsRequest = CustomsNotificationsMetricsRequest(
        "NOTIFICATION",
        notificationWorkItem.notification.conversationId,
        DateTimeHelper.toZonedDateTime(startTime),
        DateTimeHelper.zonedDateTimeUtc)

      metricsConnector.post(request).recover {
        case NonFatal(e) =>
          logger.error("Error calling customs metrics service", e)
      }
    }
  }
}
