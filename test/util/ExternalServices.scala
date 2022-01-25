/*
 * Copyright 2022 HM Revenue & Customs
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

import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, DeclarantCallbackData, Header, PushNotificationRequest}
import util.TestData._

import java.util
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.scalatest.matchers.should.Matchers

trait PushNotificationService extends WireMockRunner with Matchers {
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
      .withHeader(NOTIFICATION_ID_HEADER_NAME, equalTo(notificationId.toString))
      .withoutHeader(ISSUE_DATE_TIME_HEADER)
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

  def verifyPushNotificationServiceWasNotCalled() {
    verify(0, postRequestedFor(urlMatchingRequestPath))
  }

}

trait InternalPushNotificationService {
  private val urlMatchingRequestPath = urlMatching(ExternalServicesConfiguration.InternalPushServiceContext)

  def startInternalService(): Unit = {
    setupInternalServiceToReturn(ACCEPTED, urlMatchingRequestPath)
  }

  def setupInternalServiceToReturn(status: Int = ACCEPTED, urlPattern: UrlPattern = urlMatchingRequestPath): Unit =
    stubFor(post(urlPattern).
      willReturn(
        aResponse()
          .withStatus(status)))

  def verifyInternalServiceWasCalledWith(pnr: PushNotificationRequest) {

    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(CONTENT_TYPE, equalTo(XML))
      .withHeader(ACCEPT, equalTo(XML))
      .withHeader(AUTHORIZATION, equalTo(pnr.body.authHeaderToken))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(pnr.body.conversationId))
      .withHeader(USER_AGENT, equalTo("customs-notification"))
      .withRequestBody(equalToXml(pnr.body.xmlPayload))
    )

  }

  def verifyInternalServiceWasCalledWithOutboundHeaders(pnr: PushNotificationRequest) {

    verify(1, postRequestedFor(urlMatchingRequestPath)
      .withHeader(CONTENT_TYPE, equalTo(XML))
      .withHeader(ACCEPT, equalTo(XML))
      .withHeader(AUTHORIZATION, equalTo(pnr.body.authHeaderToken))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(conversationId.toString))
      .withHeader(USER_AGENT, equalTo("customs-notification"))
      .withHeader(X_CORRELATION_ID_HEADER_NAME, equalTo(correlationId))
      .withHeader(X_SUBMITTER_ID_HEADER_NAME, equalTo(submitterNumber))
      .withHeader(X_BADGE_ID_HEADER_NAME, equalTo(badgeId))
      .withHeader(ISSUE_DATE_TIME_HEADER, equalTo(issueDateTime))
      .withRequestBody(equalToXml(pnr.body.xmlPayload))
    )

  }

  def verifyInternalServiceWasNotCalledWith(pnr: PushNotificationRequest) {
    verify(0, postRequestedFor(urlMatchingRequestPath))
  }

}

trait ApiSubscriptionFieldsService {

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
      .withHeader(HeaderNames.AUTHORIZATION, absent())
    )
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

  def verifyPullQueueServiceWasCalledWith(request: ClientNotification): Unit = {

    val allRequestsMade = wireMockServer.findAll(postRequestedFor(urlMatchingRequestPath)
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader(HeaderNames.USER_AGENT, equalTo(userAgent))
      .withHeader(X_CONVERSATION_ID_HEADER_NAME, equalTo(request.notification.conversationId.id.toString))
      .withHeader(SUBSCRIPTION_FIELDS_ID_HEADER_NAME, equalTo(request.csid.id.toString))
      .withHeader(HeaderNames.AUTHORIZATION, absent())
      .withoutHeader(ISSUE_DATE_TIME_HEADER)
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

    request.notification.notificationId match {
      case Some(notificationId) => allRequestsMade.get(0).getHeader(NOTIFICATION_ID_HEADER_NAME) shouldBe notificationId.toString
      case None => allRequestsMade.get(0).getAllHeaderKeys.contains(NOTIFICATION_ID_HEADER_NAME) shouldBe false
    }
  }

  def verifyNotificationQueueServiceWasNotCalled(): Unit =
    verify(0, postRequestedFor(urlMatchingRequestPath))

}

object ExternalServicesConfiguration {
  val Port: Int = sys.env.getOrElse("WIREMOCK_SERVICE_PORT", "11111").toInt
  val Host = "localhost"
  val PushNotificationServiceContext = "/notify-customs-declarant"
  val ApiSubscriptionFieldsServiceContext = "/api-subscription-fields"
  val NotificationQueueContext = "/queue"
  val CustomsNotificationMetricsContext = "/log-times"
  val InternalPushServiceContext = "/internal/notify"
}
