/*
 * Copyright 2019 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{HasId, _}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, NotificationWorkItemRepo}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.workitem.{InProgress, PermanentlyFailed, Succeeded, WorkItem}

import scala.concurrent.ExecutionContext.Implicits.global // contains blocking code so uses standard scala ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.xml.NodeSeq

trait CustomsNotificationService {

  def buildHeaders(metaData: RequestMetaData): Seq[Header] = {
    (metaData.mayBeBadgeIdHeader ++ metaData.mayBeSubmitterHeader ++ metaData.mayBeCorrelationIdHeader).toSeq
  }
}

@Singleton
class CustomsNotificationClientWorkerService @Inject()(logger: NotificationLogger,
                                                       clientNotificationRepo: ClientNotificationRepo,
                                                       notificationDispatcher: NotificationDispatcher,
                                                       pullClientNotificationService: PullClientNotificationService)
  extends CustomsNotificationService {

  def handleNotification(xml: NodeSeq, metaData: RequestMetaData): Future[Boolean] = {

    val clientNotification = ClientNotification(metaData.clientSubscriptionId, Notification(metaData.conversationId,
      buildHeaders(metaData), xml.toString, MimeTypes.XML), None, Some(metaData.startTime.toDateTime))

    saveNotificationToDatabaseAndCallDispatcher(clientNotification)(metaData)
  }

  private def saveNotificationToDatabaseAndCallDispatcher(clientNotification: ClientNotification)(implicit metaData: HasId): Future[Boolean] = {

    clientNotificationRepo.save(clientNotification).map {
      case true =>
        notificationDispatcher.process(Set(clientNotification.csid))
        true
      case false => logger.error("Dispatcher failed to process the notification")
        false
    }.recover {
      case t: Throwable =>
        logger.error(s"Processing failed ${t.getMessage}")
        false
    }

  }
}

@Singleton
class CustomsNotificationRetryService @Inject()(
  logger: NotificationLogger,
  notificationWorkItemRepo: NotificationWorkItemRepo,
  pushOrPullService: PushOrPullService,
  metricsService: CustomsNotificationMetricsService
) extends CustomsNotificationService {

  type HasSaved = Boolean

  def handleNotification(xml: NodeSeq,
                         metaData: RequestMetaData,
                         apiSubscriptionFields: ApiSubscriptionFields): Future[HasSaved] = {

    implicit val hasId: RequestMetaData = metaData

    val notificationWorkItem = NotificationWorkItem(metaData.clientSubscriptionId,
      ClientId(apiSubscriptionFields.clientId),
      Some(metaData.startTime.toDateTime),
      Notification(metaData.conversationId, buildHeaders(metaData), xml.toString, MimeTypes.XML))

    (for {
      isAnyPF <- notificationWorkItemRepo.permanentlyFailedByClientIdExists(notificationWorkItem.clientSubscriptionId)
      hasSaved <- saveNotificationToDatabaseAndPushOrPullIfNotAnyPF(notificationWorkItem, isAnyPF, apiSubscriptionFields)
    } yield hasSaved)
      .recover {
        case NonFatal(e) =>
          logger.error(s"A problem occurred while handling notification work item with csid: ${notificationWorkItem.id.toString} and conversationId: ${notificationWorkItem.notification.conversationId.toString} due to: ${e.getMessage}")
          false
      }
  }

  private def saveNotificationToDatabaseAndPushOrPullIfNotAnyPF(notificationWorkItem: NotificationWorkItem,
                                                                isAnyPF: Boolean,
                                                                apiSubscriptionFields: ApiSubscriptionFields)(implicit rm: HasId): Future[HasSaved] = {

    logger.info(s"Existing permanently failed notifications found for client id: ${notificationWorkItem.clientId.toString}. " +
      s"Setting notification to permanently failed")
    val status = if (isAnyPF) PermanentlyFailed else InProgress

    notificationWorkItemRepo.saveWithLock(notificationWorkItem, status).map(
      workItem => {
         if (status == InProgress) pushOrPull(workItem, apiSubscriptionFields)
         true
      }
    ).recover {
      case NonFatal(e) =>
        logger.error(s"failed saving notification work item as permanently failed with csid: ${notificationWorkItem.id.toString} and conversationId: ${notificationWorkItem.notification.conversationId.toString} due to: ${e.getMessage}")
        false
    }
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem],
                         apiSubscriptionFields: ApiSubscriptionFields)(implicit rm: HasId): Future[HasSaved] = {

    pushOrPullService.send(workItem.item, apiSubscriptionFields).map {
      case Right(connector) =>
        if (connector == Push) {
          metricsService.notificationMetric(workItem.item)
        }

        notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        logger.info(s"$connector ${Succeeded.name} for workItemId ${workItem.id.stringify}")
        true
      case Left(pushOrPullError) =>
        notificationWorkItemRepo.setCompletedStatus(workItem.id, PermanentlyFailed)
        val msg = s"${pushOrPullError.source} error ${pushOrPullError.toString} for workItemId ${workItem.id.stringify}"
        logger.error(msg, pushOrPullError.resultError.cause)
        true
    }.recover {
      case NonFatal(e) =>
        logger.error(s"failed saving notification work item with csid: ${workItem.item.id.toString} and conversationId: ${workItem.item.notification.conversationId.toString} due to: ${e.getMessage}")
        false
    }
  }
}
