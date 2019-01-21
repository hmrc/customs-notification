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

import akka.actor.ActorSystem
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import uk.gov.hmrc.customs.notification.connectors.EmailConnector
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, Email, SendEmailRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext

/* TODO: we may need a similar poller for new Retry functionality */
@Singleton
class FailedPushEmailPollingService @Inject()(clientNotificationRepo: ClientNotificationRepo,
                                              emailConnector: EmailConnector,
                                              actorSystem: ActorSystem,
                                              configService: CustomsNotificationConfig,
                                              logger: NotificationLogger)(implicit executionContext: ExecutionContext){

  private val emailAddress: List[Email] = List(Email(configService.pullExcludeConfig.emailAddress))
  private val pullExcludeEnabled = configService.pullExcludeConfig.pullExcludeEnabled
  private val templateId = "customs_push_notifications_warning"
  private val interval = configService.pullExcludeConfig.pollingInterval
  private val delay = configService.pullExcludeConfig.pollingDelay
  private implicit val hc = HeaderCarrier()

  if (pullExcludeEnabled) {
    actorSystem.scheduler.schedule(delay, interval) {

      logger.debug(s"running push notifications warning email scheduler with an initial delay of $delay and an interval of $interval")
      clientNotificationRepo.failedPushNotificationsExist().map {
        case true =>
          val now = DateTime.now(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime())
          val sendEmailRequest = SendEmailRequest(emailAddress, templateId, Map("timestamp" -> now), force = false)
          logger.debug(s"sending push notifications warning email with email address ${emailAddress.head.value} and timestamp $now")
          emailConnector.send(sendEmailRequest)
        case false =>
          logger.info(s"No push notifications warning email sent")
      }
    }
  }
}
