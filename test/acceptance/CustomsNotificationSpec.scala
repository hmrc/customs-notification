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

package acceptance

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test.Helpers._
import util.TestData._
import util._

import scala.concurrent.Future
import scala.xml.NodeSeq
import scala.xml.Utility.trim
import scala.xml.XML.loadString

class CustomsNotificationSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService with NotificationQueueService with TableDrivenPropertyChecks
  with PublicNotificationService
  with GoogleAnalyticsSenderService {

  private val endpoint = "/customs-notification/notify"
  private val googleAnalyticsTrackingId: String = "UA-12345678-2"
  private val googleAnalyticsClientId: String = "555"
  private val googleAnalyticsEventValue = "10"

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("googleAnalytics.trackingId" -> googleAnalyticsTrackingId) +
      ("googleAnalytics.clientId" -> googleAnalyticsClientId) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue)).build()


  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue) _


  override protected def beforeAll() {
    startMockServer()
    setupPublicNotificationServiceToReturn()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  feature("Ensure call to public notification service is made when request is valid") {

    scenario("DMS/MDG submits a valid request") {
      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      setupGoogleAnalyticsEndpoint()

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty

      And("the notification gateway service was called correctly")
      eventually(verifyPublicNotificationServiceWasCalledWith(createPushNotificationRequestPayload()))
      eventually(verifyNotificationQueueServiceWasNotCalled())
      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(2))

      callWasMadeToGoogleAnalyticsWith("notificationRequestReceived",
        s"[ConversationId=$validConversationId] A notification received for delivery") shouldBe true

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestSuccess",
        s"[ConversationId=$validConversationId] A notification has been pushed successfully") shouldBe true
    }

    scenario("DMS/MDG submits a valid request with incorrect callback details used") {
      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      setupPublicNotificationServiceToReturn(404)
      setupGoogleAnalyticsEndpoint()
      runNotificationQueueService(CREATED)

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty

      And("the notification gateway service was called correctly")
      eventually(verifyPublicNotificationServiceWasCalledWith(createPushNotificationRequestPayload()))
      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(3))

      callWasMadeToGoogleAnalyticsWith("notificationRequestReceived",
        s"[ConversationId=$validConversationId] A notification received for delivery") shouldBe true

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestFailed",
        s"[ConversationId=$validConversationId] A notification Push request failed") shouldBe true

      callWasMadeToGoogleAnalyticsWith("notificationLeftToBePulled",
        s"[ConversationId=$validConversationId] A notification has been left to be pulled") shouldBe true
    }

  }

  private val table =
    Table(
      ("description", "request", "expected response code", "expected response XML"),
      ("accept header missing", MissingAcceptHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), NOT_ACCEPTABLE, errorResponseForMissingAcceptHeader),
      ("content type invalid", InvalidContentTypeHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), UNSUPPORTED_MEDIA_TYPE, errorResponseForInvalidContentType),
      ("unauthorized", InvalidAuthorizationHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), UNAUTHORIZED, errorResponseForUnauthorized),
      ("client id invalid", InvalidClientIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForInvalidClientId),
      ("client id missing", MissingClientIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForMissingClientId),
      ("conversation id invalid", InvalidConversationIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForInvalidConversationId),
      ("conversation id missing", MissingConversationIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForMissingConversationId),
      ("empty payload", ValidRequest.withXmlBody(NodeSeq.Empty).copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForPlayXmlBodyParserError)
    )

  feature("For invalid requests, ensure error payloads are correct") {

    forAll(table) { (description, request, httpCode, responseXML) =>

      scenario(s"DMS/MDG submits an invalid request with $description") {

        Given("the API is available")

        When("a POST request with data is sent to the API")
        val result: Option[Future[Result]] = route(app = app, request)

        Then(s"a response with a $httpCode status is received")
        result shouldBe 'defined
        val resultFuture: Future[Result] = result.value

        status(resultFuture) shouldBe httpCode

        And(s"the response body is $description XML")
        trim(loadString(contentAsString(resultFuture))) shouldBe trim(responseXML)
      }
    }

    scenario("DMS/MDG submits a request with clientId that can not be found") {
      setupApiSubscriptionFieldsServiceToReturn(NOT_FOUND, validFieldsId)

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 400 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe BAD_REQUEST

      And("the response body is for client id not found")
      val s = contentAsString(resultFuture)
      trim(loadString(s)) shouldBe trim(errorResponseForClientIdNotFound)
    }

  }

}
