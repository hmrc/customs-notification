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

case class NotificationQueueConfig(url: String)

case class GoogleAnalyticsSenderConfig(url: String, gaTrackingId: String, gaClientId: String, gaEventValue: String)

// TODO: pull up all other service config into here
trait CustomsNotificationConfig {
  def maybeBasicAuthToken: Option[String]

  def notificationQueueConfig: NotificationQueueConfig

  def googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig
}
