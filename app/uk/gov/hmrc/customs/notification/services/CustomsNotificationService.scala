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

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.connectors.{NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, PublicNotificationRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationService @Inject()(logger: NotificationLogger,
                                           publicNotificationRequestService: PublicNotificationRequestService,
                                           pushConnector: PublicNotificationServiceConnector,
                                           queueConnector: NotificationQueueConnector
                                          ) {
  def handleNotification(xml: NodeSeq, callbackDetails: DeclarantCallbackData, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Unit] =
    pushAndThenPassOnToPullIfPushFails(publicNotificationRequestService.createRequest(xml, callbackDetails, metaData))


  def pushAndThenPassOnToPullIfPushFails(publicNotificationRequest: PublicNotificationRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    pushConnector.send(publicNotificationRequest).map(_ =>
      logger.info("Notification has been pushed")(hc)
    ).recover {

      case _ =>
        queueConnector.enqueue(publicNotificationRequest).map(_ =>
          logger.info("Notification has been passed on to PULL service")(hc)
        )

    }
  }
}
