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

import play.api.http.MimeTypes
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.PushNotificationRequest.pushNotificationRequestFrom
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.WorkItem

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationService @Inject()(logger: NotificationLogger,
                                           notificationWorkItemRepo: NotificationWorkItemRepo,
                                           pushOrPullService: PushOrPullService,
                                           metricsService: CustomsNotificationMetricsService,
                                           customsNotificationConfig: CustomsNotificationConfig,
                                           dateTimeService: DateTimeService,
                                           auditingService: AuditingService)
                                          (implicit ec: ExecutionContext) {

  type HasSaved = Boolean

  def handleNotification(xml: NodeSeq,
                         metaData: RequestMetaData,
                         apiSubscriptionFields: ApiSubscriptionFields)(implicit hc: HeaderCarrier): Future[HasSaved] = {

    implicit val hasId: RequestMetaData = metaData
    val notificationWorkItem = NotificationWorkItem(metaData.clientSubscriptionId,
      ClientId(apiSubscriptionFields.clientId),
      Some(metaData.startTime.toInstant),
      Notification(Some(metaData.notificationId), metaData.conversationId, buildHeaders(metaData), xml.toString, MimeTypes.XML))

    val pnr = pushNotificationRequestFrom(apiSubscriptionFields.fields, notificationWorkItem)
    auditingService.auditNotificationReceived(pnr)

    (for {
      isAnyPF <- notificationWorkItemRepo.permanentlyFailedAndHttp5xxByCsIdExists(notificationWorkItem.clientSubscriptionId)
      hasSaved <- saveNotificationToDatabaseAndPushOrPullIfNotAnyPF(notificationWorkItem, isAnyPF, apiSubscriptionFields)
    } yield hasSaved)
      .recover {
        case NonFatal(e) =>
          logger.error(s"A problem occurred while handling notification work item with csid: ${notificationWorkItem._id.toString} and conversationId: ${notificationWorkItem.notification.conversationId.toString} due to: $e")
          false
      }
  }

  def buildHeaders(metaData: RequestMetaData): Seq[Header] = {
    (metaData.maybeBadgeIdHeader ++ metaData.maybeSubmitterHeader ++ metaData.maybeCorrelationIdHeader ++ metaData.maybeIssueDateTimeHeader).toSeq
  }

  private def saveNotificationToDatabaseAndPushOrPullIfNotAnyPF(notificationWorkItem: NotificationWorkItem,
                                                                isAnyPF: Boolean,
                                                                apiSubscriptionFields: ApiSubscriptionFields)(implicit rm: HasId, hc: HeaderCarrier): Future[HasSaved] = {

    val status = if (isAnyPF) {
      logger.info(s"Existing permanently failed notifications found for client id: ${notificationWorkItem.clientId.toString}. " +
        "Setting notification to permanently failed")
      PermanentlyFailed
    }
    else {
      InProgress
    }

    notificationWorkItemRepo.saveWithLock(notificationWorkItem, status).map(
      workItem => {
        recordNotificationEndTimeMetric(workItem)
        if (status == InProgress) pushOrPull(workItem, apiSubscriptionFields)
        true
      }
    ).recover {
      case NonFatal(e) =>
        logger.error(s"failed saving notification work item as permanently failed with csid: ${notificationWorkItem._id.toString} and conversationId: ${notificationWorkItem.notification.conversationId.toString} due to: $e")
        false
    }
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem],
                         apiSubscriptionFields: ApiSubscriptionFields)(implicit rm: HasId, hc: HeaderCarrier): Future[HasSaved] = {

    pushOrPullService.send(workItem.item, apiSubscriptionFields).map {
      case Right(connector) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        val prefix = connector match {
          case Pull => s"Placing on $connector queue ${Succeeded.name}"
          case _    => s"$connector ${Succeeded.name}"
        }
        logger.info(prefix + s" for workItemId ${workItem.id.toString}")
        true
      case Left(pushOrPullError) =>
        val prefix = pushOrPullError.source match {
          case Pull => s"Placing on ${pushOrPullError.source} queue failed ${pushOrPullError.toString}"
          case _    => s"${pushOrPullError.source} failed ${pushOrPullError.toString}"
        }
        logger.warn(prefix + s" for workItemId ${workItem.id.toString}")
        (for {
          _ <- notificationWorkItemRepo.incrementFailureCount(workItem.id)
          _ <- {
            pushOrPullError.resultError match {
              case httpResultError: HttpResultError if httpResultError.is3xx || httpResultError.is4xx =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusMinutes(customsNotificationConfig.notificationConfig.nonBlockingRetryAfterMinutes)
                logger.error(s"Status response ${httpResultError.status} received while pushing notification, setting availableAt to $availableAt")
                notificationWorkItemRepo.setCompletedStatusWithAvailableAt(workItem.id, PermanentlyFailed, httpResultError.status, availableAt)
              case HttpResultError(status, _) =>
                notificationWorkItemRepo.setPermanentlyFailed(workItem.id, status)
              case NonHttpError(cause) =>
                logger.error(s"Error received while pushing notification: ${cause.getMessage}")
                Future.successful(())
            }
          }
        } yield ()).recover {
          case NonFatal(e) =>
            logger.error("Error updating database", e)
            ()
        }
        true
    }.recover {
      case NonFatal(e) =>
        logger.error(s"failed push/pulling notification work item with csid: ${workItem.item._id.toString} and conversationId: ${workItem.item.notification.conversationId.toString} due to: $e")
        false
    }
  }

  private def recordNotificationEndTimeMetric(workItem: WorkItem[NotificationWorkItem])(implicit hc: HeaderCarrier): Unit = {
    metricsService.notificationMetric(workItem.item)
  }
}
