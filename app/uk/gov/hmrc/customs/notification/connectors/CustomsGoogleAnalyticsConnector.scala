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

package uk.gov.hmrc.customs.notification.connectors

import javax.inject.Singleton

import com.google.inject.Inject
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.domain.{PublicNotificationRequest, PublicNotificationRequestBody}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CustomsGoogleAnalyticsConnector @Inject()(http: HttpClient,
                                                logger: NotificationLogger,
                                                serviceConfigProvider: ServiceConfigProvider) {

  private val outboundHeaders = Seq(
    (ACCEPT, MimeTypes.JSON),
    (CONTENT_TYPE, MimeTypes.JSON))

  def send(eventAction: String, eventLabel: String): Future[Unit] = {
    ???
  }


}
