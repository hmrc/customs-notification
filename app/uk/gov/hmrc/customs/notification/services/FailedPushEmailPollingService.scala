/*
 * Copyright 2018 HM Revenue & Customs
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

import akka.actor.ActorSystem
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import uk.gov.hmrc.customs.notification.connectors.EmailConnector
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, Email, SendEmailRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Singleton
class FailedPushEmailPollingService @Inject()(clientNotificationRepo: ClientNotificationRepo,
                                              emailConnector: EmailConnector,
                                              actorSystem: ActorSystem,
                                              configService: CustomsNotificationConfig,
                                              logger: NotificationLogger)(implicit executionContext: ExecutionContext){

  private val emailAddresses: List[Email] = configService.pullExcludeConfig.emailAddresses.map { address => Email(address) }.toList
  private val pullExcludeEnabled = configService.pullExcludeConfig.pullExcludeEnabled
  private val templateId = "customs_push_notifications_warning"
  private val delay = configService.pullExcludeConfig.pollingDelay
  private implicit val hc = HeaderCarrier()

  if (pullExcludeEnabled) {
    actorSystem.scheduler.schedule(0.seconds, delay) {

      logger.debug(s"running push notifications warning email scheduler with delay of $delay")
      clientNotificationRepo.failedPushNotificationsExist().map {
        case true =>
          val now = DateTime.now(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime())
          val sendEmailRequest = SendEmailRequest(emailAddresses, templateId, Map("timestamp" -> now), force = false)
          logger.debug(s"sending push notifications warning email with timestamp $now")
          emailConnector.send(sendEmailRequest)
        case false =>
          logger.info(s"No push notifications warning email sent")
      }
    }
  }
}
