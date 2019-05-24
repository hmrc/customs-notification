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

package util

import com.github.tomakehurst.wiremock.client.WireMock._
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.http.Status.OK

trait CustomsNotificationMetricsService {
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfiguration.CustomsNotificationMetricsContext)

  def setupCustomsNotificationMetricsServiceToReturn(status: Int = OK): Unit =
    stubFor(post(urlMatchingRequestPath)
      .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
      willReturn aResponse()
      .withStatus(status))

  def verifyCustomsNotificationMetricsServiceWasCalled() {
    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON))
    )
  }

  def verifyCustomsNotificationMetricsServiceWasNotCalled() {
    verify(0, postRequestedFor(urlMatchingRequestPath)
    )
  }

}
