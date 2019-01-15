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
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import play.api.http.{HeaderNames, MimeTypes}
import play.api.libs.json.Json.parse
import play.api.test.Helpers._

import scala.collection.JavaConverters._

trait GoogleAnalyticsSenderService extends WireMockRunner {

  private val googleAnalyticsEndpointPath = urlMatching(ExternalServicesConfiguration.GoogleAnalyticsEndpointContext)

  def setupGoogleAnalyticsEndpoint(statusToReturn: Int = ACCEPTED): Unit =
    stubFor(post(googleAnalyticsEndpointPath) willReturn aResponse().withStatus(statusToReturn))


  private val gaRequestBuilder: RequestPatternBuilder = postRequestedFor(googleAnalyticsEndpointPath)
    .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
    .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))


  def aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId: String, googleAnalyticsClientId: String, eventValue: String)(eventAction: String, eventLabel: String): Boolean = {
    findAll(gaRequestBuilder).asScala.find(x => (
      parse(x.getBodyAsString) == parse(
        s""" {"payload" : "v=1&t=event&tid=$googleAnalyticsTrackingId&cid=$googleAnalyticsClientId&ec=CDS&ea=$eventAction&el=$eventLabel&ev=$eventValue"} """
      ))).isDefined
  }

  def verifyNoOfGoogleAnalyticsCallsMadeWere(expectedNoOfCalls: Int): Unit =
    verify(expectedNoOfCalls, gaRequestBuilder)
}
