/*
 * Copyright 2023 HM Revenue & Customs
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

import cats.implicits.toBifunctorOps
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.connectors.SendNotificationConnector
import uk.gov.hmrc.customs.notification.connectors.SendNotificationConnector._
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services.SendNotificationService._
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Succeeded

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendNotificationService @Inject()(sendNotificationConnector: SendNotificationConnector,
                                        repo: NotificationRepo,
                                        config: AppConfig,
                                        dateTimeService: DateTimeService,
                                        logger: NotificationLogger)(implicit ec: ExecutionContext) {

  def send(notification: Notification,
           clientSendData: ClientSendData)
          (implicit hc: HeaderCarrier,
           auditEv: Auditable[Notification],
           logEv: Loggable[Notification]): Future[Either[SendNotificationError.type, Unit]] =
    send(notification, notification, clientSendData)(auditEv, logEv, hc)

  def send[A: Auditable : Loggable](notification: Notification,
                                    toAudit: A,
                                    clientSendData: ClientSendData)
                                   (implicit hc: HeaderCarrier): Future[Either[SendNotificationError.type, Unit]] = {
    sendNotificationConnector.send(notification, clientSendData, toAudit).flatMap {
      case Right(SuccessfullySent(request)) =>
        repo.setStatus(notification.id, Succeeded)
        logger.info(s"${request.name} succeeded", notification)
        Future.successful(Right(()))
      case Left(error: SendError) =>
        repo.incrementFailureCount(notification.id)

        (error match {
          case e: ClientSendError =>
            val whenToUnblock = dateTimeService.now().plusMinutes(config.failedAndNotBlockedAvailableAfterMinutes)
            logger.error(s"Sending notification failed. Setting availableAt to $whenToUnblock", notification)
            repo.setFailedButNotBlocked(notification.id, e.maybeStatus, whenToUnblock)
          case e: ServerSendError =>
            logger.error(s"Sending notification failed. Blocking notifications for client subscription ID", notification)
            repo.setFailedAndBlocked(notification.id, Some(e.status))
            repo.blockAllFailedButNotBlocked(notification.clientSubscriptionId).map(_.map(_ => ()))
        }).map(_.leftMap(_ => SendNotificationError))
    }
  }
}

object SendNotificationService {
  case object SendNotificationError extends CdsError
}
