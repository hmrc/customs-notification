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
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.MongoDbError
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.customs.notification.util.FutureEither.Implicits.FutureEitherExtensions
import uk.gov.hmrc.http.HeaderCarrier

import java.time.ZonedDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SendService @Inject()(sendConnector: SendConnector,
                            repo: Repository,
                            retryDelayConfig: RetryDelayConfig,
                            dateTimeService: DateTimeService)
                           (implicit ec: ExecutionContext) extends Logger {
  def send(notification: Notification,
           clientSendData: SendData)
          (implicit hc: HeaderCarrier,
           lc: LogContext,
           ac: AuditContext): Future[Either[MongoDbError, Unit]] = {
    sendConnector.send(notification, clientSendData).flatMap {
      case Right(SuccessfullySent(requestDescriptor)) =>
        repo.setSucceeded(notification.id)
        logger.info(s"${requestDescriptor.value.capitalize} succeeded")
        Future.successful(Right(()))
      case Left(error: SendError) =>
        val whatToDo = error match {
          case ClientError => DoWhenClientError
          case ServerError => DoWhenServerError
        }
        logger.error(whatToDo.errorMessage)
        whatToDo.doWith(notification)
    }
  }

  private sealed trait WhatToDo {
    def retryDelay: FiniteDuration

    final def availableAt(): ZonedDateTime = dateTimeService.now().plusSeconds(retryDelay.toSeconds)

    def errorMessage: String

    def doWith(notification: Notification): Future[Either[MongoDbError, Unit]]
  }

  private case object DoWhenClientError extends WhatToDo {
    val retryDelay: FiniteDuration = retryDelayConfig.failedButNotBlocked
    val errorMessage: String = s"Failed to send notification due to client error. Setting availableAt to ${availableAt()}"

    def doWith(notification: Notification): Future[Either[MongoDbError, Unit]] = {
      repo.setFailedButNotBlocked(
        notification.id,
        availableAt()
      )(LogContext(notification))
    }
  }

  private case object DoWhenServerError extends WhatToDo {
    val retryDelay: FiniteDuration = retryDelayConfig.failedAndBlocked
    val errorMessage: String =
      s"Failed to send notification due to client error." +
        " Blocking outstanding notifications for client subscription ID" +
        s" and setting availableAt to ${availableAt()}"

    def doWith(notification: Notification): Future[Either[MongoDbError, Unit]] = {
      implicit val lc: LogContext = LogContext(notification)
      (for {
        _ <- repo.setFailedAndBlocked(notification.id, availableAt()).toFutureEither
        _ <- repo.blockAllFailedButNotBlocked(notification.csid).toFutureEither
      } yield ()).value
    }
  }
}
