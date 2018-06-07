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

package util

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import play.api.http.{HeaderNames, MimeTypes}
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest

import scala.collection.JavaConverters._

trait CustomsNotificationGatewayService extends WireMockRunner {

  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfig.PublicNotificationServiceContext)
  private val googleAnalyticsEndpointPath = urlMatching(ExternalServicesConfig.GoogleAnalyticsEndpointContext)

  def setupGoogleAnalyticsEndpoint(): Unit =
    stubFor(post(googleAnalyticsEndpointPath) willReturn aResponse().withStatus(ACCEPTED))


  def setupPublicNotificationServiceToReturn(status: Int = NO_CONTENT): Unit =
    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      willReturn aResponse()
      .withStatus(status))

  def verifyPublicNotificationServiceWasCalledWith(publicNotificationRequest: PublicNotificationRequest) {
    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson(Json.toJson(publicNotificationRequest.body).toString()))
    )
  }

  def verifyPublicNotificationServiceWasCalledWith(expectedPayload: JsValue) {
    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson(expectedPayload.toString()))
    )
  }

  private val gaRequestBuilder: RequestPatternBuilder = postRequestedFor(googleAnalyticsEndpointPath)
    .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
    .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))


  def aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId: String, googleAnalyticsClientId: String)(eventAction: String, eventLabel: String) :Boolean = {
    findAll(gaRequestBuilder).asScala.find(_.getBodyAsString ==
      s"""
         |v=1&
         |t=event&
         |tid=$googleAnalyticsTrackingId&
         |cid=$googleAnalyticsClientId&
         |ec=CDS&
         |ea=$eventAction&
         |el=[ConversationId=$eventLabel""".stripMargin).isDefined
  }

  def verifyNoOfGoogleAnalyticsCallsMadeWere(expectedNoOfCalls: Int): Unit =
    verify(expectedNoOfCalls, gaRequestBuilder)


}
