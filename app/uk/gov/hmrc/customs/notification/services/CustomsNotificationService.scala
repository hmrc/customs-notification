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
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, PublicNotificationRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.{Elem, NodeSeq}

sealed trait SendNotificationResult

case object NotificationSent extends SendNotificationResult
case object DeclarantCallbackDataNotFound extends SendNotificationResult

@Singleton
class CustomsNotificationService @Inject() (logger: NotificationLogger,
                                            publicNotificationRequestService: PublicNotificationRequestService,
                                            pushConnector: PublicNotificationServiceConnector,
                                            queueConnector: NotificationQueueConnector
                                           ) {
  def handleNotification(xml: NodeSeq, callbackDetails: DeclarantCallbackData, metaData: RequestMetaData): Future[Unit] = ???


  def sendNotification(xml: NodeSeq, callbackDetaila: DeclarantCallbackData, idsRcvd: RequestMetaData)(implicit hc: HeaderCarrier): Future[Unit] = ???

  def sendNotification(xml: NodeSeq, headers: Headers)(implicit hc: HeaderCarrier): Future[SendNotificationResult] = {

    publicNotificationRequestService.createRequest(xml, headers) map {
      case None =>
        DeclarantCallbackDataNotFound
      case Some(request) =>
        sendAsync(request)
        NotificationSent
    }
  }

  private def sendAsync(publicNotificationRequest: PublicNotificationRequest): Future[Unit] = {
    Future {
      pushConnector.send(publicNotificationRequest)
        .recoverWith {
          case _ =>
            queueConnector.enqueue(publicNotificationRequest).map(_ => ())
        }
    }
  }
}
