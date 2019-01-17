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
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
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

  def handleNotification(xml: NodeSeq, metaData: RequestMetaData, declarantCallbackData: DeclarantCallbackData)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val headers: Seq[Header] = (metaData.mayBeBadgeId ++ metaData.mayBeEoriNumber ++ metaData.maybeCorrelationId).toSeq

    val notificationWorkItem = NotificationWorkItem(metaData.clientId, Notification(metaData.conversationId, headers, xml.toString, MimeTypes.XML))
    saveNotificationToDatabaseAndPush(notificationWorkItem, declarantCallbackData)
  }

  private def saveNotificationToDatabaseAndPush(notificationWorkItem: NotificationWorkItem, declarantCallbackData: DeclarantCallbackData)(implicit hc: HeaderCarrier): Future[Boolean] = {

    def inProgress(item: NotificationWorkItem): ProcessingStatus = InProgress

    def complete(id: BSONObjectID, status: ResultStatus): Future[Boolean] = {
      notificationWorkItemRepo.complete(id, status)
      Future.successful(if (status == Succeeded) true else false)
    }

    notificationWorkItemRepo.pushNew(notificationWorkItem, notificationWorkItemRepo.now, inProgress _).flatMap { workItem =>
      pushClientNotificationWorkItemService.send(declarantCallbackData, workItem.item).flatMap { result =>
        if (result) {
          complete(workItem.id, Succeeded)
        } else {
          complete(workItem.id, Failed)
        }
      }.recover { //recover for failed send
        case t: Throwable =>
          logger.error(s"push failed ${t.getMessage}")
          false
      }
    }.recover { //recover for failed pushNew
      case t: Throwable =>
        logger.error(s"saving workItem failed ${t.getMessage}")
        false
    }
  }

//    notificationWorkItemRepo.pushNew()save(clientNotification).map {
//      case true =>
//        notificationDispatcher.process(Set(clientNotification.csid))
//        true
//      case false => logger.error("Dispatcher failed to process the notification")
//        false
//    }.recover {
//      case t: Throwable =>
//        logger.error(s"Processing failed ${t.getMessage}")
//        false
//    }
//  }
}
