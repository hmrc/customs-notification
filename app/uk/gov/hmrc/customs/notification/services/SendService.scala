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

import uk.gov.hmrc.customs.notification.config.RetryDelayConfig
import uk.gov.hmrc.customs.notification.connectors.SendConnector
import uk.gov.hmrc.customs.notification.connectors.SendConnector.*
import uk.gov.hmrc.customs.notification.models.{AuditContext, LogContext, Notification, SendData}
import uk.gov.hmrc.customs.notification.repositories.utils.Errors.MongoDbError
import uk.gov.hmrc.customs.notification.repositories.{BlockedCsidRepository, NotificationRepository}
import uk.gov.hmrc.customs.notification.services.SendService.*
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendService @Inject()(sendConnector: SendConnector,
                            notificationRepo: NotificationRepository,
                            blockedCsidRepo: BlockedCsidRepository,
                            retryDelayConfig: RetryDelayConfig,
                            dateTimeService: DateTimeService)
                           (implicit ec: ExecutionContext) extends Logger {
  def send(notification: Notification,
           clientSendData: SendData)
          (implicit hc: HeaderCarrier,
           lc: LogContext,
           ac: AuditContext): Future[Either[MongoDbError, Result]] = {
    sendConnector.send(notification, clientSendData).flatMap {
      case Right(SuccessfullySent(requestDescriptor)) =>
        notificationRepo.setSucceeded(notification.id)
        logger.info(s"${requestDescriptor.value.capitalize} succeeded")
        Future.successful(Right(Success))
      case Left(error: SendError) =>
        error match {
          case SendConnector.ClientError =>
            val retryDelay = retryDelayConfig.failedButNotBlocked
            val availableAt = dateTimeService.now().plusSeconds(retryDelay.toSeconds)
            logger.error(s"Failed to send notification due to client error. Setting availableAt to $availableAt")
            notificationRepo
              .setFailedButNotBlocked(notification.id, availableAt)
              .map(_.map(_ => SendService.ClientError))
          case SendConnector.ServerError =>
            logger.error("Failed to send notification due to server error.")
            blockedCsidRepo
              .blockCsid(notification)
              .map(_.map(_ => SendService.ServerError))
        }
    }
  }
}

object SendService {
  sealed trait Result

  case object Success extends Result

  case object ClientError extends Result

  case object ServerError extends Result
}
