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

import play.api.mvc.Headers
import uk.gov.hmrc.customs.notification.connectors.{NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

sealed trait SendNotificationResult

case object NotificationSuccessfullyPushed extends SendNotificationResult

case object NotificationPassedOnToPull extends SendNotificationResult

case object DeclarantCallbackDataNotFound extends SendNotificationResult

@Singleton
class CustomsNotificationService @Inject()(logger: NotificationLogger,
                                           publicNotificationRequestService: PublicNotificationRequestService,
                                           pushConnector: PublicNotificationServiceConnector,
                                           queueConnector: NotificationQueueConnector
                                          ) {

  def sendNotification(xml: NodeSeq, headers: Headers)(implicit hc: HeaderCarrier): Future[SendNotificationResult] = {
    // TODO: Headers should have not been passed on to the service from controller.
    // fetch the values required from headers in controller, validate them and pass a populated domain object
    publicNotificationRequestService.createRequest(xml, headers) flatMap {
      case None =>
        Future.successful(DeclarantCallbackDataNotFound)
      case Some(request) =>
        sendAsync(request)
    }
  }

  private def sendAsync(publicNotificationRequest: PublicNotificationRequest): Future[SendNotificationResult] = {

    pushConnector.send(publicNotificationRequest)
      .map(_ => NotificationSuccessfullyPushed)
      .recoverWith {
        case _ =>
          queueConnector.enqueue(publicNotificationRequest).map(_ => NotificationPassedOnToPull)
      }

  }
}
