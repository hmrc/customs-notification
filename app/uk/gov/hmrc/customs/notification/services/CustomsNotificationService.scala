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

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.http.HeaderNames
import uk.gov.hmrc.customs.notification.connectors.{GoogleAnalyticsSenderConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
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
  def handleNotification(xml: NodeSeq, callbackDetails: DeclarantCallbackData, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Unit] = {
    gaConnector.send("notificationRequestReceived", s"[ConversationId=${metaData.conversationId}] A notification received for delivery")

    val publicNotificationRequest = publicNotificationRequestService.createRequest(xml, callbackDetails, metaData)

    if (callbackDetails.callbackUrl.isEmpty) {
      logger.info("Notification will be enqueued as callbackUrl is empty")
      passOnToPullQueue(publicNotificationRequest)
    } else {
      saveNotificationToDatabaseAndCallDispatcher(publicNotificationRequest)
    }
  }

  private def saveNotificationToDatabaseAndCallDispatcher(req: PublicNotificationRequest)(implicit hc: HeaderCarrier) = {
    val contentType = hc.headers.toMap[String, String].getOrElse(HeaderNames.CONTENT_TYPE, throw new RuntimeException("Content type missing? Very unlikely"))

    val notification = Notification(hc.headers.seq, req.body.xmlPayload, contentType)
    val clientSubscriptionId = ClientSubscriptionId(UUID.fromString(req.clientSubscriptionId))

    clientNotificationRepo.save(ClientNotification(clientSubscriptionId, notification, DateTime.now())).flatMap {
      case true => notificationDispatcher.process(Set(clientSubscriptionId))
      case false => Future.failed(throw new RuntimeException("Dispatcher failed to process the notification"))
    }

  }

  private def passOnToPullQueue(publicNotificationRequest: PublicNotificationRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    queueConnector.enqueue(publicNotificationRequest)
      .map { _ =>
        gaConnector.send("notificationLeftToBePulled", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification has been left to be pulled")
        logger.info("Notification has been passed on to PULL service")(hc)
        ()
      }.recover {
      case t: Throwable =>
        gaConnector.send("notificationPullRequestFailed", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification Pull request failed")
    }
  }
}
