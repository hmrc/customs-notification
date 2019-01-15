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
import uk.gov.hmrc.customs.notification.connectors.GoogleAnalyticsSenderConnector
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._


import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationService @Inject()(logger: NotificationLogger,
                                           gaConnector: GoogleAnalyticsSenderConnector,
                                           clientNotificationRepo: ClientNotificationRepo,
                                           notificationDispatcher: NotificationDispatcher,
                                           pullClientNotificationService: PullClientNotificationService) {

  def handleNotification(xml: NodeSeq, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Boolean] = {
    gaConnector.send("notificationRequestReceived", s"[ConversationId=${metaData.conversationId}] A notification received for delivery")

    val headers: Seq[Header] = (metaData.mayBeBadgeId ++ metaData.mayBeEoriNumber ++ metaData.maybeCorrelationId).toSeq

    val clientNotification = ClientNotification(metaData.clientId, Notification(metaData.conversationId, headers, xml.toString, MimeTypes.XML), None, Some(metaData.startTime.toDateTime))

    saveNotificationToDatabaseAndCallDispatcher(clientNotification, metaData)
  }

  private def saveNotificationToDatabaseAndCallDispatcher(clientNotification: ClientNotification, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Boolean] = {

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
