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
import org.scalatest.Matchers
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, PublicNotificationRequest}
import util.TestData._

trait PublicNotificationService extends WireMockRunner {
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfig.PublicNotificationServiceContext)

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

}

trait ApiSubscriptionFieldsService extends WireMockRunner {

  def apiSubscriptionFieldsUrl(fieldsId: String): String =
    s"${ExternalServicesConfig.ApiSubscriptionFieldsServiceContext}/$fieldsId"

  private def urlMatchingRequestPath(fieldsId: String) = {
    urlEqualTo(apiSubscriptionFieldsUrl(fieldsId))
  }

  private def responseString(fieldsId: String, fields: DeclarantCallbackData) =
    s"""
       |{
       |  "clientId" : "aThirdPartyApplicationId",
       |  "apiContext" : "customs/declarations",
       |  "apiVersion" : "1.0",
       |  "fieldsId" : "$fieldsId",
       |  "fields": {
       |    "callbackUrl": "${fields.callbackUrl}",
       |    "securityToken": "${fields.securityToken}"
       |  }
       |}
       |""".stripMargin

//  def startApiSubscriptionFieldsService(fieldsId: String): Unit = setupApiSubscriptionFieldsServiceToReturn(Status.OK, fieldsId, callbackData)

  def startApiSubscriptionFieldsService(fieldsId: String, testCallbackData : DeclarantCallbackData = callbackData): Unit = setupApiSubscriptionFieldsServiceToReturn(Status.OK, fieldsId, testCallbackData)


  def setupApiSubscriptionFieldsServiceToReturn(status: Int, fieldsId: String, fields: DeclarantCallbackData): Unit =
    stubFor(
      get(urlMatchingRequestPath(fieldsId)).
        willReturn(
          aResponse().withBody(responseString(fieldsId, fields))
            .withStatus(status)
        )
    )

  def setupApiSubscriptionFieldsServiceToReturn(status: Int, fieldsId: String): Unit =
    stubFor(
      get(urlMatchingRequestPath(fieldsId)).
        willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  def setupApiSubscriptionFieldsServiceToReturn(status: Int, fieldsId: String, responseBody: String): Unit =
    stubFor(
      get(urlMatchingRequestPath(fieldsId)).
        willReturn(
          aResponse().withBody(responseBody)
            .withStatus(status)
        )
    )

  def verifyApiSubscriptionFieldsServiceWasCalled(fieldsId: String) {
    verify(1, getRequestedFor(urlMatchingRequestPath(fieldsId))
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
    )
  }

}

trait NotificationQueueService extends WireMockRunner {
  self: Matchers =>
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfig.NotificationQueueContext)

  def getBadgeIdHeader(request: PublicNotificationRequest): Option[String] = {
    val mayBeBadgeId = Map(request.body.outboundCallHeaders.map(x => x.name -> x.value): _*).get(X_BADGE_ID_HEADER_NAME)
    mayBeBadgeId
  }

  def setupNotificationQueueServiceToReturn(status: Int,
                                            request: PublicNotificationRequest,
                                            fieldsId: String = validFieldsId): Unit = {

    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(request.body.authHeaderToken))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME, equalTo(request.body.conversationId))
      .withHeader(CustomHeaderNames.X_BADGE_ID_HEADER_NAME, equalTo(getBadgeIdHeader(request).get))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(fieldsId))
      willReturn aResponse()
      .withStatus(status))
  }

  def setupNotificationQueueServiceToReturnNoBadgeId(status: Int,
                                                     request: PublicNotificationRequest,
                                                     fieldsId: String = validFieldsId): Unit = {

    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(request.body.authHeaderToken))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME, equalTo(request.body.conversationId))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(fieldsId))
      willReturn aResponse()
      .withStatus(status))
  }

  def verifyNotificationQueueServiceWasCalledWith(request: PublicNotificationRequest,
                                                  fieldsId: String = validFieldsId): Unit = {

    val allRequestsMade = wireMockServer.findAll(postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(request.body.authHeaderToken))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(CustomHeaderNames.X_CONVERSATION_ID_HEADER_NAME, equalTo(request.body.conversationId))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(fieldsId))
      .withRequestBody(equalToXml(request.body.xmlPayload))
    )

    assert(allRequestsMade.size() == 1)

    getBadgeIdHeader(request) match {
      case Some(expectedBadgeIdHeaderValue) => allRequestsMade.get(0).getHeader(CustomHeaderNames.X_BADGE_ID_HEADER_NAME) shouldBe expectedBadgeIdHeaderValue
      case None => allRequestsMade.get(0).containsHeader(CustomHeaderNames.X_BADGE_ID_HEADER_NAME) shouldBe false
    }
  }

  def verifyNotificationQueueServiceWasNotCalled(): Unit =
    verify(0, postRequestedFor(urlMatchingRequestPath))

}

object ExternalServicesConfig {
  val Port: Int = sys.env.getOrElse("WIREMOCK_SERVICE_PORT", "11111").toInt
  val Host = "localhost"
  val PublicNotificationServiceContext = "/make-post-call"
  val GoogleAnalyticsEndpointContext = "/google-analytics"
  val ApiSubscriptionFieldsServiceContext = "/api-subscription-fields"
  val NotificationQueueContext = "/queue"
}
