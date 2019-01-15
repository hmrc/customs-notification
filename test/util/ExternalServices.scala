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

import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, DeclarantCallbackData, Header, PushNotificationRequest}
import util.TestData._
import java.util

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import play.api.http.Status.OK

trait PushNotificationService extends WireMockRunner {
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfiguration.PushNotificationServiceContext)

  def setupPushNotificationServiceToReturn(status: Int = NO_CONTENT): Unit =
    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      willReturn aResponse()
      .withStatus(status))

  def verifyPushNotificationServiceWasCalledWith(pushNotificationRequest: PushNotificationRequest) {
    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson(Json.toJson(pushNotificationRequest.body).toString()))
    )
  }

  def actualCallsMadeToClientsPushService(): util.List[LoggedRequest] = this.wireMockServer.findAll(postRequestedFor(urlMatchingRequestPath))

  def verifyPushNotificationServiceWasCalledWith(expectedPayload: JsValue) {
    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson(expectedPayload.toString()))
    )

  }

}

trait ApiSubscriptionFieldsService extends WireMockRunner {

  def apiSubscriptionFieldsUrl(fieldsId: String): String =
    s"${ExternalServicesConfiguration.ApiSubscriptionFieldsServiceContext}/$fieldsId"

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

  def startApiSubscriptionFieldsService(fieldsId: String, testCallbackData: DeclarantCallbackData = callbackData): Unit =
    setupApiSubscriptionFieldsServiceToReturn(Status.OK, fieldsId, testCallbackData)


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

trait EmailService extends WireMockRunner with Eventually {

  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfiguration.EmailServiceContext)

  def setupEmailServiceToReturn(status: Int): Unit = {
    stubFor(post(urlMatchingRequestPath).willReturn(aResponse().withStatus(status)))
  }

  def verifyEmailServiceWasCalled(): Unit = {
    eventually(verify(1, postRequestedFor(urlMatchingRequestPath)))
  }
}


trait NotificationQueueService extends WireMockRunner {
  self: Matchers =>
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfiguration.NotificationQueueContext)

  def getBadgeIdHeader(request: PushNotificationRequest): Option[String] =
    extractBadgeIdHeaderValue(request.body.outboundCallHeaders)


  private def extractHeader(headers: Seq[Header], name: String) = {
    headers.find(_.name.toLowerCase == name.toLowerCase).map(_.value)
  }

  private def extractBadgeIdHeaderValue(headers: Seq[Header]) =
    extractHeader(headers, X_BADGE_ID_HEADER_NAME)


  private def extractCorrelationIdHeaderValue(headers: Seq[Header]) =
    extractHeader(headers, X_CORRELATION_ID_HEADER_NAME)


  def runNotificationQueueService(status: Int = CREATED): Unit = {
    stubFor(post(urlMatchingRequestPath)
      willReturn aResponse()
      .withStatus(status))
  }

  def actualCallsMadeToPullQ(): util.List[LoggedRequest] = wireMockServer.findAll(postRequestedFor(urlMatchingRequestPath))

  def setupNotificationQueueServiceToReturn(status: Int,
                                            request: PushNotificationRequest,
                                            fieldsId: String = validFieldsId): Unit = {

    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(request.body.authHeaderToken))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(request.body.conversationId))
      .withHeader(X_BADGE_ID_HEADER_NAME, equalTo(getBadgeIdHeader(request).get))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(fieldsId))
      willReturn aResponse()
      .withStatus(status))
  }

  def setupPullQueueServiceToReturn(status: Int,
                                    request: ClientNotification): Unit = {

    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(request.notification.conversationId.toString()))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(request.csid.toString()))
      willReturn aResponse()
      .withStatus(status))
  }

  def setupNotificationQueueServiceToReturnNoBadgeId(status: Int,
                                                     request: PushNotificationRequest,
                                                     fieldsId: String = validFieldsId): Unit = {

    stubFor(post(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(request.body.authHeaderToken))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(request.body.conversationId))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(fieldsId))
      willReturn aResponse()
      .withStatus(status))
  }

  def verifyNotificationQueueServiceWasCalledWith(request: PushNotificationRequest,
                                                  fieldsId: String = validFieldsId): Unit = {

    val allRequestsMade = wireMockServer.findAll(postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
//      .withHeader(HeaderNames.AUTHORIZATION, equalTo(request.body.authHeaderToken))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(request.body.conversationId))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(fieldsId))
      .withRequestBody(equalToXml(request.body.xmlPayload))
    )

    assert(allRequestsMade.size() == 1)

    getBadgeIdHeader(request) match {
      case Some(expectedBadgeIdHeaderValue) => allRequestsMade.get(0).getHeader(X_BADGE_ID_HEADER_NAME) shouldBe expectedBadgeIdHeaderValue
      case None => allRequestsMade.get(0).containsHeader(X_BADGE_ID_HEADER_NAME) shouldBe false
    }
  }

  def verifyPullQueueServiceWasCalledWith(request: ClientNotification): Unit = {

    val allRequestsMade = wireMockServer.findAll(postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(request.notification.conversationId.id.toString))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(request.csid.id.toString))
      .withRequestBody(equalToXml(request.notification.payload))
    )

    assert(allRequestsMade.size() == 1)

    extractBadgeIdHeaderValue(request.notification.headers) match {
      case Some(expectedBadgeIdHeaderValue) => allRequestsMade.get(0).getHeader(X_BADGE_ID_HEADER_NAME) shouldBe expectedBadgeIdHeaderValue
      case None => allRequestsMade.get(0).containsHeader(X_BADGE_ID_HEADER_NAME) shouldBe false
    }

    extractCorrelationIdHeaderValue(request.notification.headers) match {
      case Some(expectedBadgeIdHeaderValue) => allRequestsMade.get(0).getHeader(X_CORRELATION_ID_HEADER_NAME) shouldBe expectedBadgeIdHeaderValue
      case None => allRequestsMade.get(0).containsHeader(X_CORRELATION_ID_HEADER_NAME) shouldBe false
    }
  }

  def verifyNotificationQueueServiceWasNotCalled(): Unit =
    verify(0, postRequestedFor(urlMatchingRequestPath))

}


trait AuditService extends WireMockRunner {
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfiguration.AuditContext)

  def setupAuditServiceToReturn(status: Int = OK): Unit =
    stubFor(post(urlMatchingRequestPath)
      willReturn aResponse()
      .withStatus(status))

  def verifyAuditServiceWasNotCalled() {
    verify(0, postRequestedFor(urlMatchingRequestPath))
  }

}

object ExternalServicesConfiguration {
  val Port: Int = sys.env.getOrElse("WIREMOCK_SERVICE_PORT", "11111").toInt
  val Host = "localhost"
  val PushNotificationServiceContext = "/notify-customs-declarant"
  val GoogleAnalyticsEndpointContext = "/google-analytics"
  val ApiSubscriptionFieldsServiceContext = "/api-subscription-fields"
  val NotificationQueueContext = "/queue"
  val EmailServiceContext = "/hmrc/email"
  val CustomsNotificationMetricsContext = "/log-times"
  val AuditContext = "/write/audit.*"
}
