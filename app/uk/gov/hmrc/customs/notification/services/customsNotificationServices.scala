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
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, NotificationWorkItemRepo}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.workitem.{Failed, PermanentlyFailed, Succeeded}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

trait CustomsNotificationService {

  def buildHeaders(metaData: RequestMetaData): Seq[Header] = {
    (metaData.mayBeBadgeId ++ metaData.mayBeEoriNumber ++ metaData.maybeCorrelationId).toSeq
  }
}

@Singleton
class CustomsNotificationClientWorkerService @Inject()(logger: NotificationLogger,
                                                       clientNotificationRepo: ClientNotificationRepo,
                                                       notificationDispatcher: NotificationDispatcher,
                                                       pullClientNotificationService: PullClientNotificationService)
  extends CustomsNotificationService {

  def handleNotification(xml: NodeSeq, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val clientNotification = ClientNotification(metaData.clientSubscriptionId, Notification(metaData.conversationId,
      buildHeaders(metaData), xml.toString, MimeTypes.XML), None, Some(metaData.startTime.toDateTime))

    saveNotificationToDatabaseAndCallDispatcher(clientNotification, metaData)
  }

  private def saveNotificationToDatabaseAndCallDispatcher(clientNotification: ClientNotification,
                                                          metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Boolean] = {

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
class CustomsNotificationRetryService @Inject()(logger: NotificationLogger,
                                                notificationWorkItemRepo: NotificationWorkItemRepo,
                                                pushClientNotificationRetryService: PushClientNotificationRetryService,
                                                pullClientNotificationRetryService: PullClientNotificationRetryService)
  extends CustomsNotificationService {

  def handleNotification(xml: NodeSeq,
                         metaData: RequestMetaData,
                         apiSubscriptionFields: ApiSubscriptionFields)
                        (implicit hc: HeaderCarrier): Future[Boolean] = {

    val notificationWorkItem = NotificationWorkItem(metaData.clientSubscriptionId,
      ClientId(apiSubscriptionFields.clientId),
      Some(metaData.startTime.toDateTime),
      Notification(metaData.conversationId, buildHeaders(metaData), xml.toString, MimeTypes.XML))

      saveNotificationToDatabaseAndPushOrPull(notificationWorkItem, apiSubscriptionFields)
  }

  private def saveNotificationToDatabaseAndPushOrPull(notificationWorkItem: NotificationWorkItem,
                                                apiSubscriptionFields: ApiSubscriptionFields)
                                               (implicit hc: HeaderCarrier): Future[Boolean] = {

    val logMsgBuilder = StringBuilder.newBuilder
    notificationWorkItemRepo.saveWithLock(notificationWorkItem).flatMap { workItem =>
      val pushPullResult = if (apiSubscriptionFields.fields.callbackUrl.isEmpty) {
        ("pull", pullClientNotificationRetryService.send(notificationWorkItem))
      } else {
        ("push", pushClientNotificationRetryService.send(apiSubscriptionFields, workItem.item))
      }
      pushPullResult._2.flatMap { result =>
        logMsgBuilder.append(s"${pushPullResult._1} for workItemId ${workItem.id.stringify}")
          if (result) {
            notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
            logMsgBuilder.insert(5, "succeeded ")
            logger.info(logMsgBuilder.toString())
          } else {
            if (pushPullResult._1 == "pull") {
              notificationWorkItemRepo.setCompletedStatus(workItem.id, Failed)
              logMsgBuilder.insert(5, "failed ")
            } else {
              notificationWorkItemRepo.setCompletedStatus(workItem.id, PermanentlyFailed)
              logMsgBuilder.insert(5, "permanently failed ")
            }
            logger.error(logMsgBuilder.toString())
          }
          Future.successful(true)
        }.recover {
        case t: Throwable =>
          logger.error(s"processing failed for notification work item id: ${workItem.id.stringify} due to: ${t.getMessage}")
          true
      }
    }.recover {
      case t: Throwable =>
        logger.error(s"failed saving notification work item with csid: ${notificationWorkItem.id.toString} and conversationId: ${notificationWorkItem.notification.conversationId.toString} due to: ${t.getMessage}")
        false
    }
  }
}
