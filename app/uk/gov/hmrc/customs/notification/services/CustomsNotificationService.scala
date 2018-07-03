/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.inject.{Inject, Singleton}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.notification.connectors.{GoogleAnalyticsSenderConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationService @Inject()(logger: NotificationLogger,
                                           publicNotificationRequestService: PublicNotificationRequestService,
                                           pushConnector: PublicNotificationServiceConnector,
                                           queueConnector: NotificationQueueConnector,
                                           gaConnector: GoogleAnalyticsSenderConnector,
                                           clientNotificationRepo: ClientNotificationRepo,
                                           notificationDispatcher: NotificationDispatcher
                                          ) {

  private val passOnToPullQueueTimeout = 500 //TODO MC configurable?
  private val saveNotificationToDatabaseAndCallDispatcherTimeout = 600 //TODO MC configurable?

  def handleNotification(xml: NodeSeq, callbackDetails: DeclarantCallbackData, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Either[String, String] = {
    gaConnector.send("notificationRequestReceived", s"[ConversationId=${metaData.conversationId}] A notification received for delivery")

    if (callbackDetails.callbackUrl.isEmpty) {
      logger.info("Notification will be enqueued as callbackUrl is empty")
      Await.result(passOnToPullQueue(xml, callbackDetails, metaData), Duration(passOnToPullQueueTimeout, TimeUnit.SECONDS))
    } else {
      val value = hc.headers.seq.map(a => Header(a._1, a._2))
      val clientNotification = ClientNotification(ClientSubscriptionId(UUID.fromString(metaData.clientId)), Notification(ConversationId(metaData.conversationId), value, xml.toString, MimeTypes.XML), None)
      Await.result(saveNotificationToDatabaseAndCallDispatcher(clientNotification), Duration(saveNotificationToDatabaseAndCallDispatcherTimeout, TimeUnit.SECONDS))
    }
  }

  private def saveNotificationToDatabaseAndCallDispatcher(clientNotification: ClientNotification)(implicit hc: HeaderCarrier) = {

    clientNotificationRepo.save(clientNotification).map {
      case true =>
        notificationDispatcher.process(Set(clientNotification.csid))
        Right("success")
      case false => Left("Dispatcher failed to process the notification")
    }

  }

  private def passOnToPullQueue(xml: NodeSeq, callbackDetails: DeclarantCallbackData, metaData: RequestMetaData)(implicit hc: HeaderCarrier) = {
    val publicNotificationRequest = publicNotificationRequestService.createRequest(xml, callbackDetails, metaData)
    queueConnector.enqueue(publicNotificationRequest)
      .map { _ =>
        gaConnector.send("notificationLeftToBePulled", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification has been left to be pulled")
        logger.info("Notification has been passed on to PULL service")(hc)
        Right("success")
      }.recover {
      case _: Throwable =>
        gaConnector.send("notificationPullRequestFailed", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification Pull request failed")
        Left("failed to pass on to pull queue")
    }
  }
}
