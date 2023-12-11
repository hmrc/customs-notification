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
import org.mongodb.scala.bson.ObjectId
import uk.gov.hmrc.customs.notification.config.RetryDelayConfig
import uk.gov.hmrc.customs.notification.connectors.{ClientDataConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableRequestMetadata
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.MongoDbError
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService.*
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.customs.notification.util.FutureEither.Implicits.*
import uk.gov.hmrc.http.HeaderCarrier

import java.time.ZonedDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class IncomingNotificationService @Inject()(repo: Repository,
                                            dateTimeService: DateTimeService,
                                            sendService: SendService,
                                            clientDataConnector: ClientDataConnector,
                                            auditService: AuditService,
                                            metricsConnector: MetricsConnector,
                                            newObjectIdService: ObjectIdService,
                                            retryDelayConfig: RetryDelayConfig)
                                           (implicit ec: ExecutionContext) extends Logger {
  def process(payload: NodeSeq,
              requestMetadata: RequestMetadata)
             (implicit hc: HeaderCarrier,
              lc: LogContext): Future[Either[Error, Unit]] = {

    clientDataConnector.get(requestMetadata.csid).flatMap {
      case Left(ClientDataConnector.DeclarantNotFound) =>
        Future.successful(Left(DeclarantNotFound))
      case Left(ClientDataConnector.OtherError) =>
        Future.successful(Left(InternalServiceError))
      case Right(ClientDataConnector.Success(clientData)) =>
        processNotificationFor(clientData, payload, requestMetadata)
    }
  }

  private def processNotificationFor(clientData: ClientData,
                                     payload: NodeSeq,
                                     requestMetadata: RequestMetadata)
                                    (implicit hc: HeaderCarrier,
                                     lc: LogContext): Future[Either[Error, Unit]] = {
    implicit val ac: AuditContext = AuditContext(requestMetadata)

    val newNotification = notificationFrom(newObjectIdService.newId(), payload, clientData.clientId, requestMetadata)
    auditService.sendIncomingNotificationEvent(clientData.sendData, newNotification.payload)

    (for {
      failedAndBlockedExist <- repo.checkFailedAndBlockedExist(requestMetadata.csid).toFutureEither
      whatToDo = if (failedAndBlockedExist) BlockAndAbort else ContinueToSend
      _ = logger.error(s"TIME NOW IS: ${dateTimeService.now()}")
      _ <- repo.insert(newNotification, whatToDo.statusToSave, whatToDo.availableAt, whatToDo.failureCount).toFutureEither
      _ = metricsConnector.send(newNotification)
    } yield whatToDo).value flatMap {
      case Left(_: MongoDbError) =>
        Future.successful(Left(InternalServiceError))
      case Right(BlockAndAbort) =>
        logger.info(s"Existing FailedAndBlocked notifications found, incoming notification saved as FailedAndBlocked")
        Future.successful(Right(()))
      case Right(ContinueToSend) =>
        logger.info("Saved notification")

        sendService.send(newNotification, clientData.sendData)
          .map(_.leftMap(_ => InternalServiceError))
    }
  }

  private def notificationFrom(newId: ObjectId, payload: NodeSeq, clientId: ClientId, requestMetadata: RequestMetadata): Notification = {
    val headers =
      (requestMetadata.maybeBadgeId ++
        requestMetadata.maybeSubmitterId ++
        requestMetadata.maybeCorrelationId ++
        requestMetadata.maybeIssueDateTime).toList

    Notification(
      newId,
      requestMetadata.csid,
      clientId,
      requestMetadata.notificationId,
      requestMetadata.conversationId,
      headers,
      Payload.from(payload),
      requestMetadata.startTime
    )
  }

  private sealed trait WhatToDo {
    val failureCount: Int
    val statusToSave: ProcessingStatus

    def availableAt: ZonedDateTime

  }

  private case object ContinueToSend extends WhatToDo {
    val failureCount: Int = 0
    val statusToSave: ProcessingStatus = ProcessingStatus.SavedToBeSent

    def availableAt: ZonedDateTime = dateTimeService.now()
  }

  private case object BlockAndAbort extends WhatToDo {
    val failureCount: Int = 1
    val statusToSave: ProcessingStatus = ProcessingStatus.FailedAndBlocked

    def availableAt: ZonedDateTime = dateTimeService.now().plusSeconds(retryDelayConfig.failedAndBlocked.toSeconds)
  }
}

object IncomingNotificationService {

  sealed trait Error

  case object InternalServiceError extends Error

  case object DeclarantNotFound extends Error

}