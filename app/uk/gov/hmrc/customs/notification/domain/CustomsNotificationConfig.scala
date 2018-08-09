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

package uk.gov.hmrc.customs.notification.domain

import com.google.inject.ImplementedBy
import uk.gov.hmrc.customs.notification.services.config.ConfigService

import scala.concurrent.duration.FiniteDuration

case class NotificationQueueConfig(url: String)

case class GoogleAnalyticsSenderConfig(url: String, gaTrackingId: String, gaClientId: String, gaEventValue: String, gaEnabled: Boolean)

case class PushNotificationConfig(pollingDelay: FiniteDuration, lockDuration: org.joda.time.Duration, maxRecordsToFetch: Int)

// TODO: pull up all other service config into here
@ImplementedBy(classOf[ConfigService])
trait CustomsNotificationConfig {
  def maybeBasicAuthToken: Option[String]

  def notificationQueueConfig: NotificationQueueConfig

  def googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig

  def pushNotificationConfig: PushNotificationConfig
}
