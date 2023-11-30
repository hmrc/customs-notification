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

import org.mongodb.scala.bson.ObjectId
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableRequestMetadata
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits._
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.MongoDbError
import uk.gov.hmrc.customs.notification.services.IncomingNotificationService._
import uk.gov.hmrc.customs.notification.services.SendService.SendError
import uk.gov.hmrc.customs.notification.util.FutureEither.Implicits._
import uk.gov.hmrc.customs.notification.util._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class IncomingNotificationService @Inject()(repo: Repository,
                                            sendService: SendService,
                                            apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                                            auditService: AuditService,
                                            metricsConnector: MetricsConnector,
                                            logger: Logger,
                                            newObjectIdService: ObjectIdService)(implicit ec: ExecutionContext) {
  def process(payload: NodeSeq)(implicit requestMetadata: RequestMetadata, hc: HeaderCarrier): Future[Either[Error, Unit]] = {

    apiSubscriptionFieldsConnector.get(requestMetadata.clientSubscriptionId).flatMap {
      case Left(ApiSubscriptionFieldsConnector.DeclarantNotFound) =>
        Future.successful(Left(DeclarantNotFound))
      case Left(ApiSubscriptionFieldsConnector.OtherError) =>
        Future.successful(Left(InternalServiceError))
      case Right(ApiSubscriptionFieldsConnector.Success(apiSubscriptionFields)) =>
        processNotificationFor(apiSubscriptionFields, payload)
    }
  }

  private def processNotificationFor(apiSubscriptionFields: ApiSubscriptionFields,
                                     payload: NodeSeq)(implicit requestMetadata: RequestMetadata, hc: HeaderCarrier): Future[Either[Error, Unit]] = {
    auditService.sendIncomingNotificationEvent(apiSubscriptionFields.fields, payload.toString, requestMetadata)

    val newNotification = notificationFrom(newObjectIdService.newId(), payload, apiSubscriptionFields.clientId, requestMetadata)
    (for {
      failedAndBlockedExist <- repo.checkFailedAndBlockedExist(requestMetadata.clientSubscriptionId).toFutureEither
      whatToDo = if (failedAndBlockedExist) BlockAndAbort else ContinueToSend
      _ <- repo.insert(newNotification, whatToDo.stateToSave).toFutureEither
      _ = metricsConnector.send(newNotification)
    } yield whatToDo).value flatMap {
      case Left(_: MongoDbError) =>
        Future.successful(Left(InternalServiceError))
      case Right(BlockAndAbort) =>
        logger.info(s"Existing FailedAndBlocked notifications found, incoming notification saved as FailedAndBlocked", newNotification)
        Future.successful(Right(()))
      case Right(ContinueToSend) =>
        logger.info("Saved notification", newNotification)

        sendService.send(newNotification, requestMetadata, apiSubscriptionFields.fields).flatMap {
          case Left(SendError) =>
            Future.successful(Left(InternalServiceError))
          case Right(_) =>
            Future.successful(Right(()))
        }
    }
  }

  private def notificationFrom(newId: ObjectId, xml: NodeSeq, clientId: ClientId, requestMetadata: RequestMetadata): Notification = {
    val headers =
      (requestMetadata.maybeBadgeId ++
        requestMetadata.maybeSubmitterId ++
        requestMetadata.maybeCorrelationId ++
        requestMetadata.maybeIssueDateTime).toList

    Notification(
      newId,
      requestMetadata.clientSubscriptionId,
      clientId,
      requestMetadata.notificationId,
      requestMetadata.conversationId,
      headers,
      xml.toString(),
      requestMetadata.startTime
    )
  }
}

object IncomingNotificationService {

  private sealed trait WhatToDo {
    val stateToSave: ProcessingStatus
  }

  private case object ContinueToSend extends WhatToDo {
    val stateToSave: ProcessingStatus = ProcessingStatus.SavedToBeSent
  }

  private case object BlockAndAbort extends WhatToDo {
    val stateToSave: ProcessingStatus = ProcessingStatus.FailedAndBlocked
  }

  sealed trait Error

  case object InternalServiceError extends Error

  case object DeclarantNotFound extends Error

}