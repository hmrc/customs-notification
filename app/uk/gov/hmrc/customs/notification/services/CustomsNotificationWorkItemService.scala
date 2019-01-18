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
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.workitem._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationWorkItemService @Inject()(logger: NotificationLogger,
                                                   notificationWorkItemRepo: NotificationWorkItemRepo,
                                                   notificationDispatcher: NotificationDispatcher,
                                                   pushClientNotificationWorkItemService: PushClientNotificationWorkItemService) {

  def handleNotification(xml: NodeSeq,
                         metaData: RequestMetaData,
                         declarantCallbackData: DeclarantCallbackData)
                        (implicit hc: HeaderCarrier): Future[Boolean] = {

    val headers: Seq[Header] = (metaData.mayBeBadgeId ++ metaData.mayBeEoriNumber ++ metaData.maybeCorrelationId).toSeq

    val notificationWorkItem = NotificationWorkItem(metaData.clientId, Some(metaData.startTime.toDateTime),
      Notification(metaData.conversationId, headers, xml.toString, MimeTypes.XML))

    saveNotificationToDatabaseAndPush(notificationWorkItem, declarantCallbackData)
  }

  private def saveNotificationToDatabaseAndPush(notificationWorkItem: NotificationWorkItem,
                                                declarantCallbackData: DeclarantCallbackData)
                                               (implicit hc: HeaderCarrier): Future[Boolean] = {

    notificationWorkItemRepo.saveWithLock(notificationWorkItem).flatMap { workItem =>
      pushClientNotificationWorkItemService.send(declarantCallbackData, workItem.item).flatMap { result =>
        if (result) {
          notificationWorkItemRepo.setCompletedStatus(workItem.id, Succeeded)
        } else {
          notificationWorkItemRepo.setCompletedStatus(workItem.id, Failed)
        }
        Future.successful(true)
      }.recover {
        case t: Throwable =>
          logger.error(s"push failed for notification work item id: ${workItem.id} due to: ${t.getMessage}")
          true
      }
    }.recover {
      case t: Throwable =>
        logger.error(s"saving notification work item with csid: ${notificationWorkItem.id} and conversationId: ${notificationWorkItem.notification.conversationId} failed due to: ${t.getMessage}")
        false
    }
  }
}
