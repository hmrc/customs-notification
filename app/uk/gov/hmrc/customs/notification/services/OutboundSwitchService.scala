/*
 * Copyright 2024 HM Revenue & Customs
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

import uk.gov.hmrc.customs.notification.connectors._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OutboundSwitchService @Inject()(configService: ConfigService,
                                      externalPush: ExternalPushConnector,
                                      internalPush: InternalPushConnector,
                                      auditingService: AuditingService,
                                      logger: NotificationLogger
                                     )
                                     (implicit ec: ExecutionContext) {

  def send(clientId: ClientId, pnr: PushNotificationRequest)(implicit rm: HasId, hc: HeaderCarrier): Future[Either[ResultError, HttpResponse]] = {

    val response: (String, Future[Either[ResultError, HttpResponse]]) =
      if (configService.notificationConfig.internalClientIds.contains(clientId.toString)) {
        logger.info(s"${ pnr } About to push internally")
        ("internal", internalPushWithAuditing(pnr))
      } else {
        logger.info(s"${ pnr } About to push externally")
        ("external", externalPush.send(pnr))
      }

    /** caller handles logging */
    response._2.map {
      case r@Right(_) =>
        r
      case l@Left(resultError: ResultError) =>
        l
    }
  }

  private def internalPushWithAuditing(pnr: PushNotificationRequest)(implicit rm: HasId, hc: HeaderCarrier): Future[Either[ResultError, HttpResponse]] = {

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

}
