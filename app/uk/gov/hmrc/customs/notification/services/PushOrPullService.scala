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

import javax.inject.Inject
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, MapResultError, NotificationQueueConnector}
import uk.gov.hmrc.customs.notification.domain.{ApiSubscriptionFields, ClientId, ClientNotification, ClientSubscriptionId, DeclarantCallbackData, HasId, NonHttpError, NotificationWorkItem, PushNotificationRequest, PushNotificationRequestBody, ResultError}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal




sealed trait ConnectorSource
case object Push extends ConnectorSource
case object Pull extends ConnectorSource
case object GetApiSubscriptionFields extends ConnectorSource
case class PushOrPullError(source: ConnectorSource, resultError: ResultError)

class PushOrPullService @Inject()(
  callbackDetailsConnector: ApiSubscriptionFieldsConnector,
  pushOutboundSwitchService: OutboundSwitchService,
  pull: NotificationQueueConnector
) extends MapResultError {

  def send(n: NotificationWorkItem): Future[Either[PushOrPullError, ConnectorSource]] = {
    implicit val hasId = n

    clientData(n.id).flatMap{
      case Right(fields) =>
        send(n, fields)
      case Left(pushOrPullError) =>
        Future.successful(Left(pushOrPullError))
    }

  }

  private def clientData(csId: ClientSubscriptionId): Future[Either[PushOrPullError, ApiSubscriptionFields]] = {
    callbackDetailsConnector.getClientData(csId.toString).map[Either[PushOrPullError, ApiSubscriptionFields]]{
      case Some(fields) =>
        Right(fields)
      case _ =>
        Left(PushOrPullError(GetApiSubscriptionFields, NonHttpError(new IllegalStateException("Error getting client subscription fields data"))))
    }
    .recover {
      case NonFatal(t) =>
        Left(PushOrPullError(GetApiSubscriptionFields, mapResultError(t)))
    }
  }


  // existing controllers can reuse this method
  def send(n: NotificationWorkItem, apiSubscriptionFields: ApiSubscriptionFields)(implicit hasId: HasId): Future[Either[PushOrPullError, ConnectorSource]] = {
    if (apiSubscriptionFields.isPush) {
      val pnr = pushNotificationRequestFrom(apiSubscriptionFields.fields, n)
      pushOutboundSwitchService.send(ClientId(apiSubscriptionFields.clientId), pnr).map[Either[PushOrPullError, ConnectorSource]]{
        case Right(_) =>
          Right(Push)
        case Left(resultError) =>
          Left(PushOrPullError(Push, resultError))
      }
      .recover{
        case NonFatal(e) =>
          Left(PushOrPullError(Push, mapResultError(e)))
      }
    } else {
      pull(n)
    }
  }

  private def pushNotificationRequestFrom(declarantCallbackData: DeclarantCallbackData,
                                          n: NotificationWorkItem): PushNotificationRequest = {

    PushNotificationRequest(
      n.id.id.toString,
      PushNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        n.notification.conversationId.id.toString,
        n.notification.headers,
        n.notification.payload
      ))
  }

  private def pull(n: NotificationWorkItem): Future[Either[PushOrPullError, ConnectorSource]] = {
    val clientNotification = clientNotificationFrom(n)

    pull.enqueue(clientNotification).map[Either[PushOrPullError, ConnectorSource]] { _ =>
      Right(Pull)
    }
    .recover {
      case NonFatal(t) =>
        Left(PushOrPullError(Pull, mapResultError(t)))
    }
  }

  private def clientNotificationFrom(n: NotificationWorkItem): ClientNotification = {
    val notUsedBsonId = "123456789012345678901234"
    ClientNotification(n.id, n.notification, None, None, BSONObjectID.parse(notUsedBsonId).get)
  }
}
