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
import uk.gov.hmrc.customs.notification.config.SendConfig
import uk.gov.hmrc.customs.notification.connectors.SendConnector
import uk.gov.hmrc.customs.notification.connectors.SendConnector._
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.services.SendService._
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendService @Inject()(sendConnector: SendConnector,
                            repo: Repository,
                            config: SendConfig,
                            dateTimeService: DateTimeService,
                            logger: Logger)(implicit ec: ExecutionContext) {

  def send(notification: Notification,
           clientSendData: ClientSendData)
          (implicit hc: HeaderCarrier): Future[Either[SendError.type, Unit]] =
    send(notification, notification, clientSendData)

  def send[A: Auditable : Loggable](notification: Notification,
                                    toAudit: A,
                                    clientSendData: ClientSendData)
                                   (implicit hc: HeaderCarrier): Future[Either[SendError.type, Unit]] = {
    sendConnector.send(notification, clientSendData, toAudit).flatMap {
      case Right(SuccessfullySent(request)) =>
        repo.setSucceeded(notification.id)
        logger.info(s"${request.name.capitalize} succeeded", notification)
        Future.successful(Right(()))
      case Left(error: SendError) =>
        repo.incrementFailureCount(notification.id)

        (error match {
          case e: ClientSendError =>
            val whenToUnblock = dateTimeService.now().plusSeconds(config.failedAndNotBlockedAvailableAfter.toSeconds)
            logger.error(s"Sending notification failed. Setting availableAt to $whenToUnblock", notification)
            repo.setFailedButNotBlocked(notification.id, e.maybeStatus, whenToUnblock)
          case e: ServerSendError =>
            logger.error(s"Sending notification failed. Blocking notifications for client subscription ID", notification)
            repo.setFailedAndBlocked(notification.id, e.status)
            repo.blockAllFailedButNotBlocked(notification.clientSubscriptionId).map(_.map(_ => ()))
        }).map(_.leftMap(_ => SendError))
    }
  }
}

object SendService {
  case object SendError
}
