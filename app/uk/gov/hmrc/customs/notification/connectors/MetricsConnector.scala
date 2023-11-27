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

package uk.gov.hmrc.customs.notification.connectors

import com.kenshoo.play.metrics.Metrics
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.libs.json.Writes.StringWrites
import play.api.libs.json._
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.models._
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetricsConnector @Inject()(httpConnector: HttpConnector,
                                 config: AppConfig,
                                 graphiteMetrics: Metrics,
                                 dateTimeService: DateTimeService)(implicit ec: ExecutionContext) {
  def send(notification: Notification)(implicit hc: HeaderCarrier): Future[Unit] = {
    val updatedHc = {
      HeaderCarrier(
        requestId = hc.requestId,
        extraHeaders = List(
          CONTENT_TYPE -> MimeTypes.JSON,
          ACCEPT -> MimeTypes.JSON
        )
      )
    }

    val body = Json.obj(
      "conversationId" -> notification.conversationId,
      "eventStart" -> notification.metricsStartDateTime,
      "eventEnd" -> dateTimeService.now(),
      "eventType" -> "NOTIFICATION"
    )

    httpConnector.post(
      url = config.metricsUrl,
      body = body,
      hc = updatedHc,
      requestDescriptor = "metrics",
      shouldSendRequestToAuditing = false
    ).map(_.fold(_ => (), _ => ()))
  }

  def incrementRetryCounter(): Unit = {
    val counterName = config.retryMetricCounterName
    graphiteMetrics.defaultRegistry.counter(counterName).inc()
  }
}
