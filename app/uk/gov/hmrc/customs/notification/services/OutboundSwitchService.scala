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
import uk.gov.hmrc.customs.notification.connectors.{ExternalPushConnector, InternalPushConnector}
import uk.gov.hmrc.customs.notification.domain.{ClientId, PushNotificationRequest}
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HttpException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class OutboundSwitchService @Inject()(configService: ConfigService,
                                      externalPush: ExternalPushConnector,
                                      internalPush: InternalPushConnector,
                                      auditingService: AuditingService,
                                      logger: CdsLogger
                                     ) {

  def send(clientId: ClientId, pnr: PushNotificationRequest): Future[Unit] = {

    if (configService.pushNotificationConfig.internalClientIds.contains(clientId.toString)) {
      infoLog(s"About to push internally for clientId=$clientId", pnr)
      internalPush.send(pnr).map(_ => auditingService.auditSuccessfulNotification(pnr))
        .recoverWith {
          case rte: RuntimeException =>
            rte.getCause match {
              case httpError: HttpException =>
                // Only if an actual call was made we audit
                auditingService.auditFailedNotification(pnr, Some(s"status: ${httpError.responseCode} body: ${httpError.message}"))
                Future.failed(rte)
              case _ =>
                Future.failed(rte)
            }

        }
    } else {
      infoLog(s"About to push externally for clientId=$clientId", pnr)
      externalPush.send(pnr)
    }
  }

  // TODO: replace with call to NotificationLogger info, once logging framework has been refactored
  private def infoLog(msg: String, pnr: PushNotificationRequest) = {
    logger.info(s"[conversationId=${pnr.body.conversationId}] $msg")
  }

}
