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

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors._
import uk.gov.hmrc.customs.notification.domain.{ClientId, HttpResultError, PushNotificationRequest, ResultError}
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HttpResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class OutboundSwitchService @Inject()(configService: ConfigService,
                                      externalPush: ExternalPushConnector,
                                      internalPush: InternalPushConnector,
                                      auditingService: AuditingService,
                                      logger: CdsLogger
                                     ) {

  def send(clientId: ClientId, pnr: PushNotificationRequest): Future[Either[ResultError, HttpResponse]] = {

    val response: (String, Future[Either[ResultError, HttpResponse]]) =
      if (configService.pushNotificationConfig.internalClientIds.contains(clientId.toString)) {
        infoLog(s"About to push internally for clientId=$clientId", pnr)
        ("internal", internalPushWithAuditing(pnr))
      } else {
        infoLog(s"About to push externally for clientId=$clientId", pnr)
        ("external", externalPush.send(pnr))
      }

    response._2.map {
      case r@Right(_) =>
        infoLog(s"${response._1} push notification call succeeded", pnr)
        r
      case l@Left(resultError: ResultError) =>
        errorLog(s"Call to ${response._1} push notification service failed. POST url=${pnr.body.url}", pnr, resultError.cause)
        l
    }
  }

  private def internalPushWithAuditing(pnr: PushNotificationRequest): Future[Either[ResultError, HttpResponse]] = {

    val eventuallyEither: Future[Either[ResultError, HttpResponse]] = internalPush.send(pnr).map{
      case r@Right(_) =>
        auditingService.auditSuccessfulNotification(pnr)
        r
      case l@Left(httpError: HttpResultError) =>
        auditingService.auditFailedNotification(pnr, Some(s"status: ${httpError.status} body: ${httpError.cause.getMessage}"))
        l
      case l@Left(_) =>
        l
    }

    eventuallyEither
  }


  // TODO: replace with call to NotificationLogger info, once logging framework has been refactored
  private def infoLog(msg: String, pnr: PushNotificationRequest): Unit = {
    logger.info(formatLogMsg(msg, pnr))
  }
  private def errorLog(msg: String, pnr: PushNotificationRequest, e: Throwable): Unit = {
    logger.error(formatLogMsg(msg, pnr), e)
  }

  private def formatLogMsg(msg: String, pnr: PushNotificationRequest) = {
    s"[conversationId=${pnr.body.conversationId}] $msg"
  }
}
